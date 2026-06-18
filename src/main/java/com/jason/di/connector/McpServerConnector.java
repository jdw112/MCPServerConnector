package com.jason.di.connector;

import com.ibm.di.connector.HTTPServerConnector;

/**
 * MCP (Model Context Protocol) server connector for VDI 10.
 *
 * Subclasses HTTPServerConnector to reuse its socket/TLS/chunking/HTTP
 * framing (see docs/SPEC.md §1); this class adds the JSON-RPC/MCP message
 * layer on top. Phase 1 implements initialize / tools.list / tools.call.
 */
public class McpServerConnector extends HTTPServerConnector {

    public static final String VERSION_INFO = "0.1.0-SNAPSHOT";

    @Override
    public String getVersion() {
        return VERSION_INFO;
    }
}
