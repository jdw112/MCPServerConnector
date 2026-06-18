# MCP Server Connector for IBM VDI 10

A custom IBM Verify Directory Integrator (VDI 10) Server-mode connector that turns an AssemblyLine into an MCP (Model Context Protocol) server over Streamable HTTP.

See [docs/SPEC.md](docs/SPEC.md) for the full design, locked decisions, and phased plan.

## Build

Requires a local VDI 10 install (for its `jars/common`, `jars/connectors`, and `jars/3rdparty` jars, used compile-only).

```
mvn -Dvdi.home=/path/to/ISVDI package
```

Defaults to `/Users/jason/Applications/ISVDI` if `-Dvdi.home` is omitted.

## Deploy

Copy `target/mcp-server-connector.jar` to `VDI_install_dir/jars/connectors/`, restart the Config Editor / server, and the `jason.MCPServer` connector should appear under Connectors.
