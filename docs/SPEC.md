# Build Spec — MCP Server Connector for IBM VDI 10

**Goal:** A custom IBM Verify Directory Integrator (VDI 10) **Server-mode Connector** that acts as the FEED of an AssemblyLine — modeled on the stock HTTP Server Connector — but speaks **MCP (Model Context Protocol, JSON-RPC 2.0 over Streamable HTTP)**. When the AL runs, it *is* an MCP server: an inbound `tools/call` becomes the AL's work Entry; the AL's reply Entry becomes the tool result.

**Target client:** Claude (Desktop / Code / Cowork) connecting to the AL's MCP endpoint.

**Reference install used for verification:** `/Users/jason/Applications/ISVDI` (VDI 10, Java 17 / OpenJ9 bundled JRE).

---

## 0. Locked decisions

| Decision | Value |
| --- | --- |
| Shape | **A** — SDI connector that *is* the MCP server; AL = endpoint |
| Model | **Subclass** of `com.ibm.di.connector.HTTPServerConnector` as feed of an AL |
| Transport | **Streamable HTTP**, JSON-RPC 2.0, single `/mcp` endpoint |
| MCP protocol version | **2025-11-25** (stable) |
| Response mode (v1) | **Stateless JSON** — return `application/json` per request; **no SSE, no session id** in v1 |
| AL runtime | **Persistent listener** (Server mode, always up) |
| Target VDI | **VDI 10** (Java 17) |
| Tool catalog | **Configurable multi-tool list** with an AL-side discriminator |
| Endpoint auth (v1) | **Bearer token**, with TLS + **optional mTLS** as config options |
| JSON library | **JSON4J** (`com.ibm.json.java.JSONObject`/`JSONArray`), platform-provided, compile-only |

---

## 1. Phase 0 verification — DONE

Findings from inspecting the local VDI 10 install (`docs/api`, `jars/`, `etc/global.properties`):

1. **Server-mode contract on `HTTPServerConnector`** (`docs/api/com/ibm/di/connector/HTTPServerConnector.html`):
   - `getNextClient()` blocks until a client connects; returns a per-connection `ConnectorInterface`. This *is* the concurrency unit — one inbound connection drives one AL cycle on that per-connection instance.
   - `getNextEntry()` parses the inbound HTTP request into an `Entry` (used in Iterator-feed mode).
   - `replyEntry(Entry)` writes the HTTP response back to that same client; `putEntry(Entry)` adds chunking support.
   - `isConnectionClosed()`, `isTerminating()`, `terminate()`/`terminateServer()` handle lifecycle/shutdown.
   - The connector already exposes `ATTR_NAME_HTTP_BODY`, `ATTR_NAME_HTTP_CONTENT_TYPE`, `ATTR_NAME_HTTP_AUTH_ENTRY`, etc. as Entry attributes.
   - **Decision:** subclass `HTTPServerConnector` rather than write a raw socket listener or wrapper. Let it handle socket/TLS/chunking/HTTP framing; our subclass reads `http.body` as a JSON-RPC envelope and writes the MCP response into the reply Entry's body before calling `replyEntry`.

2. **JSON library — resolved, not Jackson.** Originally assumed Jackson 1.9.13 (`org.codehaus.jackson`) would be needed. Verified present at `osgi/plugins/jackson-core-asl.jar` + `jackson-mapper-asl.jar` (both 1.9.13, full tree model available) — but these live in an **OSGi bundle directory**, and `etc/global.properties` references a distinct `com.ibm.di.loader.userjars` classloader for connector jars, making visibility from `jars/connectors` uncertain without a runtime probe.
   - **Better alternative found and adopted:** `jars/3rdparty/others/JSON4J.jar` (`com.ibm.json.java.JSONObject`/`JSONArray`) sits on the same flat, non-OSGi classpath tier as the platform's other third-party jars (log4j, axis, etc.) and as `HTTPServerConnector.jar` itself. VDI's own built-in JSON Parser component (`jars/parsers/JSONParser.jar`, `com.ibm.di.parser.JSONParser`) confirms platform-jar-based JSON handling is the native pattern here.
   - JSON4J's API (simple `Map`/`List`-like `JSONObject`/`JSONArray`, `get`/`put`, `toString()` / parse-from-string) is sufficient: we hand-map fixed JSON-RPC envelope shapes (§3), so we don't need Jackson's annotation-driven databind.
   - **Decision:** use JSON4J exclusively. No Jackson dependency, no shading/bundling question, no classloader risk. `JSON4J.jar` is **compile-only** (provided at runtime by the platform), exactly like `HTTPServerConnector.jar`, `miserver.jar`, `miconfig.jar`.

