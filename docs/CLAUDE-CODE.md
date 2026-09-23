# Claude Code

Léo can run the official Claude Code CLI alongside Codex. In **Connections**, choose
**Connect Claude Code**, open Anthropic’s sign-in page, and paste the authorization
code it provides. The page follows sign-in automatically, survives browser reloads,
and offers cancellation and retry. Android has the same flow in **Connexions**.
Passwords are entered only on Anthropic’s page. The sign-in link expires after
15 minutes; a server restart ends an unfinished sign-in.

Choose **Claude Code** in an agent’s **Coding agent** setting. Existing agents
remain on Codex. Model and effort choices come from the official CLI’s control
protocol, without a model inference request. A saved catalog remains available
while the account is executing. When discovery fails, the UI marks the catalog
unavailable rather than inventing current model capabilities.

Claude agents support scheduled tasks, chats, streaming text and tool results,
image/file attachments, queued follow-ups, steering, native questions, cancellation,
and resumption. Changing a model applies to the next queued turn. Changing an
agent’s provider requires a new conversation; Claude and Codex session IDs cannot
be mixed. Completion receipts prevent a finished request from being repeated after
a worker restart. Interrupted requests tell the agent to inspect completed work
and verify external effects before continuing.

## Installation and authentication

The manager and guest images include unmodified Claude Code **2.1.280**.
For development, install that CLI and set `CLAUDE_BIN` if it is outside PATH.
The CLI owns the login flow and private state in `DATA_DIR/claude`. Léo never
implements Anthropic OAuth or exposes access/refresh tokens through its API.
Identity responses are restricted to connected state, email, subscription type,
and authentication method. Unlike managed Codex accounts, Claude’s files use the
CLI’s own file storage, protected by the private data directory and file permissions.
Include that directory in encrypted server backups.

One Claude account is connected per owner workspace. Claude executions are
serialized so only one CLI execution owns refreshing credentials at a time;
Codex executions can continue independently. The host provisions the selected
CLI-owned authentication files into the private guest, then receives rotated state
through the private VM control connection after execution. These files never enter
activity logs or deliverable exports. Conversation history remains on the retained
run disk. An interrupted synchronization blocks other Claude runs until the original
run resumes and synchronizes, or the owner reconnects. It never silently falls back
to an API key or a different provider.

Léo clears inherited Anthropic API-key, bearer-token, cloud-provider, and alternate
OAuth environment overrides from subscription executions. Sign-in and logout are
performed with `claude auth login --claudeai` and `claude auth logout`.

## Permissions

YOLO remains the default and runs within the existing private microVM boundary.
Workspace-write and read-only agents enable Claude’s Bash sandbox with
`failIfUnavailable` and no unsandboxed fallback. The images include Bubblewrap and
socat. Read-only also denies edit tools and retains read-only project mounts.
Only the agent’s configured MCP connections are supplied; HTTP tools keep Léo’s
server-enforced grants, and excluded command-MCP tools are denied explicitly.
Shared/project Claude settings are disabled for the managed invocation. Selected
Léo skills and instructions are supplied through the existing run context.

## Subscription usage

This is programmatic Claude Code usage. Anthropic’s June 15 update currently says
that the proposed separate SDK billing change is paused and subscription-backed
SDK/CLI usage still draws from subscription limits. Léo does not promise permanent
pricing or add paid fallback. Consult [Anthropic’s current billing guidance](https://support.claude.com/en/articles/15036540-use-the-claude-agent-sdk-with-your-claude-plan).

Hosting follows the [Claude Code hosting and credential rules](https://code.claude.com/docs/en/legal-and-compliance): the binary stays unmodified and sign-in completes through Anthropic’s own flow using the owner’s account. The implementation uses the [official streaming CLI](https://code.claude.com/docs/en/headless) and its SDK control protocol.

## Validation

`backend/tests/claude.rs` exercises the streaming adapter and sign-in lifecycle with
a synthetic executable. `tests/e2e/claude.spec.ts` exercises the native backend from
Chromium and WebKit, including mobile layouts and resumed conversations.
`ClaudeJourneyTest` covers Android’s code-entry flow and provider serialization.
These fixtures never contact Anthropic or spend subscription allowance.
The real installed 2.1.280 CLI was also checked with a non-inference initialization
request, verifying its current model names and effort capabilities.
