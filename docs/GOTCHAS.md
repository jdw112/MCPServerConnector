# Platform gotchas & non-obvious findings

A maintainer's reference to the IBM VDI 10 platform behaviors that shaped this connector — each one cost real debugging to find. If you're extending or porting this connector, read this first. Full narrative is in [SPEC.md](SPEC.md) §1b–§1d; this is the condensed index.

## 1. `getNextClient()` hardcodes the base class — you must override it

`HTTPServerConnector.getNextClient()` constructs `new HTTPServerConnector()` (literally, confirmed by `javap -c` — not `this.getClass().newInstance()`) for the per-connection object that actually runs `getNextEntry()`/`replyEntry()`. So a plain subclass's overrides **never run for real traffic** — the framework processes every connection on a base-class instance.

**What we do:** override `getNextClient()` to replicate the base accept/setup sequence but construct `new McpServerConnector()`. Everything in that sequence uses public inherited methods except grabbing the listening `mServerSocket` (private, no accessor), which needs one reflective field read. See `McpServerConnector.getNextClient()`.

**Symptom if broken:** your overrides silently don't execute; the AL sees the raw HTTP entry (`http.*`/`tcp.*`), none of your log lines fire.

## 2. `http.body` is a `byte[]`, not a String — use `http.bodyAsString`

`Entry.getString("http.body")` returns `Object.toString()` of a `byte[]` (e.g. `[B@65166f15`), not the request body. The HTTP parser exposes a decoded String copy under **`http.bodyAsString`**.

**Symptom if broken:** JSON parse error like `Expecting '{' ... obtained token: '['` (the `[` is from `[B@...`).

## 3. `serverReply=true` is required for the reply-at-end-of-cycle path

A Server-mode connector has two reply mechanisms:
- **Direct reply** inside `getNextEntry()` (call `super.replyEntry()`, return `null`) — always works. We use it for `initialize`/`tools/list`/errors.
- **End-of-cycle reply**: when `getNextEntry()` returns a work Entry, the AL only calls `replyEntry()` afterward if the connector config's `getReplyRequired()` is true — which reads the **`serverReply`** boolean param (default `false`, confirmed via `ConnectorConfigImpl` disassembly). We use this for `tools/call`.

Our `tdi.xml` sets `serverReply=true`, so an AL author never sets it (and can't — it isn't on the CE form). It won't appear in the solution XML either: the CE omits params that equal the inherited default, so `grep serverReply` on a saved config returns nothing even though it's in force. Absence = "matches default," not "off."

**Correction (cost hours to find):** the entry handed to `replyEntry()` is the connection's **`conn`** object, and it is **empty** unless the connection's **Output attribute map** populates it from `work`. There is no automatic passthrough. You must map `$mcp.result`, `$mcp.structured`, and `$mcp.isError` from `work` → `conn` in the MCPServerConnection's Output map — see §9.

