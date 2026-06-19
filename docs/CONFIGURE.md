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

Security section (Authentication Mode, Bearer Token, Allowed Origins, Use SSL, Require Client Certificate) is present on the form but **none of it is wired into the connector's Java code yet** — see Known limitations. Leave these at defaults for now; they're placeholders for Phase 3.

## 4. Data Flow

Add whatever AL logic should run per `tools/call`. The connector hands the AL a work Entry with these attributes already set:

- `$mcp.tool` — the tool name from the JSON-RPC `params.name`
- `$mcp.requestId` — the JSON-RPC request id
- `$mcp.protocolVersion` — `2025-11-25`
- `$mcp.arguments` — the raw JSON string of `params.arguments`
- Any top-level **scalar** argument is also flattened directly onto the Entry under its own name (e.g. `arguments: {"text": "hello"}` also sets a plain `text` attribute) — convenient for simple tools that don't want to re-parse JSON. Nested objects/arrays are only available via `$mcp.arguments`.

As of Phase 2, `tools/call` validates the tool name against `toolCatalog` **before** invoking the AL: if the name isn't present in the catalog (or is missing), the connector responds directly with a tool-level error (`isError: true`, no AL cycle spent) rather than handing it to the Data Flow. Make sure `toolCatalog` actually lists every tool name your AL branches on.

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

## Known limitations (current state, Phase 1)

The connector currently behaves like a plain HTTP listener with no path routing, no bind-address restriction, and no auth enforcement — it accepts any request on the configured port regardless of `endpointPath`, `bindAddress`, `authMode`, `bearerToken`, or `allowedOrigins`. Those fields exist on the form (so the Config Editor doesn't show "form not found" and so the values are ready to read once wired up) but `McpServerConnector.java` doesn't yet read or act on them. That enforcement is scoped for Phase 3 (§6/§8 of [SPEC.md](SPEC.md)). Don't expose this beyond localhost until then.
