# MCP connections for agents

Open **MCPs → Add MCP** to connect an external server. This is separate from the
manager’s inbound `/mcp` endpoint described in [MCP.md](MCP.md).

## Remote servers

Choose **Remote HTTP server**, enter its final Streamable HTTP endpoint, and
select no authentication, a bearer token, or OAuth. Save, then use **Test** to
discover tools or **Connect** to sign in. OAuth returns to the manager in the
same browser, including on a phone; no terminal callback or laptop is needed.

Set `PUBLIC_URL` to the manager’s public HTTPS origin. Register the callback
shown under **OAuth settings** when your provider requires a pre-created client:

```text
https://your-manager.example/oauth/mcp/callback
```

Providers with dynamic client registration need no manual client ID. For other
providers, enter their registered client ID, optional client secret, and scopes.
Public clients and `client_secret_post` confidential clients are supported.
Providers requiring another client authentication method need an adapter or
additional implementation. Legacy HTTP+SSE endpoints are not supported.

OAuth uses PKCE, a ten-minute, single-use state bound to the signed-in browser
session, issuer validation, and refresh tokens when supplied by the provider.
Concurrent calls share serialized refresh handling. Reconnect starts fresh
consent; cancelling it leaves the connection requiring sign-in.

Private HTTP/DNS addresses require explicit **Allow private network endpoints**.
This permission also applies to OAuth discovery and token endpoints. Connections
pin the validated DNS address and reject redirects; instance metadata addresses
are always blocked. Use the final server URL rather than a redirecting URL.

## Command servers

Choose **Command in agent container**, an executable, and one argument per line.
Add credentials as environment variables, rather than command arguments.
Executables must be available in the agent image; the built-in toolkit includes
Node, pnpm, Python, uv, and mise. Package runners can install a server on demand.

During a task the command runs in that agent’s environment, including its
separate container when resource or sandbox restrictions apply. **Test** starts
the command in the manager’s home to discover tools. Only configure trusted
commands: command configuration is an owner-level execution capability.

## Agent access

The main agent receives all enabled connections. Other agents can inherit all
connections or select individual ones under **Agents → Access & execution**.
Existing restricted agents receive no new MCP access during migration.

**Browse tools** chooses the connection-wide tool allowlist; each agent can
narrow it further. “All tools” includes future tools. An explicit empty list
allows no tools. Effective access is the intersection of both selections.

Remote calls go through the manager gateway with a temporary run credential.
The gateway enforces tool access on every call, checks the run and current agent
policy, and rejects expired or revoked grants. Provider credentials stay in the
manager. Connection settings or credentials changed during a run invalidate its
remote grant; rerun the task to use the new configuration.

Command tool allowlists are passed to Codex. They control the tools exposed to
the model, but are not an operating-system security boundary for a YOLO agent
that can execute the same command itself. Use project restrictions and sandbox
modes when an OS boundary is required.

Resources, resource templates, and prompts on an allowed remote connection are
forwarded too; tool checkboxes do not restrict those capabilities. The gateway
supports ordinary request/response tool calls, not persistent subscriptions,
server-initiated sampling/elicitation, or resumable background MCP tasks. Each
operation opens an upstream session, with bounded requests and an 8 MB response
limit. Calls time out after 60 seconds.

## Credentials and backups

Tokens, refresh tokens, client secrets, and command environment values are
encrypted with AES-256-GCM in the manager database. The API exposes only saved
secret indicators and environment variable names. Leave secret fields blank to
preserve saved values; removing an environment row deletes that variable.

Back up **both the database and `DATA_DIR/mcp-encryption-key`**. The key is created
with mode `0600`; without it, encrypted connections cannot be restored. Keep the
backup private. The key and database live in the persistent data volume.

**Remove credentials** clears locally stored authentication and immediately
invalidates remote run access. Provider-side consent can be revoked separately
in the provider account. Already running command processes retain their
environment until their run ends. **Delete MCP** also removes agent selections.

## Validation

Integration tests use a local OAuth/MCP provider to exercise PKCE, client
registration, registered confidential clients, refresh rotation, session and
issuer checks, denial, replay, encrypted storage, gateway permissions, resource
and prompt forwarding, revocation, and command-server discovery. No live
third-party account is needed for the tests.

The browser journey covers connection creation, OAuth redirects, tool selection,
agent permissions, command credentials, and removal. Screenshots cover light
and dark appearances at 320, 390, and 1440 pixels. Container smoke tests verify
that isolated runs receive their MCP run token while manager credentials and
unselected resources remain inaccessible.
