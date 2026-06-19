package com.jason.di.connector;

import com.ibm.di.connector.ConnectorInterface;
import com.ibm.di.connector.HTTPServerConnector;
import com.ibm.di.entry.Entry;
import com.ibm.di.exceptions.RetryEntryException;
import com.ibm.json.java.JSONArray;
import com.ibm.json.java.JSONObject;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * MCP (Model Context Protocol) server connector for VDI 10.
 *
 * Subclasses HTTPServerConnector to reuse its socket/TLS/chunking/HTTP
 * framing (see docs/SPEC.md §1); this class adds the JSON-RPC/MCP message
 * layer on top.
 *
 * HTTPServerConnector#getNextClient() hardcodes `new HTTPServerConnector()`
 * for the per-connection object it hands back (confirmed by disassembling
 * HTTPServerConnector.class — it is not `this.getClass().newInstance()` or
 * a clone). That means a plain subclass's getNextEntry()/replyEntry()
 * overrides are never invoked for real traffic: the framework always
 * processes the connection on a base-class instance. getNextClient() is
 * overridden below to replicate the exact same setup sequence (verified
 * step-by-step against the decompiled bytecode) but constructing
 * `new McpServerConnector()` instead, so our overrides actually run.
 *
 * Every step that sequence performs uses public inherited methods
 * (setServerConnector/setConfiguration/setRSInterface/setName/setLog/
 * initialize(Socket)) except obtaining the listening socket itself
 * (mServerSocket is private with no accessor), which is why reflection is
 * used for that one field only.
 *
 * One inbound connection still drives exactly one AL cycle on a fresh
 * per-connection instance, so the instance fields below (pending JSON-RPC
 * id) are safe: they never outlive a single request/response exchange.
 *
 * Protocol bookkeeping methods (initialize, notifications/initialized,
 * tools/list) are answered directly here without involving the AL, since
 * they don't carry business logic. Only tools/call is handed to the AL as
 * a work Entry; the AL's reply Entry is translated back into the MCP tool
 * result in replyEntry().
 */
public class McpServerConnector extends HTTPServerConnector {

    public static final String VERSION_INFO = "0.1.0-SNAPSHOT";
    public static final String PROTOCOL_VERSION = "2025-11-25";

    public static final String ATTR_MCP_TOOL = "$mcp.tool";
    public static final String ATTR_MCP_REQUEST_ID = "$mcp.requestId";
    public static final String ATTR_MCP_PROTOCOL_VERSION = "$mcp.protocolVersion";
    public static final String ATTR_MCP_ARGUMENTS = "$mcp.arguments";

    public static final String ATTR_MCP_RESULT = "$mcp.result";
    public static final String ATTR_MCP_STRUCTURED = "$mcp.structured";
    public static final String ATTR_MCP_IS_ERROR = "$mcp.isError";

    private static final String HTTP_OK = "200 OK";
    private static final String HTTP_ACCEPTED = "202 Accepted";
    private static final String CONTENT_TYPE_JSON = "application/json";

    /** JSON-RPC id of the in-flight tools/call, held between getNextEntry() and replyEntry(). */
    private Object pendingCallId;

    @Override
    public String getVersion() {
        return VERSION_INFO;
    }

