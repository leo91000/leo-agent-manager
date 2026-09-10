# Agent and project chats

Open **Chats**, or **Start chat** on an agent or project. Project chats select Main agent by default. Choose another agent before the first message; its project, skill, MCP, GitHub and sandbox restrictions still apply. A chat owns one persistent conversation and workspace. It does not create a scheduled task.

Send starts a reply. During a reply, **Queue** saves a follow-up for the next turn, while **Steer now** sends instructions into the active turn. Queued messages can be edited or removed until dispatch begins. Pause queue holds follow-ups without stopping the current reply. Stop response stops execution and pauses the queue; Resume continues the saved conversation. On desktop, Enter sends or queues, Shift+Enter adds a line, and Alt+Enter steers. Touch keyboards use Enter for a line break.

The composer options contain an optional model override for the next message. A model change needs a new turn; it cannot change an already running turn. The agent's configured model remains unchanged. Drafts stay in the current browser tab across reloads. Submitted messages, queue state, transcripts and working files persist on the server.

## Execution and recovery

Tasks continue to use `codex exec`. Chats use Codex app-server `thread/start`, `thread/resume`, `turn/start` and `turn/steer`, tested against Codex 0.154.0. Steering includes the active `expectedTurnId`. If that turn has already finished, the message remains pending for the next turn.

`Chats` owns durable user messages and dispatch. `Worker` owns account selection, project locks, timeout budgets, restart fencing and execution. `chat-process.ts` adapts app-server notifications to the shared Activity artifact format. Each active reply has a supervised process; idle chats hold no account or worker slot. Restricted agents run the same adapter inside the isolated container, with a read-only inbox containing only that run's steering messages.

Messages use client-generated IDs. Requests can be retried without duplicating submissions. On recovery, Codex's persisted user-message client IDs reconcile accepted messages, using paginated turn history when required. A completed saved reply is recovered without another model turn. Interrupted replies continue with the existing workspace and conversation. Failures pause follow-ups for review. Usage exhaustion follows the existing account failover path.

SQLite schema version 4 adds indexed chat messages; records and task runs are preserved. Chat events are excluded from the normal 30-day task-event pruning and event-count cap. Workspace cleanup disables further messages in that conversation.

Validation includes authenticated API boundaries, restricted project access, live steering, queue editing and deduplication, model overrides, account-backed isolated execution, restart and lost-completion recovery, process disconnection, and Chromium/WebKit journeys with desktop/mobile light/dark screenshots.
