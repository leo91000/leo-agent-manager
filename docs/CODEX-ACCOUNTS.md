# Codex accounts

Open **Connections → Add account**, choose a name, and complete Codex's device sign-in with the corresponding ChatGPT account. Up to ten accounts can be connected. Each account can be renamed, paused, reconnected, or removed. Active accounts cannot be reconnected or removed until their run finishes; pausing prevents future assignment.

The worker reads usage every minute through the official Codex app-server. Enabled accounts with banked resets are checked every 15 seconds once remaining capacity reaches 10%, or while recovering from exhaustion or an unresolved redemption. The page fetches cached readings every five seconds and shows the short and weekly windows, reset times, banked resets, low capacity below 5%, and the active run. Stale readings are marked unavailable rather than presented as current. The overall remaining percentage is the lowest remaining window, including applicable model-specific limits when scheduling. “Next run” on the page refers to Codex's general capacity; an agent's model-specific limit can change its selection.

A new run selects the enabled, idle account with the most remaining capacity. Ties prefer the least recently used account. Unknown, stale, exhausted, or backend-blocked accounts are excluded. Each account handles one run at a time, within the existing global concurrency and project locks. Once account management is enabled, an empty or unavailable pool leaves new tasks queued with a visible reason.

## Natural resets and continuation

Natural usage-window resets happen on OpenAI's side and are detected automatically. Enabled accounts also redeem one banked reset when the lowest relevant window reaches **2% remaining**, even without a running conversation. This uses at least 98% of the limiting allowance while retaining a small buffer for uninterrupted work. The worker also attempts redemption immediately after a run reports a subscription usage limit. Paused accounts never start or retry redemptions. When credit details are available, the earliest-expiring usable Codex reset is selected first; otherwise the backend selects a credit. This never buys capacity or changes a subscription.

Redemptions use the official `account/rateLimitResetCredit/consume` API. A request key is persisted before redemption and reused after timeouts and restarts. A confirmed redemption cannot consume another credit until fresh usage shows capacity above 2% again; this avoids draining banked resets on delayed backend readings. Reset errors appear on the account card and do not interrupt a healthy run or exclude otherwise usable capacity. Backend eligibility still applies: `nothingToReset` and `noCredit` leave the existing account-switching and natural-reset behavior available. A reset timestamp alone never makes an account available.

The threshold and polling buffer reduce interruptions but cannot guarantee zero pauses: a burst can exhaust capacity between polls, and the backend can reject or delay a reset. Once all banked resets and other accounts are exhausted, the run must wait for capacity within its original timeout.

If Codex exits with a subscription usage-limit error and a saved session ID, the worker releases the exhausted account and resumes the same conversation with the next available account. It preserves the working directories, changes, agent policy, MCP scope, and original run timeout. It tells the resumed agent to verify external effects before repeating an action. This continues the task, but cannot guarantee that a model will never repeat a side effect. Ordinary command failures, authentication errors, and temporary HTTP throttling do not trigger account switching.

If every account is unavailable, the run waits for capacity while retaining its project lock. It stays cancellable, and the original timeout still applies. Account recovery is confirmed by fresh window usage or the backend clearing its explicit usage block. After a worker restart, active runs resume with a freshly selected available account and their retained conversation. Recovery first stops the previous process/container before recovering rotated credentials. A failure without available saved Codex history cannot be resumed automatically. See [restart recovery](RESTART-RECOVERY.md).

## Credentials and configuration

Account credentials are encrypted in the existing application vault, whose key remains in the persistent data volume. Preserve that key with database backups. Credentials are materialized in a private per-run `CODEX_HOME`; refreshes are captured back into the vault, and temporary auth files are removed after use. Restart recovery captures credentials left by interrupted managed runs. Account metadata and usage APIs never return authentication tokens.

The first startup imports an existing file-backed ChatGPT login as **Primary account**. The original CLI login file is left untouched; use the Connections page for subsequent managed-account reconnects. API-key logins are not imported. Unrestricted runs retain their shared user configuration, rules, skills and plugins while keeping Codex session state per run. Isolated agents receive only the selected account credential and their existing permitted resources, never the account pool or encryption key. YOLO remains the default policy.

Requires a Codex CLI with `account/read`, `account/rateLimits/read`, `account/rateLimitResetCredit/consume`, and `codex exec resume`. The reset protocol was checked against installed Codex 0.154.0 and the official CLI source. Automated reset and session handoff tests use synthetic accounts and a fixture subprocess; validation does not spend real banked resets.

Protocol reference: [Codex app-server account and rate-limit APIs](https://learn.chatgpt.com/docs/app-server#6-rate-limits-chatgpt).

Screenshots use synthetic accounts: [desktop](screenshots/codex-accounts-desktop.png) · [mobile, dark theme](screenshots/codex-accounts-mobile-dark.png).