    /**
     * Replicates HTTPServerConnector#getNextClient()'s accept/setup sequence
     * (verified against the decompiled bytecode), substituting our own
     * subclass for the per-connection object so getNextEntry()/replyEntry()
     * overrides below actually run for real traffic. See class doc.
     */
    @Override
    public ConnectorInterface getNextClient() throws Exception {
        ServerSocket serverSocket = getListeningSocket();
        if (serverSocket == null) {
            throw new Exception("McpServerConnector.getNextClient() called on a non-listening (per-connection) instance");
        }

        if (isTerminating()) {
            logmsg("McpServerConnector: terminated by external request before accept().");
            return null;
        }

        Socket socket = serverSocket.accept();

        if (isTerminating()) {
            logmsg("McpServerConnector: terminated by external request after accept().");
            socket.close();
            return null;
        }

        McpServerConnector client = new McpServerConnector();
        client.setServerConnector(this);
        client.setConfiguration(this.getConfiguration());
        client.setRSInterface(this.getRSInterface());
        client.setName(this.getName());
        client.setLog(this.getLog());
        try {
            client.initialize(socket);
        } catch (IOException | NoSuchElementException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // best-effort close on a socket we're already abandoning
            }
            throw new RetryEntryException("McpServerConnector: failed to initialize connection: " + e.getMessage());
        }
        return client;
    }

    private ServerSocket getListeningSocket() throws Exception {
        Field field = HTTPServerConnector.class.getDeclaredField("mServerSocket");
        field.setAccessible(true);
        return (ServerSocket) field.get(this);
    }

    @Override
    public Entry getNextEntry() throws Exception {
        Entry httpEntry = super.getNextEntry();
        if (httpEntry == null) {
            return null;
        }

        // http.body is the raw byte[]; HTTPServerConnector also exposes a decoded
        // String copy under http.bodyAsString (confirmed via the CE's Entry dump),
        // which is what we actually want for JSON parsing.
        String body = httpEntry.getString("http.bodyAsString");
        JSONObject rpc;
        try {
            rpc = JSONObject.parse(body == null ? "" : body);
        } catch (Exception e) {
            sendError(httpEntry, null, -32700, "Parse error: " + e.getMessage());
            return null;
        }

        Object id = rpc.get("id");
        Object methodObj = rpc.get("method");
        if (!(methodObj instanceof String)) {
            sendError(httpEntry, id, -32600, "Invalid Request: missing method");
            return null;
        }
        String method = (String) methodObj;

        switch (method) {
            case "initialize":
                sendInitializeResult(httpEntry, id);
                return null;
            case "notifications/initialized":
                sendAccepted(httpEntry);
                return null;
            case "tools/list":
                sendToolsList(httpEntry, id);
                return null;
            case "tools/call":
                return buildToolCallEntry(httpEntry, id, rpc);
            default:
                sendError(httpEntry, id, -32601, "Method not found: " + method);
                return null;
        }
    }

    @Override
    public void replyEntry(Entry aEntry) throws Exception {
        JSONObject response = new JSONObject();
        response.put("jsonrpc", "2.0");
        response.put("id", pendingCallId);

        boolean isError = "true".equalsIgnoreCase(aEntry.getString(ATTR_MCP_IS_ERROR));
        String resultText = aEntry.getString(ATTR_MCP_RESULT);
        String structuredJson = aEntry.getString(ATTR_MCP_STRUCTURED);

        JSONObject result = new JSONObject();
        JSONArray content = new JSONArray();
        JSONObject textBlock = new JSONObject();
        textBlock.put("type", "text");
        textBlock.put("text", resultText != null ? resultText : aEntry.toString());
        content.add(textBlock);
        result.put("content", content);
        result.put("isError", isError);
        if (structuredJson != null) {
            try {
                result.put("structuredContent", JSONObject.parse(structuredJson));
            } catch (Exception e) {
                logmsg("McpServerConnector: ignoring unparsable $mcp.structured: " + e.getMessage());
            }
        }
        response.put("result", result);

        sendJson(buildHttpReplyEntry(response, HTTP_OK));
    }

    private Entry buildToolCallEntry(Entry httpEntry, Object id, JSONObject rpc) throws Exception {
        Object params = rpc.get("params");
        JSONObject paramsObj = params instanceof JSONObject ? (JSONObject) params : new JSONObject();
        String toolName = (String) paramsObj.get("name");
        Object arguments = paramsObj.get("arguments");
        JSONObject argumentsObj = arguments instanceof JSONObject ? (JSONObject) arguments : new JSONObject();

        if (toolName == null || !getToolNames().contains(toolName)) {
            sendToolError(id, "Unknown tool: " + toolName);
            return null;
        }

        pendingCallId = id;

        Entry work = new Entry();
        work.setAttribute(ATTR_MCP_TOOL, toolName);
        work.setAttribute(ATTR_MCP_REQUEST_ID, id != null ? id.toString() : "");
        work.setAttribute(ATTR_MCP_PROTOCOL_VERSION, PROTOCOL_VERSION);
        work.setAttribute(ATTR_MCP_ARGUMENTS, argumentsObj.serialize());

        // §3.2: flatten top-level scalar arguments directly onto the Entry too,
        // so the AL's Data Flow can read e.g. work.text instead of always having
        // to re-parse $mcp.arguments for simple cases. Nested objects/arrays are
        // only available via $mcp.arguments.
        for (Object key : argumentsObj.keySet()) {
            Object value = argumentsObj.get(key);
            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                work.setAttribute(key.toString(), value.toString());
            }
        }

        return work;
    }

    /** Parses the configured toolCatalog (falling back to an empty array on bad JSON). */
    private JSONArray getToolCatalog() {
        try {
            JSONArray tools = JSONArray.parse(getParam("toolCatalog"));
            return tools != null ? tools : new JSONArray();
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private Set<String> getToolNames() {
        Set<String> names = new HashSet<>();
        for (Object entry : getToolCatalog()) {
            if (entry instanceof JSONObject) {
                Object name = ((JSONObject) entry).get("name");
                if (name instanceof String) {
                    names.add((String) name);
                }
            }
        }
        return names;
    }

    /** A well-formed tools/call whose tool name isn't in the catalog: a tool-level error, not a JSON-RPC protocol error. */
    private void sendToolError(Object id, String message) throws Exception {
        JSONObject textBlock = new JSONObject();
        textBlock.put("type", "text");
        textBlock.put("text", message);
        JSONArray content = new JSONArray();
        content.add(textBlock);

        JSONObject result = new JSONObject();
        result.put("content", content);
        result.put("isError", true);

        JSONObject response = new JSONObject();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);

        sendJson(buildHttpReplyEntry(response, HTTP_OK));
    }

    private void sendInitializeResult(Entry httpEntry, Object id) throws Exception {
        JSONObject result = new JSONObject();
        result.put("protocolVersion", PROTOCOL_VERSION);
        JSONObject capabilities = new JSONObject();
        capabilities.put("tools", new JSONObject());
        result.put("capabilities", capabilities);
        JSONObject serverInfo = new JSONObject();
        serverInfo.put("name", "mcp-server-connector");
        serverInfo.put("version", VERSION_INFO);
        result.put("serverInfo", serverInfo);

        JSONObject response = new JSONObject();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);

        sendJson(buildHttpReplyEntry(response, HTTP_OK));
    }

    private void sendToolsList(Entry httpEntry, Object id) throws Exception {
        JSONObject result = new JSONObject();
        result.put("tools", getToolCatalog());

        JSONObject response = new JSONObject();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);

        sendJson(buildHttpReplyEntry(response, HTTP_OK));
    }

    private void sendAccepted(Entry httpEntry) throws Exception {
        Entry reply = new Entry();
        reply.setAttribute(ATTR_NAME_HTTP_BODY, "");
        reply.setAttribute(ATTR_NAME_HTTP_CONTENT_TYPE, CONTENT_TYPE_JSON);
        reply.setAttribute("http.status", HTTP_ACCEPTED);
        super.replyEntry(reply);
    }

    private void sendError(Entry httpEntry, Object id, int code, String message) throws Exception {
        JSONObject error = new JSONObject();
        error.put("code", code);
        error.put("message", message);

        JSONObject response = new JSONObject();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", error);

        sendJson(buildHttpReplyEntry(response, HTTP_OK));
    }

    private Entry buildHttpReplyEntry(JSONObject body, String status) throws Exception {
        Entry reply = new Entry();
        reply.setAttribute(ATTR_NAME_HTTP_BODY, body.serialize());
        reply.setAttribute(ATTR_NAME_HTTP_CONTENT_TYPE, CONTENT_TYPE_JSON);
        reply.setAttribute("http.status", status);
        return reply;
    }

    private void sendJson(Entry reply) throws Exception {
        super.replyEntry(reply);
    }
}
