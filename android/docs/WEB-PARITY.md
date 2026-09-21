# Android 0.6.0 / web parity

Reference: web commit `2e9868233295af713eede804c7d70b9f514df04b` (v0.21.12).
This audit compares user actions and information, with native Android presentation.
It does not assert pixel equality between a browser and Material components.

## Feature map

| Area | Native Android implementation | Validation |
| --- | --- | --- |
| Navigation | Leo identity, flat More menu, resource search, phone bottom navigation and large-window rail; no workspace-owner decorations | Workspace journey; device review |
| Conversations | Explicit compact chooser, title, search, Today/Yesterday/Earlier groups, selected conversation, pending-answer and execution status, new conversation | Chat journey; device switching/search |
| Composer | Native expanding text field, attachments, model/reasoning controls, preserved drafts when switching chats, send/queue/steer/stop, IME insets | Chat journey; device keyboard and enlarged text |
| Delivery | Initial send and initiating run message appear in transcript; only waiting follow-ups appear in queue; acknowledgements deduplicate optimistic messages; question responses use server privacy redaction | Six presentation regressions; existing message/network cases |
| Queue and questions | Collapsible queue, pause/resume, edit/remove/steer, option/free-text/private answers | Chat journey; network/Compose suite |
| Completion | Quiet task outcome below final reply, expandable reason/evidence, explicit blocked/input state, attribution; routine session rows hidden in chat; failures remain visible | Presentation/activity cases; device evidence expansion |
| Task workspace | Conversation-first detail, compact chooser, grouped inbox, search and filters, persistent list on wide windows | Native UI and workspace journeys; device task screens |
| Task management | Create/edit/duplicate/delete/archive/restore, schedules/timezones/tags, agent/project/model/limits, pause/resume/run, original brief | Workspace journey; device editor; existing task form |
| Runs | Conversation/result/files, history, fullscreen, stop/retry/resume, errors/account waiting, usage, snapshots, project source revision, workspace cleanup guard | Workspace journey; history/scroll regressions |
| Agents | Existing configuration and access controls, project-scoped GitHub behavior, dedicated token on creation, permitted skills and MCP tools | Form/API comparison; existing account/MCP suite |
| Projects | Remote branch or committed local snapshot, path and base branch, starting revision in run details | Contract decoding; form/API comparison |
| Skills and MCPs | Skill content/files, scopes and validation, MCP configuration/authentication/discovery/tool restrictions | Existing API and MCP/Compose cases; form/API comparison |
| Connections and access | OAuth, GitHub, Codex accounts/quotas, token grants, authorization, logout and encrypted sessions | Existing network/authorization/cache suite |
| Artifacts | Authenticated previews, versions, groups, images/PDF/Markdown, save/share and deep links from replies | Chat journey; artifact and link gesture regressions |
| Reading | Wrapped long text/URLs, selectable native Markdown, history pagination, stable reading anchors during drag/streaming, jump to latest | Markdown/history suite; real device gesture tests |
| Appearance | Shared restrained palette, light/dark/system themes, Material sheets and controls, font scaling, touch-sized labelled actions | Native captures; large-text/keyboard device checks |

Validation results are recorded in `../VALIDATION.md`; entries above identify the
coverage used, not a claim that every production account/provider was exercised.

## Platform equivalents

- Navigation uses Android system Back, bottom sheets and a navigation rail on wide
  windows. Chat and run readers hide the global phone navigation to maximize room.
- Android document/photo pickers and system share/save/selection replace browser
  upload/download/clipboard controls. External OAuth uses Custom Tabs.
- Foreground updates use SSE. Background question notifications retain the
  existing Firebase-free WorkManager service: Android schedules periodic checks
  (approximately 15 minutes, potentially delayed by the OS), rather than browser
  Web Push. This is an explicit delivery-timing difference.
- Drafts survive in-app conversation switching; sensitive attachments remain in
  app storage and are cleared with session logout/forget. No draft is uploaded
  before the user sends it.

## Test data and privacy

Device UI captures use a local MockWebServer fixture, synthetic messages and
example.test links. They do not contain production conversations or credentials.
