# MCP Server Connector for IBM VDI 10

A custom IBM Verify Directory Integrator (VDI 10) **Server-mode connector** that turns an AssemblyLine into an **MCP (Model Context Protocol) server** over Streamable HTTP. When the AL runs, it *is* an MCP server: an inbound `tools/call` becomes the AL's work Entry, and the AL's reply Entry becomes the tool result. Validated end-to-end with **Claude Code as a live MCP client**.

## How it works

- Subclasses the stock `HTTPServerConnector` to reuse its socket/TLS/HTTP framing, adding a JSON-RPC 2.0 / MCP message layer on top.
- `initialize`, `notifications/initialized`, and `tools/list` are answered directly by the connector. `tools/call` is mapped to a work Entry (`$mcp.tool`, `$mcp.arguments`, flattened scalar args, …); the AL's Data Flow does the work and sets `$mcp.result`/`$mcp.structured`/`$mcp.isError`, which the connector translates back into the MCP tool result.
- Stateless JSON responses (no SSE/sessions in v1). Transport security: bearer token, optional mTLS, `Origin` allow-list, method/path/protocol-version enforcement.

## Documentation

- **[docs/SPEC.md](docs/SPEC.md)** — full design, locked decisions, phased plan, and the narrative of every phase including the bugs found and fixed live.
- **[docs/CONFIGURE.md](docs/CONFIGURE.md)** — step-by-step setup: connector config, the three attribute contracts, security, connecting Claude, troubleshooting.
- **[docs/GOTCHAS.md](docs/GOTCHAS.md)** — condensed index of the non-obvious VDI platform behaviors that shaped this connector. **Read this first if you're extending or porting it.**
- **[docs/BACKLOG.md](docs/BACKLOG.md)** — what's done in the v1 tail, what's deferred, and design notes for v2 (SSE, sessions).

## Build

Requires a local VDI 10 install (its `jars/common`, `jars/connectors`, and `jars/3rdparty` jars are used compile-only — nothing is repackaged).

```
mvn -Dvdi.home=/path/to/ISVDI package
```

Defaults to `/Users/jason/Applications/ISVDI` if `-Dvdi.home` is omitted.

## Prebuilt jar

A prebuilt jar is checked in at [`dist/mcp-server-connector.jar`](dist/mcp-server-connector.jar) for convenience — grab it directly if you don't want to build. It depends only on jars already present in a VDI 10 install (nothing is repackaged), so it runs as-is. Rebuild from source (above) if you want to verify or modify it.

## Deploy

Copy the jar (`dist/mcp-server-connector.jar`, or `target/mcp-server-connector.jar` if you built it) to `VDI_install_dir/jars/connectors/`, restart the Config Editor / server, and the `MCPServerConnector` connector appears under Connectors. See [docs/CONFIGURE.md](docs/CONFIGURE.md) to build the AssemblyLine and connect a client.

## Testing

Test with `curl` before pointing any MCP client at it — it isolates connector behavior from client-specific transport quirks.

```bash
# tools/list (no auth)
curl -s -X POST http://127.0.0.1:8443/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

# tools/call
curl -s -X POST http://127.0.0.1:8443/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"<tool>","arguments":{}}}'
```

If `authMode=bearer`, add `-H 'Authorization: Bearer <token>'` (the raw token value only, not `bearerToken=<token>`).

**If `allowedOrigins` is configured, expect `403 {"error":"Origin not allowed."}` from the commands above** — curl sends no `Origin` header by default, and a non-empty allowlist rejects that outright (see [docs/GOTCHAS.md §7](docs/GOTCHAS.md)). To test that path specifically, add a matching header:

```bash
curl -s -X POST http://127.0.0.1:8443/mcp \
  -H 'Content-Type: application/json' \
  -H 'Origin: <one-of-your-allowedOrigins-values>' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/list"}'
```

For local/dev testing where you don't know or don't care what `Origin` a client will send, leave `allowedOrigins` empty — this disables the check entirely rather than failing closed against a value you can't predict. Real MCP clients, including Claude's, *do* send `Origin`, so don't assume a `403` here means the client is broken — check the AL log for the actual `Origin` value the client sent and match your allowlist to that, rather than reaching for a code change.

Full curl walkthrough (`initialize`, troubleshooting table) is in [docs/CONFIGURE.md §5](docs/CONFIGURE.md). [MCP Inspector](https://github.com/modelcontextprotocol/inspector) works too — point it at the same URL.

## Quick connect (Claude Code)

```
claude mcp add --transport http vdi-mcp http://127.0.0.1:8443/mcp \
  --header "Authorization: Bearer <your-token>"
```

Start a fresh Claude Code session (tools register at session start), then ask it to use one of your configured tools. Note: `--header "Origin: ..."` does **not** work here — `Origin` is a forbidden header name for `fetch()`-based clients and gets silently dropped; if `allowedOrigins` is set, it must match whatever `Origin` Claude Code actually sends on its own, not a value you inject via `--header`.

## License

[MIT](LICENSE) — provided "as is", without warranty of any kind. IBM Security Directory Integrator and IBM Verify Identity Governance are trademarks of IBM; this is an independent connector, not an IBM product.
