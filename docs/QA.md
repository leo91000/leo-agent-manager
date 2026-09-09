# Validation record

Validation performed on 2026-09-09. Screenshots use an isolated fixture workspace,
not production projects or credentials. Browser journeys exercise a real Fastify
service, SQLite database, filesystem, and subprocess; their model runner is a
small explicit fixture. Actual Codex/GitHub checks are recorded separately below.

## Automated coverage

`pnpm check` runs Antfu ESLint, TypeScript checks, 31 unit/integration tests, and the
production Vue build. `pnpm test:e2e` runs three complete Chromium journeys plus
mobile layout/navigation passes in Chromium and WebKit.

| Area | Evidence |
| --- | --- |
| Setup, password login/logout, session expiry, CSRF, Origin/Host checks, concurrent setup | `tests/auth.test.ts` |
| OAuth client/redirect/PKCE/resource binding, one-use/expired codes, refresh rotation/reuse, scope checks, revocation | `tests/auth.test.ts` |
| Immutable snapshots, concurrent enqueue, task reference protection, sanitized Git origin metadata | `tests/service.test.ts` |
| Cron validation, pause, catch-up once, overlap, spring/autumn DST | `tests/service.test.ts` |
| Actual global/project skill files, frontmatter validation, nested supporting files, traversal and symlink escapes | `tests/service.test.ts` |
| Actual subprocess output, malformed JSON, usage/session capture, redaction, failures, cancellation, timeout, restart interruption | `tests/worker.test.ts` |
| Project execution serialization, isolated Git worktree, dirty primary preservation, guarded cleanup | `tests/worker.test.ts` |
| Device-code parsing and process completion | `tests/worker.test.ts` |
| Official MCP v2 client over TCP, independent requests, no session ID, tools, scopes, stateless 2025-06-18/2025-11-25 compatibility, revoked access | `tests/mcp.test.ts` |
| Durable reopen, transaction rollback, expired records, schema downgrade refusal, compact pagination, archival restrictions | `tests/store.test.ts` |
| Setup → profile → project → skill → scheduled task → real fixture run → result → reload → pause → mobile navigation → logout | Browser journey 1 |
| Supporting-file write/read/preview, running cancellation, archive/restore, retained history | Browser journey 2 |
| OAuth consent through login, redirect callback, code exchange, settings revocation, denied MCP access | Browser journey 3 |
| Nine workspace pages, all run tabs, five editors, workspace search, drawer scrolling/focus at changing viewport heights | Browser journeys 4–5, Chromium and WebKit |
| Non-root container, CLI binaries/pnpm 12, authenticated API, Vue build, persistent session/data across restart | `tests/container-smoke.mjs` |

## UI/UX critique and exploratory checks

The initial desktop layout had overly small/light supporting text. Font sizes and
contrast were increased while keeping the compact layout. Fonts were moved into
the bundle; dialogs now have accessible names and restore focus. Mobile navigation
and a 390-pixel viewport were inspected after transitions settled. Final screenshots:

- [Desktop overview](screenshots/overview-desktop.png)
- [Mobile overview](screenshots/overview-mobile.png)
- [Agent editor with YOLO execution](screenshots/agent-yolo.png)
- [Initial contrast comparison](screenshots/overview-before-contrast.png)

Exploratory operation through the isolated browser, separately from the scripted
journeys, covered creating an owner workspace, checking connection states, guided
device login, invalid skill frontmatter, correction and Markdown preview, keyboard
search/Escape, creating an agent/project/task, selecting a skill, running the task,
and reading the original task/workspace/access snapshot. The exercise found:

1. Empty POST/DELETE requests incorrectly advertised JSON bodies; Run now/cancel/logout
   could fail before reaching the route. Headers now reflect whether a body exists.
2. Supporting-file reads returned plain text to a JSON client. The API now consistently
   returns `{ content }`; browser tests verify persisted content.
3. Changing a timezone after preview could render stale occurrences with an invalid
   zone. Preview results reset on schedule/zone edits.
4. Event type `status` collided with badge CSS. Event types now use a data attribute.
5. Skill names used an invalid HTML pattern under the browser's Unicode `v` grammar.
   The hyphen is escaped; server-side validation remains authoritative.
6. A small bundled font subset was inlined as a data URL but rejected by CSP. Font CSP
   now allows local/data fonts while external font origins remain blocked.
7. Workspace search omitted skills; they are now included and were found through the
   search dialog during the final manual retest.

The final agent editor was inspected after removing the access selector. Saving an
agent preserved its settings and showed YOLO mode. A fresh page load emitted no
console errors.

The browser suite checks unexpected console errors as well as page exceptions.
Intentional invalid-input HTTP 400 responses are excluded from that console check.

### Mobile pass for v0.1.1

The screenshot matrix covers 320×568, 390×664, 430×932, and 844×390 viewports in
Chromium and WebKit. It checks horizontal overflow, search icon alignment,
separation of Activity controls from tabs, and reachable dialog actions. Drawer
checks resize the viewport to 360, 568, and 844 pixels high, scroll to Sign out,
and exercise focus trapping, Escape, focus restoration, and navigation. Screenshots
are saved in the workflow's Playwright artifact; these are browser emulations,
not physical-device captures.

An independent isolated-browser review also covered login and OAuth approval at
320 pixels wide. The pass fixed the unscrollable navigation, dynamic viewport and
safe-area sizing, stacked search icons, cramped Activity tabs, overflowing run
history, small mobile form controls, and editor height constraints. Selecting the
current navigation destination now closes the mobile drawer as well.

Reviewed WebKit captures: [Tasks](screenshots/mobile-tasks.png),
[Activity](screenshots/mobile-activity.png), [Runs](screenshots/mobile-runs.png),
and [scrolled short-screen drawer](screenshots/mobile-drawer-scrolled.png).

## Actual provider and container execution

The application's connection checker detected local `codex-cli 0.153.4` logged in
with a ChatGPT subscription and `gh 2.100.0` logged in as the repository owner.
A minimal task through the actual worker returned `LEO_MANAGER_SMOKE_OK` with a
successful CLI exit and usage data, without modifying a project.

A live container test ran Codex with `--dangerously-bypass-approvals-and-sandbox`.
Its shell tool ran `pwd`, created/read/removed `/tmp/leo-yolo-probe`, and exited 0;
the final response was `LEO_CONTAINER_YOLO_OK /app`. The existing subscription auth
file was mounted read-only for this ephemeral test. No credentials were copied
into the image. Docker used its normal isolation without privileged mode.

## Performance and delivery boundaries

The [performance record](PERFORMANCE.md) includes the workload, before/after numbers,
and remaining limits. GitHub's `Quality and container` workflow reruns checks,
browser journeys, image build, and container smoke before publishing GHCR images.
The latest remote result is available in the repository's Actions tab.

Live ChatGPT/Claude linking, marketplace listing, and Coolify deployment require the
final reachable deployment/account configuration and are not established by local
SDK/browser tests. Platform submission requirements are documented in [MCP.md](MCP.md).