**Symptom if broken:** `tools/call` returns `200` with `content:"Tool completed but returned no result."` and no `structuredContent`, even though the AL log shows `$mcp.result` set on `work`. (If `serverReply` were genuinely false, `tools/call` would instead hang with no response — but that's unreachable from the CE form.)

## 4. `PASSWORD`-syntax params auto-decrypt at runtime

A form field with `syntax=PASSWORD` is stored `encrypted="true"` in the config XML (a ~340-char ciphertext blob). At runtime the engine decrypts it transparently — `getParam("bearerToken")` returns **plaintext**. No `SecurityCrypto`/`CryptoFactory` call needed.

**Operational note (not a code bug):** entering `name=value` (e.g. `bearerToken=test123`) into a password field stores the whole 19-char string as the secret. The tell is the configured value being longer than what you typed. TDI password fields can also *append* rather than replace on edit — clear to empty first.

## 5. The listener binds `0.0.0.0` — `bindAddress` is enforced post-accept

`HTTPServerConnector` creates its `ServerSocket` with `createServerSocket(port, backlog)` — bound to all interfaces, no per-address option. We enforce `bindAddress` by checking `socket.getLocalAddress()` after `accept()` and rejecting (close + `RetryEntryException` to keep listening) connections that didn't arrive on the configured address. It's a guard, not a true bind — pair with firewall rules for hard isolation.

## 6. mTLS uses the *server's* keystore, not connector params

`useSSL`/`needClientAuth` drive TLS via the inherited SSL layer, whose socket factory (`getRSInterface().getServerSocketFactory(true)`) sources keystore + truststore from the **SDI server's** global config — there are deliberately no per-connector keystore fields. `setNeedClientAuth(true)` is applied to the listening `SSLServerSocket`, so our `getNextClient()` accept doesn't bypass client-cert verification. A bad/missing client cert fails at the TLS handshake, before any connector code runs (so no JSON error body — just a handshake failure).

## 8. The Config Editor is Eclipse/SWT — form scripts must use SWT, not AWT/Swing

The CE (`ce/eclipsece`, an Eclipse RCP app — `org.eclipse.swt.cocoa.macosx` on macOS) runs **SWT**, not Swing/AWT. This bites form-script (`<parameter name="formscript">`) code two ways:
- A raw AWT modal dialog (`javax.swing.JOptionPane.showInputDialog` with no parent) **freezes the CE on macOS** — AWT modals don't mix with the SWT/Cocoa main thread. Use `form.alert(...)` (the CE's own parented SWT dialog) for popups.
- The AWT clipboard (`java.awt.Toolkit.getSystemClipboard()`) doesn't reliably reach the OS pasteboard from SWT. Use the **SWT clipboard**: `new org.eclipse.swt.dnd.Clipboard(Display.getCurrent())` + `setContents([text], [TextTransfer.getInstance()])`, then `dispose()`. Non-modal, so it can't hang the CE.

