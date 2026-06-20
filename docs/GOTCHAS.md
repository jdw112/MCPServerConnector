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

Our `tdi.xml` sets `serverReply=true`. The entry handed to `replyEntry()` carries the work attributes (`$mcp.result` etc.) intact — the default output mapping passes them through, so **no manual Output Map is needed**.

**Symptom if broken:** `initialize`/`tools/list` work but `tools/call` hangs the client with no response. Curl-from-a-script can mask this if you only inspect the AL log instead of the curl response body.

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
