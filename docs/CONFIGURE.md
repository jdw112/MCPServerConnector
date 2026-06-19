# Configuring the MCP Server Connector

## 1. Deploy

Copy the built jar into VDI's connector directory, then restart the server / Config Editor so it rescans `jars/connectors`:

```
cp target/mcp-server-connector.jar /path/to/ISVDI/jars/connectors/
```

## 2. Create the AssemblyLine

1. In the Config Editor, create a new AssemblyLine.
2. Under **Feed**, add a connector and pick **MCPServer** from the connector list.
3. Set **Mode** to `Server`.

## 3. Connection tab settings

| Field | What to put | Notes |
| --- | --- | --- |
| TCP Port | e.g. `8443` | Required. Port the listener binds to. |
| Bind Address | `127.0.0.1` | **Not yet enforced by code** — see Known limitations below. |
| Endpoint Path | `/mcp` | **Not yet enforced by code** — see Known limitations below. |
| Tool Catalog (JSON) | a JSON array of tool definitions, e.g.: `[{"name":"lookup_user","description":"Look up a user by id","inputSchema":{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}}]` | Returned verbatim by `tools/list`. Must be valid JSON or it's treated as an empty catalog. |
| Comment / Detailed Log | optional | |

Security section (Authentication Mode, Bearer Token, Allowed Origins, Use SSL, Require Client Certificate) is now enforced as of Phase 3:

- **`authMode: bearer`** requires `Authorization: Bearer <bearerToken>` on every request (constant-time compared); missing/wrong token → `401`. If `bearerToken` is left blank while `authMode=bearer`, every request is rejected (fail closed, not open).
- **`authMode: mtls`** does nothing extra in the connector's own logic — set `useSSL=true` and `needClientAuth=true` instead. Those two are inherited, unmodified `HTTPServerConnector` parameters; client certificate verification happens at the TLS handshake, before any of our code runs.
- **`authMode: none`** performs no authentication check at all.
- **Allowed Origins**: only enforced when non-empty. A request with no `Origin` header (curl, MCP Inspector, most non-browser clients) is always allowed through regardless of this setting — it only protects against browser-based DNS-rebinding-style attacks, per the MCP spec's intent.

## 4. Data Flow

Add whatever AL logic should run per `tools/call`. The connector hands the AL a work Entry with these attributes already set:

- `$mcp.tool` — the tool name from the JSON-RPC `params.name`
- `$mcp.requestId` — the JSON-RPC request id
- `$mcp.protocolVersion` — `2025-11-25`
- `$mcp.arguments` — the raw JSON string of `params.arguments`
- Any top-level **scalar** argument is also flattened directly onto the Entry under its own name (e.g. `arguments: {"text": "hello"}` also sets a plain `text` attribute) — convenient for simple tools that don't want to re-parse JSON. Nested objects/arrays are only available via `$mcp.arguments`.

As of Phase 2, `tools/call` validates the tool name against `toolCatalog` **before** invoking the AL: if the name isn't present in the catalog (or is missing), the connector responds directly with a tool-level error (`isError: true`, no AL cycle spent) rather than handing it to the Data Flow.

**This makes the Tool Catalog field load-bearing, not just descriptive.** The `tdi.xml` default is `[]` (empty array) — if you never explicitly paste your tool definitions into the Connection tab's **Tool Catalog (JSON)** field, *every* `tools/call` will fail with "Unknown tool", even for a tool your Data Flow correctly implements. Before debugging Data Flow logic, check this field actually has content.

Have the AL branch on `$mcp.tool`, do its work, and set on the entry it hands to the next stage (the one that ends up calling the connector's `replyEntry`):

- `$mcp.result` — text to return as the tool's text content block
- `$mcp.structured` — (optional) a JSON string returned as `structuredContent`
- `$mcp.isError` — (optional) `"true"` to mark the tool result as an MCP-level error

If none of `$mcp.result`/`$mcp.structured`/`$mcp.isError` are set, the connector falls back to serializing the whole entry as the text result.

`initialize`, `notifications/initialized`, and `tools/list` are answered directly by the connector — they never reach the AL's Data Flow.

## 5. Run and test

Start the AL. From a terminal:

```bash
# initialize
curl -s -X POST http://127.0.0.1:8443/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'

# tools/list
curl -s -X POST http://127.0.0.1:8443/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

# tools/call
curl -s -X POST http://127.0.0.1:8443/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"lookup_user","arguments":{"id":"123"}}}'
```

Or point the [MCP Inspector](https://github.com/modelcontextprotocol/inspector) at `http://127.0.0.1:<tcpPort><endpointPath>`.

## Known limitations (current state, Phase 3)

`authMode`/`bearerToken`/`allowedOrigins` are now enforced (see above), as is rejecting non-`POST` methods with `405` and validating `MCP-Protocol-Version` on post-`initialize` requests with `400`. Still not enforced: `endpointPath` and `bindAddress` — the connector accepts requests on any path at the configured port regardless of those two fields' values. They remain form-only placeholders. Don't expose this beyond localhost without `useSSL`+`needClientAuth` (mTLS) or `authMode=bearer` configured.
