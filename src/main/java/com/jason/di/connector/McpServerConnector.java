package com.jason.di.connector;

import com.ibm.di.connector.ConnectorInterface;
import com.ibm.di.connector.HTTPServerConnector;
import com.ibm.di.entry.Entry;
import com.ibm.di.exceptions.RetryEntryException;
import com.ibm.json.java.JSONArray;
import com.ibm.json.java.JSONObject;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
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

    /** Server's preferred protocol version, returned by initialize when the client doesn't request a supported one. */
    public static final String PROTOCOL_VERSION = "2025-11-25";

    /**
     * Versions we'll interoperate with. The JSON-RPC surface we implement
     * (initialize/tools.list/tools.call) is stable across these, so we accept
     * any of them on the MCP-Protocol-Version header and echo a client's
     * requested version back from initialize if it's one of these. Keeps us
     * compatible with real clients (e.g. Claude) that may negotiate an older
     * version rather than only our single preferred one.
     */
    private static final Set<String> SUPPORTED_PROTOCOL_VERSIONS = new HashSet<>(Arrays.asList(
            "2025-11-25", "2025-06-18", "2025-03-26"));

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

        // bindAddress enforcement. The inherited HTTPServerConnector binds the
        // listening socket to all interfaces (0.0.0.0) — it has no per-address
        // bind option. So we enforce bindAddress post-accept: reject any
        // connection that didn't arrive on the configured local address, then
        // RetryEntryException to go accept the next one (NOT return null, which
        // would terminate the listener). This restricts which interface the
        // service is effectively reachable on even though the socket listens
        // broadly. See docs/CONFIGURE.md.
        if (!isLocalAddressAllowed(socket)) {
            String got = socket.getLocalAddress() == null ? "?" : socket.getLocalAddress().getHostAddress();
            socket.close();
            throw new RetryEntryException("McpServerConnector: rejected connection received on " + got
                    + " (bindAddress=" + getParam("bindAddress") + ")");
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

    /** True if the connection arrived on the configured bindAddress (or no/0.0.0.0 bindAddress configured = any). */
    private boolean isLocalAddressAllowed(Socket socket) {
        String bindAddr = getParam("bindAddress");
        if (bindAddr == null || bindAddr.trim().isEmpty() || "0.0.0.0".equals(bindAddr.trim())) {
            return true;
        }
        InetAddress local = socket.getLocalAddress();
        return local != null && bindAddr.trim().equals(local.getHostAddress());
    }

    /**
     * §2: endpointPath enforcement. When endpointPath is configured non-empty,
     * a request to any other path gets 404. http.base is the request path
     * without query string (confirmed via the CE's Entry dumps); fall back to
     * http.url. Empty endpointPath = accept any path (permissive).
     */
    private boolean isEndpointPathAllowed(Entry httpEntry) {
        String configured = getParam("endpointPath");
        if (configured == null || configured.trim().isEmpty()) {
            return true;
        }
        return configured.trim().equals(requestPath(httpEntry));
    }

    /** Request path without query string: http.base, falling back to http.url. */
    private String requestPath(Entry httpEntry) {
        String path = httpEntry.getString("http.base");
        return path != null ? path : httpEntry.getString("http.url");
    }

    /** GET on the configured healthPath (when set non-empty). */
    private boolean isHealthRequest(Entry httpEntry) {
        String healthPath = getParam("healthPath");
        if (healthPath == null || healthPath.trim().isEmpty()) {
            return false;
        }
        String method = httpEntry.getString("http.method");
        if (method != null && !"GET".equalsIgnoreCase(method)) {
            return false;
        }
        return healthPath.trim().equals(requestPath(httpEntry));
    }

    private void sendHealth(Entry httpEntry) throws Exception {
        JSONObject body = new JSONObject();
        body.put("status", "ok");
        body.put("server", "mcp-server-connector");
        body.put("version", VERSION_INFO);
        sendJson(buildHttpReplyEntry(body, HTTP_OK));
    }

    /**
     * §6/Advanced: reject over-large requests with 413 when maxRequestBytes > 0.
     * Best-effort: the inherited HTTP parser has already buffered the body by the
     * time we see the entry, so this guards processing (and signals the client),
     * not the read itself — a true pre-read cap would need overriding the parser.
     */
    private boolean isRequestTooLarge(Entry httpEntry) {
        int max = parseIntParam("maxRequestBytes", 0);
        if (max <= 0) {
            return false;
        }
        String contentLength = getHeader(httpEntry, "Content-Length");
        if (contentLength != null) {
            try {
                if (Integer.parseInt(contentLength.trim()) > max) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // fall through to the actual-body check
            }
        }
        String body = httpEntry.getString("http.bodyAsString");
        return body != null && body.getBytes(StandardCharsets.UTF_8).length > max;
    }

    private int parseIntParam(String name, int dflt) {
        String v = getParam(name);
        if (v == null || v.trim().isEmpty()) {
            return dflt;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /**
     * HTTP header names land as http.<HeaderName> attributes preserving the
     * exact case the client sent (confirmed via the CE's Entry dumps: e.g.
     * http.Content-Type, http.User-Agent). Headers are case-insensitive per
     * RFC 7230, so look up by name case-insensitively rather than assuming
     * any particular casing.
     */
    private String getHeader(Entry httpEntry, String headerName) {
        String target = "http." + headerName;
        for (String attrName : httpEntry.getAttributeNames()) {
            if (attrName.equalsIgnoreCase(target)) {
                return httpEntry.getString(attrName);
            }
        }
        return null;
    }

    /**
     * §2/§6: validate Origin when allowedOrigins is configured. Left
     * permissive (no allowedOrigins configured, or no Origin header sent at
     * all — e.g. curl/MCP Inspector/non-browser clients) to match the
     * connector's localhost-by-default v1 posture documented in
     * docs/CONFIGURE.md.
     */
    private boolean isOriginAllowed(Entry httpEntry) {
        String allowed = getParam("allowedOrigins");
        if (allowed == null || allowed.trim().isEmpty()) {
            return true;
        }
        String origin = getHeader(httpEntry, "Origin");
        if (origin == null) {
            return true;
        }
        for (String candidate : allowed.split(",")) {
            if (candidate.trim().equalsIgnoreCase(origin.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * §6: bearer token check, only enforced when authMode=bearer. authMode=mtls
     * relies entirely on the TLS layer (useSSL + needClientAuth, both inherited
     * unmodified from HTTPServerConnector — the per-connection Socket returned
     * by getNextClient()'s accept() is already an SSLSocket if the listener was
     * configured for SSL, so client-cert verification happens before our code
     * ever runs). authMode=none performs no check here.
     */
    private boolean isBearerAuthorized(Entry httpEntry) {
        String mode = getParam("authMode");
        if (mode == null || !"bearer".equalsIgnoreCase(mode)) {
            return true;
        }
        String expected = getParam("bearerToken");
        if (expected == null || expected.isEmpty()) {
            logmsg("McpServerConnector: authMode=bearer but no bearerToken configured; rejecting all requests.");
            return false;
        }
        String header = getHeader(httpEntry, "Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        String provided = header.substring("Bearer ".length());
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    /** §2: absent header is allowed (spec default is 2025-03-26); present-and-unsupported is rejected. */
    private boolean isProtocolVersionAcceptable(Entry httpEntry) {
        String header = getHeader(httpEntry, "MCP-Protocol-Version");
        return header == null || SUPPORTED_PROTOCOL_VERSIONS.contains(header);
    }

    private void sendTransportError(Entry httpEntry, String httpStatus, String message) throws Exception {
        JSONObject body = new JSONObject();
        body.put("error", message);
        sendJson(buildHttpReplyEntry(body, httpStatus));
    }

    @Override
    public Entry getNextEntry() throws Exception {
        Entry httpEntry = super.getNextEntry();
        if (httpEntry == null) {
            return null;
        }

        // Health probe (GET on the configured healthPath) is answered before any
        // endpoint/method/auth checks — liveness checks are unauthenticated and
        // distinct from the MCP endpoint's GET (which returns 405).
        if (isHealthRequest(httpEntry)) {
            sendHealth(httpEntry);
            return null;
        }

        if (!isEndpointPathAllowed(httpEntry)) {
            sendTransportError(httpEntry, "404 Not Found", "No MCP endpoint at this path.");
            return null;
        }

        String httpMethod = httpEntry.getString("http.method");
        if (httpMethod != null && !"POST".equalsIgnoreCase(httpMethod)) {
            sendTransportError(httpEntry, "405 Method Not Allowed", "Only POST is supported on this endpoint.");
            return null;
        }

        if (isRequestTooLarge(httpEntry)) {
            sendTransportError(httpEntry, "413 Payload Too Large", "Request body exceeds the configured maximum.");
            return null;
        }

        if (!isOriginAllowed(httpEntry)) {
            sendTransportError(httpEntry, "403 Forbidden", "Origin not allowed.");
            return null;
        }

        if (!isBearerAuthorized(httpEntry)) {
            sendTransportError(httpEntry, "401 Unauthorized", "Missing or invalid bearer token.");
            return null;
        }

        // http.body is the raw byte[]; HTTPServerConnector also exposes a decoded
        // String copy under http.bodyAsString (confirmed via the CE's Entry dump),
        // which is what we actually want for JSON parsing.
        String body = httpEntry.getString("http.bodyAsString");
        JSONObject rpc;
        // Catch Throwable, not just Exception: JSON4J's parser is recursive, so a
        // deeply nested payload throws StackOverflowError (an Error, not an
        // Exception). Without this it would propagate and kill the AL cycle
        // instead of returning a clean JSON-RPC parse error.
        try {
            rpc = JSONObject.parse(body == null ? "" : body);
        } catch (Throwable t) {
            sendError(httpEntry, null, -32700, "Parse error: " + t.getClass().getSimpleName());
            return null;
        }

        Object id = rpc.get("id");
        Object methodObj = rpc.get("method");
        if (!(methodObj instanceof String)) {
            sendError(httpEntry, id, -32600, "Invalid Request: missing method");
            return null;
        }
        String method = (String) methodObj;

        // MCP-Protocol-Version is unknown to the client until initialize() responds,
        // so it's only enforced on requests after that handshake.
        if (!"initialize".equals(method) && !isProtocolVersionAcceptable(httpEntry)) {
            sendTransportError(httpEntry, "400 Bad Request", "Unsupported or invalid MCP-Protocol-Version header.");
            return null;
        }

        switch (method) {
            case "initialize":
                sendInitializeResult(httpEntry, id, rpc);
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

        // Reflect the protocol version the client is actually using on this
        // request (the MCP-Protocol-Version header, which a compliant client
        // sets to whatever initialize negotiated), falling back to our default.
        String reqVersion = getHeader(httpEntry, "MCP-Protocol-Version");
        if (reqVersion == null) {
            reqVersion = PROTOCOL_VERSION;
        }

        Entry work = new Entry();
        work.setAttribute(ATTR_MCP_TOOL, toolName);
        work.setAttribute(ATTR_MCP_REQUEST_ID, id != null ? id.toString() : "");
        work.setAttribute(ATTR_MCP_PROTOCOL_VERSION, reqVersion);
        work.setAttribute(ATTR_MCP_ARGUMENTS, argumentsObj.serialize());

        // §3.2: flatten top-level scalar arguments directly onto the Entry too,
        // so the AL's Data Flow can read e.g. work.text instead of always having
        // to re-parse $mcp.arguments for simple cases. Nested objects/arrays are
        // only available via $mcp.arguments.
        //
        // SECURITY: never let a client-supplied argument named "$mcp.*" overwrite
        // a reserved attribute. Without this, a call to a whitelisted tool could
        // smuggle {"$mcp.tool":"<other tool>"} in its arguments and clobber the
        // validated tool name set above — bypassing the toolCatalog allowlist that
        // the AL branches on. Reserved keys are skipped; they remain only as the
        // values the connector itself set.
        for (Object key : argumentsObj.keySet()) {
            String name = key.toString();
            if (name.startsWith("$mcp.")) {
                continue;
            }
            Object value = argumentsObj.get(key);
            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                work.setAttribute(name, value.toString());
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

    /** Echo the client's requested protocolVersion if we support it; otherwise advertise our preferred one. */
    private String negotiateProtocolVersion(JSONObject rpc) {
        Object params = rpc.get("params");
        if (params instanceof JSONObject) {
            Object requested = ((JSONObject) params).get("protocolVersion");
            if (requested instanceof String && SUPPORTED_PROTOCOL_VERSIONS.contains(requested)) {
                return (String) requested;
            }
        }
        return PROTOCOL_VERSION;
    }

    private void sendInitializeResult(Entry httpEntry, Object id, JSONObject rpc) throws Exception {
        JSONObject result = new JSONObject();
        result.put("protocolVersion", negotiateProtocolVersion(rpc));
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
