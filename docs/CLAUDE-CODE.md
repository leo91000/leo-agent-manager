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
and resumption. Changing a model applies to the next queued turn. The chat composer
also switches between Codex and Claude Code without creating another visible chat.
The server waits for the current turn to finish, starts a fresh native session,
and transfers visible conversation context while retaining the same workspace.
Switching back also starts a fresh session so the intervening work is included.
Native session IDs, pending tool calls and hidden reasoning are not transferred.

Resuming a saved Claude session sends a fresh protocol message ID while keeping
the original Léo message and completion receipt. Claude ignores IDs already in
its transcript, so replaying the original ID would acknowledge the request without
continuing it. Completed receipts are replayed without starting another model turn.

Private question answers and raw tool outputs are excluded from the transfer.
For long chats, the transfer includes up to 200 recent transcript entries and
100,000 characters, plus an excerpt of the initial request when earlier history
is omitted. The full visible history stays available in Léo. Previous attachments
remain in the retained input directory. Model and effort defaults are reset when
changing providers; the agent's global configuration is unchanged.

Claude's turn results can cover several user messages, or background notifications
unrelated to the current prompt. Léo correlates completion with consumed message
IDs and keeps the process alive while non-ambient background tasks and their
follow-up responses are pending. A background build or CI wait therefore remains
part of the running conversation. Ambient watchers do not keep a run open.
Resume ignores legacy completion receipts that never acknowledged the request,
preventing an empty result from repeatedly restarting the same pending message.

An explicit provider choice is saved with each queued message. Steering cannot
change the running turn's provider. Older clients that omit the provider continue
with the chat's current provider. Completion receipts prevent finished requests
from being repeated after a worker restart. Interrupted requests preserve completed
work and verify external effects before continuing.

The design review used [T3 Code](https://github.com/pingdotgg/t3code/tree/f5ef0ddb90a8c36584e181b1913e7b8a5df30ffc),
under its MIT license. Its provider reactor separates visible threads from native
sessions, but explicitly rejects switching drivers on an existing thread. Léo's
context transfer is an independent implementation, not a port of a T3 Code feature.
No T3 Code runtime dependency or source code is included.

## Installation and authentication

The manager and guest images include unmodified Claude Code **2.1.280**.
For development, install that CLI and set `CLAUDE_BIN` if it is outside PATH.
The CLI owns the interactive login flow and private state in `DATA_DIR/claude`.
Léo never exposes access/refresh tokens through its public API.
Identity responses are restricted to connected state, email, subscription type,
and authentication method. Unlike managed Codex accounts, Claude’s files use the
CLI’s own file storage, protected by the private data directory and file permissions.
Include that directory in encrypted server backups.

One Claude account is connected per owner workspace. **Connections → Claude Code →
Simultaneous Claude conversations** sets its execution limit from 1 to 32, default 4,
on web and Android. The server's global `CONCURRENCY` capacity still applies across
providers. Changes are persisted immediately. Lowering the limit lets active runs
finish and queues subsequent work; it does not cancel conversations.

Only the manager holds refresh credentials. It serializes token rotation under the
account gate, persists rotated state before replying, and distributes access-only
credential snapshots through private, run-scoped Unix/vsock connections. It uses the
OAuth refresh endpoint and client ID of the pinned official CLI (or the client ID
saved with the login). Guests refresh their snapshot every 30 seconds; the unmodified
CLI detects the changed credential file before subsequent requests. Guests cannot
write back to the shared login, so a late-finishing run cannot overwrite new tokens.
A temporary broker outage keeps the conversation alive while its token remains
valid; an expired login produces an explicit recoverable authentication error.

Legacy runs that still own a `sync-required` marker drain through the original
serialized credential handoff before parallel execution starts. Their history and
workspace remain intact. Reconnect/disconnect continue to wait for active runs.
Credentials never enter activity logs or deliverable exports. There is no API-key
fallback and no change to the selected provider or subscription billing.

Léo clears inherited Anthropic API-key, bearer-token, cloud-provider, and alternate
OAuth environment overrides from subscription executions. Sign-in and logout are
performed with `claude auth login --claudeai` and `claude auth logout`.

## Usage limits

Connections shows the remaining percentage and reset date for the five-hour,
weekly, and model-specific windows returned by Claude Code, on web and Android.
Léo reads the CLI's `get_usage` control response (`skip_behaviors: true`) without
sending a prompt. The pinned CLI 2.1.280 defines utilization as a percentage
(0–100) and reset dates as ISO 8601 timestamps. This experimental protocol may
change; unsupported versions and unavailable quotas produce an unavailable state,
never an invented zero or full allowance. Only allowlisted quota fields are exposed.

Reads are cached for five minutes, including failures. While a run, sign-in, or
credential recovery owns the account, Connections shows the last known values
as stale and waits to refresh. Reconnecting or disconnecting clears the cache.
Quota errors leave account connection status unchanged. No OAuth tokens are
read by the usage integration, and no model request is made to check the limits.

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
