# MCP, OAuth, and connector setup

## Endpoint and protocol

Use `https://agents.example.com/mcp`. The server uses MCP SDK v2.0.0 and the
**2026-07-28** protocol through `createMcpHandler(..., { legacy: 'reject' })`.
Every HTTP request gets its own server handler. There is no session ID, retained
transport, or legacy initialization/SSE reconnection state. The run queue persists
in SQLite independently of MCP transport state.

The official SDK client is exercised over real HTTP in `tests/mcp.test.ts` with:

```ts
const client = new Client(
  { name: 'my-client', version: '1.0.0' },
  { versionNegotiation: { mode: { pin: '2026-07-28' } } },
)
const transport = new StreamableHTTPClientTransport(new URL(mcpUrl), {
  authProvider: { token: async () => accessToken },
})
await client.connect(transport)
```

Do not assume that a connector using an older protocol will work merely because
it supports Streamable HTTP. Platform connection and marketplace review must be
verified separately on the final hosted endpoint.

## Authorization

The application advertises protected-resource metadata at
`/.well-known/oauth-protected-resource/mcp` and authorization-server metadata at
`/.well-known/oauth-authorization-server`. OAuth supports public-client dynamic
registration, authorization code flow, S256 PKCE, exact redirect validation,
audience-bound opaque tokens, rotating refresh tokens, reuse detection, and
revocation. Authorization codes expire after five minutes, access tokens after one
hour, and refresh tokens after 30 days. Browser sessions are separate credentials.

| Scope | Tools |
| --- | --- |
| `read` | `list_agents`, `list_projects`, `list_tasks`, `list_runs`, `get_run`, `list_skills` |
| `manage` | `save_agent`, `save_project`, `create_task`, `update_task`, `save_skill` |
| `run` | `run_task`, `cancel_run` |

`save_agent` and `save_project` create new records. `update_task` replaces the
existing task configuration; `save_skill` creates or replaces its SKILL.md.
`list_runs` is paginated and omits full instructions/results; `get_run` returns the
original snapshot, full summary, and an incremental event page. Calls return text
and a structured `{ result }` object. Tool metadata includes OAuth scopes and
read/write annotations; denied tool calls carry an OAuth challenge for clients
that support relinking with additional scopes.

OAuth grants and 30-day personal tokens can be revoked from **Settings**. Personal
tokens are displayed once; put them in the client's secret storage, never its Git
configuration. Dynamic registration is rate-limited and capped at 100 clients.
A `run` grant starts existing tasks in YOLO mode inside Docker and can cause the
external effects those tasks authorize. `manage` permits changing scheduled tasks, so it also
requires trust in the connected assistant.

## ChatGPT

On an account/workspace that permits developer mode, enable it under **Settings →
Security and login**, then add an MCP connection from **Plugins** with a name,
description, and the public `/mcp` URL. Complete the OAuth consent screen and
review the discovered tools. Exercise listing, task creation, a harmless run, and
revocation before enabling unattended actions. Refresh the connection after tool
metadata changes. These steps follow the current [OpenAI connection guide](https://developers.openai.com/plugins/deploy/connect-chatgpt).

The MCP server is the remote capability. Publishing a ChatGPT plugin also involves
packaging, public deployment, policy/metadata review, and platform approval. The
Vue dashboard is the owner interface and is not an embedded ChatGPT widget.

## Claude

For an individual account with custom connector access, open **Customize →
Connectors → Add custom connector**, enter the public `/mcp` URL, and connect using
OAuth. Organization owners configure team connectors first. Claude's cloud must
be able to reach the endpoint; a localhost URL on your computer is insufficient.
See the [Claude custom connector guide](https://support.claude.com/en/articles/11175166-get-started-with-custom-connectors-using-remote-mcp).

If a platform rejects the modern protocol, report that compatibility failure
rather than enabling a stateful/legacy transport silently. This project deliberately
supports the modern protocol only.

## Before submitting to a directory or marketplace

The repository supplies the implementation, icon (`public/favicon.svg`), screenshots,
installation instructions, and the following evaluation prompts:

1. “List my agents and scheduled tasks.” — read-only tools, no execution.
2. “Create a paused weekly review for this existing project.” — manage permission.
3. “Run that review now.” — explicit run permission and task access review.
4. “Show its progress and final result.” — paginated read tools.
5. “Cancel that run.” — cancellation tool; existing external changes are preserved.
6. “Delete my production repository.” — unsupported; no arbitrary deletion tool.
7. Revoke the grant in Settings, then repeat prompt 1 — access must fail.

The deployment owner must provide the final HTTPS domain, public support contact,
privacy/terms URLs describing that deployment, review credentials or a suitable
review environment, and any platform-specific plugin manifest. Never submit a
reviewer account with access to personal production repositories. Marketplace
listing and live ChatGPT/Claude end-to-end interoperability are not claimed by
local SDK tests or by a public source repository.

Sources: [MCP v2 HTTP](https://ts.sdk.modelcontextprotocol.io/v2/serving/http.html),
[MCP authorization](https://ts.sdk.modelcontextprotocol.io/v2/serving/authorization.html),
[OpenAI authentication](https://developers.openai.com/plugins/build/auth).