The available form-script API (the `form` object) includes `getConfigValue`/`setConfig`/`updateControl`, `alert`, `translate`, `chooseFromList`, `setWaitCursor`/`setNormalCursor`. Java is reachable via `Packages.<fqcn>`; `new Packages...()` constructors work. See the "Generate Token" button (`generateBearerToken` in `tdi.xml`'s formscript) for a worked example.

## 7. Real MCP clients send an `Origin` header

Browsers *and* Claude's MCP client send `Origin`. If `allowedOrigins` is non-empty and doesn't include the client's origin, the connection gets `403`. Leave `allowedOrigins` empty for local testing unless you know the exact value (check the AL log for the parsed `Origin` header). curl/MCP Inspector typically send no `Origin`, so they're unaffected — which can mask the issue until a real client connects.

---

# Building an AssemblyLine with this connector

The items above are connector-internals for maintainers. These are **AL-authoring** gotchas — hit while building the read-only "Identity Service Desk" AL (see [USE-CASES.md](USE-CASES.md) §3) against OpenLDAP, all in the Config Editor. Each cost real debugging.

## 9. The reply entry is `conn`, built by the connection's Output map — populate it

The Data Flow only ever writes `work`. The connector replies from **`conn`** (§3). Nothing bridges the two automatically. On the **MCPServerConnection**'s **Output** attribute map, add items copying the reply attributes from `work`:

| conn attribute | Advanced mapping |
|---|---|
| `$mcp.result` | `ret.value = work.getString("$mcp.result");` |
| `$mcp.structured` | `ret.value = work.getString("$mcp.structured");` |
| `$mcp.isError` | `ret.value = work.getString("$mcp.isError");` |

**Symptom if broken:** every `tools/call` → `200` "Tool completed but returned no result", no `structuredContent`. The AL log's dump shows `The 'conn' object` empty and `The 'work' object` holding your values.

## 10. Branch routing: empty condition matches everything; ELSE-IF order matters; names are case-sensitive

- A branch with an **empty condition** is always-true. As an **ELSE-IF**, it **swallows every branch below it** — those are never evaluated. Give each tool branch an explicit `$mcp.tool equals <toolName>`.
- Connector-flattened argument attributes are the **exact JSON key**, case-sensitive: a `userId` property is `$userId`, never `$userid`. A wrong-case Link Criteria value silently matches nothing.

**Symptom if broken:** a tool runs the *wrong* branch's logic (e.g. `list_group_members` executing the groups Iterator), or a Lookup matches nothing because `$userid` resolved empty.

## 11. Tool Catalog (JSON) field: invalid JSON is silently treated as empty, and the field appends on edit

`tools/call` validates the tool name against the catalog **before** the AL; an empty/`[]` catalog makes *every* call "Unknown tool". The catalog textarea is a TDI field that **appends rather than replaces** on paste — pasting a second time yields `] [` (two arrays concatenated) which is invalid JSON → parsed as empty. Always **select-all → delete to empty → paste once**, and confirm `tools/list` returns your names.

**Symptom if broken:** `tools/list` returns `[]`; every `tools/call` → `isError` "Unknown tool: …".

## 12. `$attr` is *not* substituted in static connector params (e.g. `ldapSearchFilter`) — set it in a hook

TDI substitutes `$attr` in a connector parameter at **connector init from the work entry available then** — which for a feed/loop connector is effectively empty, so `$userId` reaches LDAP **literally** and matches nothing. **Link Criteria** *does* substitute (evaluated at lookup time), which is why the single-entry Lookups worked. For an **Iterator** whose filter needs a per-request value, set it in a **Before Initialize** hook:

```javascript
var uid = work.getString("userId");
thisConnector.getConfiguration().setParameter("ldapSearchFilter",
  "(&(objectClass=groupOfNames)(member=uid=" + uid + ",ou=People,dc=example,dc=com))");
```

**Symptom if broken:** AL log shows `search filter '(...member=uid=$userId,...)'` verbatim; `Loop Cycles:0`.

## 13. "List" tools: nothing auto-collects — accumulate per cycle, and scope the Input map

An Iterator hands you **one** entry per cycle. To return a list, append each cycle's value to a multi-valued work attribute yourself:

```javascript
work.addAttributeValue("groups", work.getString("cn"));   // NOT setAttribute — that overwrites
```

Restrict the Iterator's **Input map to only the attribute you accumulate** (e.g. `cn`), not `*`. Otherwise each cycle's other attributes (`objectClass`, `member`, the entry `$dn`) linger on `work` and the reply reflects the **last** group's — leaking unrelated data. Multi-valued work attrs serialize to JSON arrays automatically; a single value stays scalar (special-case if you need always-array).

## 14. LDAP Lookup with no match throws and fails the AL — add an On No Match hook

A Lookup that finds nothing raises an error that kills the cycle — the client gets a dropped connection (curl exit 52 / `HTTP 000`), not a clean answer. On the Lookup connector's **On No Match** hook, set a normal result:

```javascript
work.setAttribute("$mcp.result", "No match for '" + work.getString("userId") + "'.");
work.setAttribute("$mcp.structured", "{\"found\":false}");
```

## 15. IBM JScript (`ibmjs`) can't read `.length` on Java arrays or `StringBuilder`

The CE/engine script engine is IBM JScript, not Rhino/Nashorn. `someJavaArray.length` and `stringBuilder.length()` both throw `Java Bean property 'length' does not have a read method` — the interpreter resolves `.length` as a bean property before any call. Use `java.lang.reflect.Array.getLength(arr)` for array size and build strings with plain **JS string concatenation**, not `StringBuilder`. Ordinary method calls (`entry.getAttributeNames()`, `attr.size()`, `attr.getValue(i)`) are fine — only `length` collides.

**Symptom if broken:** `Script interpreter error, line=N: Java Bean property 'length' does not have a read method`.