3. **Compile-only platform jars identified:**
   - `jars/connectors/HTTPServerConnector.jar` (base class)
   - `jars/common/miserver.jar`, `jars/common/miconfig.jar` (Connector/Entry/AL runtime API)
   - `jars/3rdparty/others/JSON4J.jar` (JSON)

None of these are repackaged into our connector jar; all are `provided`/`system` scope in the build, present on the target VDI's runtime classpath by definition.

---

## 2. MCP protocol surface (2025-11-25)

Single MCP endpoint (configurable path, default `/mcp`) supporting POST and GET.

**Methods to implement (v1):**
- `initialize` — capability negotiation; advertise `tools` capability; echo negotiated `protocolVersion` (`2025-11-25`).
- `notifications/initialized` — accept; return **202 Accepted**, no body.
- `tools/list` — return the configured tool catalog (see §3.1).
- `tools/call` — map to work Entry, run AL, return reply (see §3.2–3.3).

**Transport rules (MUST):**
- **POST** carries one JSON-RPC request/notification/response. For a **request**, return `Content-Type: application/json` with one JSON object (v1 stateless mode — do **not** open SSE). For a **notification/response**, return **202 Accepted** with no body.
- Validate the **`Origin`** header on every connection; respond **403 Forbidden** if present and invalid (DNS-rebinding protection).
- Honor the **`MCP-Protocol-Version`** header on post-initialize requests; respond **400** if invalid/unsupported. If absent, assume `2025-03-26` per spec.
- **GET** to the endpoint: v1 does not offer a server→client SSE stream — return **405 Method Not Allowed**.
- When binding locally, bind to `127.0.0.1`; require auth on all connections (§6).

**Deferred to later phase:** `MCP-Session-Id` sessions, SSE streaming, resumability, `notifications/tools/list_changed`, resources, prompts, server-initiated requests.

---

## 3. The three contracts (the real design surface)

### 3.1 Tool catalog → `tools/list`
Connector config parameter holds a JSON array of tool definitions:

```json
[
  { "name": "lookup_user", "description": "...", "inputSchema": { "type": "object", "properties": { ... }, "required": [...] } }
]
```

`tools/list` returns this verbatim (validated). One AL can serve several tools; it branches on the tool name.

### 3.2 Request mapping — `tools/call` → work Entry
Build the inbound Entry from the call:
- Flatten top-level scalar `arguments` to Entry attributes.
- Put the **raw arguments JSON** in a reserved attribute `$mcp.arguments` so the AL can parse nested structure with JSON4J.
- Metadata attributes: `$mcp.tool` (tool name, the discriminator), `$mcp.requestId` (JSON-RPC id), `$mcp.protocolVersion`.

### 3.3 Response contract — reply Entry → `tools/call` result
AL sets reserved attributes on the outgoing Entry; the connector maps them to the MCP result:
- `$mcp.result` → text content block.
- `$mcp.structured` → JSON used for `structuredContent`.
- `$mcp.isError` → `true` marks the tool result as an error (MCP tool-level error, not a JSON-RPC error).
- Absence of all three → return a generic success with the Entry serialized as text.

Map transport/dispatch failures (bad JSON, unknown method, auth failure) to **JSON-RPC error responses**; map AL business failures to **tool results with `isError: true`** with an actionable message.

---

## 4. Connector configuration (Config Editor form)

Parameters (use the `tdi.xml` form, password syntax for secrets, progressive disclosure per `<param>_changed()` convention):

- **General:** `endpointPath` (default `/mcp`), `bindAddress`, `port`, `toolCatalog` (JSON).
- **Security:** `authMode` (none | bearer | mtls), `bearerToken` (password), `allowedOrigins` (list), keystore/truststore paths + passwords (password), `requireClientCert` (mTLS).
- **Advanced:** request size limit, request timeout, max concurrent requests, verbose logging toggle.

Include a form even though some fields are advanced, so the Config Editor reports no missing form.

---

## 5. Packaging (`tdi.xml`)

