# Leo Android 0.6.1 — Fil, compact native reading

- Implements the selected Fil direction using the web palette and bundled DM Sans /
  Manrope fonts. Font licenses are included in the APK; Android font scaling is retained.
- Compact 56 dp conversation header and single-row resting composer. Messages use
  16 sp body text, 24 sp line height and 16 dp reading margins; assistant replies
  stay on the page rather than in cards.
- Sending uses a discreet 32 dp face and 18 dp arrow inside a 48 dp native touch target.
- Published files use a compact horizontal rail with small previews in the transcript.
  Full galleries, group names, versions, authenticated opening and sharing remain available.
- Tasks have an underlined filter strip, flat rows, fine separators and a narrow
  selection marker. Execution details remain expandable with labelled touch targets.
- History follow distinguishes actual finger movement from text relocation during a stationary press.
- No backend change or dependency-pin upgrade. This is a density adaptation inspired
  by the supplied ChatGPT screenshot, not a pixel-identical reproduction.

## Previous release: Leo Android 0.6.0 — native web parity

- Compact Conversations header with a searchable, dated native conversation sheet.
  Switching conversations retains message, model and attachment drafts.
- Ordinary sends appear in the transcript. The waiting queue is collapsible;
  steering, pause, edit, remove and stop remain available.
- Agent-reported completion appears below the reply with expandable evidence.
  Blocked and input-required reasons remain visible. Routine session notices stay hidden.
- Tasks use a grouped inbox, a compact chooser on phones and a side pane on large
  windows. Conversation, Result and Files have native tabs. Task and execution
  details are sheets; management and recovery actions remain available.
- Fullscreen reading, selectable messages, consistent Markdown font scaling,
  compact Leo navigation and search across tasks, agents, projects and skills.
- Project source mode and starting revisions match the web. Agent access editing
  matches shared GitHub restrictions, dedicated tokens and MCP tool selection.
- History responses capture the latest visible message before inserting older
  rows, protecting the reading anchor when a response overtakes a scroll observer.
- Existing encrypted history, stable scrolling, files, questions, connections and
  native authentication flows are retained. No backend migration is required.

Validation is recorded separately in [WEB-PARITY.md](docs/WEB-PARITY.md) and
[VALIDATION.md](VALIDATION.md).

## Previous release: Leo Android 0.5.6 — stop cascading history loads

- Loading an older page waits for the viewport to settle before automatic paging
  can re-arm. A response alone no longer triggers the next page.
- The visible text anchor is retained when a page arrives during an active drag;
  the same finger can continue scrolling without lifting.
- Includes the latest chat improvements hiding routine session notices.
- No server change or database migration is required.

## Previous revision: Leo Android 0.5.5 — stable older-history loading

- Loading older messages preserves the current reading position, including movement
  made while the request is pending, in both chats and runs.
- Long Markdown messages reserve space during their initial asynchronous render so
  the list does not skip them before their text appears.
- The older-history control keeps a stable height. Automatic paging uses distance
  from the top and requests each cursor once; explicit error retry remains available.
- No backend change or database migration is required.

## Previous revision: Leo Android 0.5.4 — message links and artifact previews

- Markdown links open from the conversation while keeping text selectable.
- Artifact links open the authenticated native viewer, including files from older
  runs that are no longer in the loaded history. Relative and same-server absolute
  links are supported; external links use the browser.
- Missing files and browser-opening failures produce a visible message.
- The web client also opens artifact message links in its integrated viewer.

## Previous revision: Leo Android 0.5.3 — predictable conversation following

- The down arrow reaches the actual bottom, including the final spacing.
- Touch pauses automatic scrolling immediately. Moving toward older text exits
  follow, including while the assistant is streaming.
- Scrolling toward the end keeps follow enabled; reaching the end manually enables
  it again. Overscroll at the bottom no longer disables follow.
- A tap, incoming text or keyboard resize never pulls a reader back down.
- Chats and run activity use the same gesture handling.

## Previous revision: Leo Android 0.4.1 — stable conversation resume

- Open conversations retain their stream cursor and accumulated messages while the
  screen is stopped. Returning to the foreground resumes from the accepted cursor.
- Initial history and reset/reconnect catch-up are displayed atomically, after the
  last batch. The conversation no longer jumps through old pages while loading.
- A restored list is attached only when its history is ready. Manual reading
  position and paused following survive background/foreground transitions.
- Chat and run auto-follow wait for history readiness. Stream state remains scoped
  to the connection and route, and is reset when the session generation changes.
- Regression tests exercise partial pages, opening/reopening, foreground resume,
  manual scrolling, compressed message continuation and session/path isolation.
- The native Android client, CI workflow and native MCP OAuth server bridge are
  included together in the repository. Generated Android builds are excluded from
  the web linter.

## Previous revision: Android 0.4.0 — native activity presentations

- Session lifecycle events show readable French summaries instead of raw event JSON.
  Account selection and successful completion use compact rows. Raw source is
  available only through the advanced disclosure for normal-sized supported results.
- Terminal commands, file reads, workspace browsing, searches, diffs, plans,
  reasoning and MCP calls each have a native presentation with an icon and status.
- Simple shell commands are classified without execution. Shell operators,
  substitutions and ambiguous commands stay in the terminal view. Expected
  grep/rg and diff exit-code outcomes are distinguished from failures.
- MCP text/structured results become readable fields, expandable collections,
  workflow-check rows and resource links. Returned images use bounded native
  decoding. Unknown result fields remain inspectable; truncated JSON is labelled.
- Markdown reads have a document preview, file changes show coloured diffs and
  addition/removal counts, and plans show progress and completed steps.
- Legacy recorded tool steps are paired with their results. New and historical
  activity share these components in chats and runs.
- JSON artifacts also have a structured reader with source available separately.
  Inline artifact viewers use the conversation host so live updates do not close them.

The web activity modules in this repository are the reference. No API, dependency,
notification, authentication or server deployment changes are part of this revision.

## Previous revision: 0.3.0 — conversation-focused interface

## Changes

- Chat and run details have a single compact header. Phone bottom navigation is
  reserved for the main sections, freeing space for the conversation and keyboard.
- The chat composer grows to at most four visible lines. Attach and send/stop use
  icons; model, reasoning, pause and run navigation are available from the header.
  Steering is available in that menu when composing during an active run.
- Assistant messages flow directly on the page; user messages use a quiet,
  right-aligned bubble. Markdown has more generous line spacing.
- Consecutive technical events form collapsed activity groups. Tool lifecycle
  updates merge at their original position within a turn. Errors remain signalled
  on the collapsed group; commands, results and raw data remain inspectable.
- Published files appear in grouped horizontal galleries beside the relevant
  response, following the web timeline, and in the run result. The full gallery provides search, version selection and an adaptive
  grid. File readers use compact download/share/open-with actions.
- Run details concentrate on result and activity, with files and mission details
  accessible from the header. Dragging upward stops automatic following; a
  labelled arrow returns to the latest activity.
- Main screens use quieter headings, search fields and cards. Task, agent,
  project, skill and MCP actions use labelled icon controls with tooltips and
  native touch targets. Confirmation flows remain in place.

The app retains Leo's blue-violet light/dark theme and native Compose components.
No backend or authentication changes are part of this visual revision.
Notifications continue to use the previously selected Firebase-free periodic
checks. The optional native MCP OAuth server patch from 0.2.0 is unchanged and
has not been deployed by this task.

## Validation

See `VALIDATION.md` for measured checks and device limitations. Captures are
native Robolectric renders with fixture data; a constrained viewport exercises
reduced available height without claiming to emulate a Samsung keyboard.
