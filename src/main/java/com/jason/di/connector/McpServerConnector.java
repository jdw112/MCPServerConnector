package com.jason.di.connector;

import com.ibm.di.connector.HTTPServerConnector;
import com.ibm.di.entry.Entry;
import com.ibm.json.java.JSONArray;
import com.ibm.json.java.JSONObject;

/**
 * MCP (Model Context Protocol) server connector for VDI 10.
 *
 * Subclasses HTTPServerConnector to reuse its socket/TLS/chunking/HTTP
 * framing (see docs/SPEC.md §1); this class adds the JSON-RPC/MCP message
 * layer on top.
 *
 * Phase 0 found that one inbound connection drives exactly one AL cycle on
 * a fresh per-connection instance (HTTPServerConnector#getNextClient), so
 * the instance fields below (pending JSON-RPC id) are safe: they never
 * outlive a single request/response exchange.
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

    @Override
    public Entry getNextEntry() throws Exception {
        Entry httpEntry = super.getNextEntry();
        if (httpEntry == null) {
            return null;
        }

        String body = httpEntry.getString(ATTR_NAME_HTTP_BODY);
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
        pendingCallId = id;

        Object params = rpc.get("params");
        JSONObject paramsObj = params instanceof JSONObject ? (JSONObject) params : new JSONObject();
        String toolName = (String) paramsObj.get("name");
        Object arguments = paramsObj.get("arguments");

        Entry work = new Entry();
        work.setAttribute(ATTR_MCP_TOOL, toolName != null ? toolName : "");
        work.setAttribute(ATTR_MCP_REQUEST_ID, id != null ? id.toString() : "");
        work.setAttribute(ATTR_MCP_PROTOCOL_VERSION, PROTOCOL_VERSION);
        work.setAttribute(ATTR_MCP_ARGUMENTS, arguments != null ? arguments.toString() : "{}");
        return work;
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
        JSONArray tools;
        try {
            tools = JSONArray.parse(getParam("toolCatalog"));
        } catch (Exception e) {
            tools = new JSONArray();
        }

        JSONObject result = new JSONObject();
        result.put("tools", tools);

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