- `tdi.xml` at jar root, `Folder name="Connectors"` with `<parameter name="connectorType">fully.qualified.McpServerConnector</parameter>` and supported modes including `Server`.
- Matching `Folder name="Forms"` form definition for the params in §4.
- Deploy jar to `VDI_install_dir/jars/connectors`.

---

## 6. Security (v1)

- **Bearer:** require `Authorization: Bearer <token>`; constant-time compare; 401 on mismatch.
- **TLS:** server keystore via config; never log secrets.
- **mTLS (optional):** truststore + `requireClientCert`; reject unknown client certs.
- Mask any secret-bearing config in logs/errors. Validate `Origin` (§2). Bind localhost unless explicitly opened.

---

## 7. Dependency / classpath strategy (revised — JSON4J, not Jackson)

- **JSON library: JSON4J** (`com.ibm.json.java.JSONObject`/`JSONArray`), shipped at `jars/3rdparty/others/JSON4J.jar`. Flat classpath tier, same as `HTTPServerConnector.jar` — no OSGi visibility question. **Compile-only / provided**, not repackaged.
- **Do not use Jackson** (neither the bundled 1.9.13 ASL jars in `osgi/plugins`, nor any 2.x jar). The OSGi jars are reserved for OSGi-loaded platform features (e.g. ActiveMQ-backed JMS/REST); relying on them from a `jars/connectors` component is an unverified assumption we don't need to make.
- Keep all platform runtime jars (`jars/common/miserver.jar`, `jars/common/miconfig.jar`, `jars/connectors/HTTPServerConnector.jar`, `jars/3rdparty/others/JSON4J.jar`) **compile-only**; never repackage them into the connector jar.
- No new third-party runtime dependencies introduced by this connector at all — everything it needs is already on the target VDI's classpath.

---

## 8. Phased plan

- **Phase 0 — Verify & scaffold.** ✅ Verifications done (§1). Scaffolding (this commit): Maven project, `tdi.xml` skeleton, package skeleton, compiled against VDI jars.
- **Phase 1 — Echo path.** ✅ Code complete: `initialize` / `notifications/initialized` / `tools/list` answered directly by the connector; `tools/call` mapped to a work Entry; `replyEntry()` translates the AL's reply back into an MCP tool result. Statically verified: jar deployed to `jars/connectors/`, loaded and instantiated via reflection against the real VDI 10 runtime classpath (all jars under `jars/`) — `getVersion()`, `getModes()` (`[Server, Iterator]`), and a JSON4J round-trip all succeeded with no `NoClassDefFoundError`/`ClassNotFoundException`. This confirms the §7 JSON4J classpath decision is sound in practice, not just on paper.
  - **Not yet done:** a live MCP Inspector round-trip. That needs a running server (`ibmdisrv`) and a configured AssemblyLine wired to this connector as its feed — both require either the GUI Config Editor or hand-built solution XML, neither of which this verification pass touched. Left as the first task of Phase 4 (or earlier, on request).
- **Phase 2 — Contracts.** Implement `toolCatalog` param, full request mapping (§3.2), and response contract (§3.3); AL branches on `$mcp.tool`.
- **Phase 3 — Security & robustness.** Bearer/TLS/mTLS, Origin + protocol-version enforcement, concurrency hardening, JSON-RPC vs tool-error mapping, request limits/timeouts.
- **Phase 4 — Package & validate.** `tdi.xml` at root, form polish, deploy to `jars/connectors`, confirm it appears in the Config Editor, end-to-end run with Claude as the MCP client.

---

## 9. Acceptance / validation

- `GET` health-style probe and MCP Inspector `initialize` succeed.
- `tools/list` returns the configured catalog.
- A `tools/call` round-trips: arguments → Entry → AL logic → reply Entry → tool result (text + `structuredContent`).
- Invalid `Origin` → 403; bad/missing `MCP-Protocol-Version` → 400; missing/invalid bearer → 401; client-supplied `notification` → 202.
- Connector loads cleanly with **no classpath errors** in the VDI logs.
- Component appears under the expected system namespace in the Config Editor.

---

## 10. Open items to settle during build

- Whether one generic AL handles all tools via `$mcp.tool` (current lean: yes, for v1 — simpler packaging, matches "configurable multi-tool list" decision), or each tool maps to a distinct AL/flow (documented future option).
- Whether to expose a minimal `GET` health endpoint distinct from the MCP GET (which returns 405).
- Token/keystore secret sourcing — config param now; Vault integration is a natural follow-on given existing patterns.
