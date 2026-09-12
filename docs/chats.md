# Agent and project chats

Agents can now publish [persistent deliverables](DELIVERABLES.md): screenshots, videos, audio, PDFs, Markdown, code and downloadable files. Open a card beside a response, or **Files** for the complete gallery. Versions, image comparison and a mobile fullscreen reader remain available after the VM stops.

Open **Chats**, or **Start chat** on an agent or project. Project chats select Main agent by default. Choose another agent before the first message; its project, skill, MCP, GitHub and sandbox restrictions still apply. A chat owns one persistent conversation and workspace. It does not create a scheduled task.

Send starts a reply. During a reply, **Queue** saves a follow-up for the next turn, while **Steer now** sends instructions into the active turn. Queued messages can be edited or removed until dispatch begins. Pause queue holds follow-ups without stopping the current reply. Stop response stops execution and pauses the queue; Resume continues the saved conversation. On desktop, Enter sends or queues, Shift+Enter adds a line, and Alt+Enter steers. Touch keyboards use Enter for a line break.

The composer options contain searchable model and reasoning selectors for the next message. They use Codex's live `model/list` catalog, including descriptions and each model's supported reasoning levels. Agent settings use the same selectors. Changing model resets reasoning to that model's default; **Agent default** follows the agent configuration. Saved models absent from the current catalog remain visible. Model and reasoning changes need a new turn; they cannot change an already running turn. Queued edits preserve both choices. Drafts stay in the current browser tab across reloads. Submitted messages, queue state, transcripts and working files persist on the server.

Model discovery is authenticated and cached for five minutes per enabled Codex account. Usage polling refreshes it using the existing account session. The picker combines available models, hides catalog-hidden entries except a saved selection, and offers the reasoning levels shared by accounts exposing that model. Account selection uses fresh catalogs to avoid routing a known model to an account that does not expose it. Discovery failures keep the last catalog with a visible retry status. Custom provider aliases remain supported by the API. [Codex model discovery protocol](https://learn.chatgpt.com/docs/app-server#models).

## Execution and recovery

Tasks continue to use `codex exec`. Chats use Codex app-server `thread/start`, `thread/resume`, `turn/start` and `turn/steer`, tested against Codex 0.154.0. Steering includes the active `expectedTurnId`. If that turn has already finished, the message remains pending for the next turn.

`Chats` owns durable user messages and dispatch. `Worker` owns account selection, project locks, timeout budgets, restart fencing and execution. `chat-process.ts` adapts app-server notifications to the shared Activity artifact format. Each active reply has a supervised process; idle chats hold no account or worker slot. Restricted agents run the same adapter inside the isolated container, with a read-only inbox containing only that run's steering messages.

Messages use client-generated IDs. Requests can be retried without duplicating submissions. On recovery, Codex's persisted user-message client IDs reconcile accepted messages, using paginated turn history when required. A completed saved reply is recovered without another model turn. Interrupted replies continue with the existing workspace and conversation. Failures pause follow-ups for review. Usage exhaustion follows the existing account failover path.

SQLite schema version 4 adds indexed chat messages; records and task runs are preserved. Chat events are excluded from the normal 30-day task-event pruning and event-count cap. Workspace cleanup disables further messages in that conversation.

Validation includes authenticated API boundaries, restricted project access, live steering, queue editing and deduplication, model overrides, account-backed isolated execution, restart and lost-completion recovery, process disconnection, and Chromium/WebKit journeys with desktop/mobile light/dark screenshots.

## Questions during a reply

Codex can ask for input while continuing its work, or wait when an answer is required. A **Your input** card appears above the composer, and the chat history shows how many questions need an answer. Open **Answer** to choose an option or write your own response. Suggested choices are never submitted automatically. You can close the panel and answer later; questions survive navigation and server restarts. Answering a live question works even when the follow-up queue is paused.

The adapter enables Codex's `default_mode_request_user_input` feature for chats. It supports `item/tool/requestUserInput` server requests and questions attached to assistant messages. Live request answers use the native response protocol; if the request expired or the process restarted, an answer can instead steer or start the next turn in the same conversation. Other interactive RPC requests, including approvals, remain unavailable. Answer submissions are idempotent and retained until delivery. Questions and the notification outbox are committed together in SQLite.

## Notifications

Open the bell in **Chats**, or **Settings → Question notifications**, then choose **Enable on this device**. Permission is requested only after that click. Web Push can deliver while the app is closed; clicking a notification opens the relevant chat and question. Each device can opt out independently. The notification contains no question or answer text.

On iPhone and iPad, install Leo using Safari's **Share → Add to Home Screen**, then enable notifications from that installed app. This requires iOS/iPadOS 16.4 or newer ([WebKit guidance](https://webkit.org/blog/13878/web-push-for-web-apps-on-ios-and-ipados/)). Delivery depends on the browser, OS notification settings, and network availability.

The public app must use HTTPS. No notification SaaS account is required: VAPID keys are generated automatically and encrypted with the existing persistent application key, along with browser subscription credentials. Keep the data volume and its encryption key when moving the deployment. Supported push providers are Chrome/Chromium (FCM), Firefox, Safari, and Edge's Windows notification service. The server validates provider endpoints, removes expired subscriptions, and retries temporary failures for up to an hour. It skips notifications for questions already answered. The service worker does not cache pages or authenticated API responses.

Tests cover the native Codex request/response shape, blocking and nonblocking questions, late answers, replay, restart, API authorization, notification retry and expiry, private push content, device enrollment/revocation, notification routing, and desktop/mobile question screens in Chromium and WebKit. Browser automation replaces the OS permission prompt and push provider; actual delivery to a phone requires opting in on that phone.
