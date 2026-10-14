# Daily log

Newest run first. Each entry ends with the REPORT table that was sent to alpha.

---

## 2026-10-14 — run 0 (setup, interactive session)

**Model:** Claude Opus 5.5 (`claude-opus-5-5`), running from alpha's PC.

### Phase 0 — setup
- Read the whole codebase (≈15.8k lines Kotlin). Baseline: 231 JVM unit tests pass locally.
- Wrote CLAUDE.md (architecture, commands, style, all policies), ROADMAP.md (23 items + tech debt), NEEDS_APPROVAL.md, this log.
- Added `.github/workflows/android.yml` (lint, unit tests, debug APK artifact) and made `gradlew` executable in git.

### Audit highlights
- Outgoing messages never reach `delivered`: ACK packets are never sent.
- A packet holds only 55 bytes of text, so normal sentences are rejected.
- Direct messages fall back to plaintext when the peer's X25519 key is unknown, and that key is not persisted, so after a restart queued messages go out unencrypted.
- Undecryptable payloads are rendered as garbage text.

### Phase 1 — work
- PR #1 setup: CI failed once (`android-actions/setup-android@v3` installs the removed `tools` package); fixed by relying on the runner's preinstalled SDK. Merged, tag `v2026-10-14-1`.
- PR #2 delivery receipts (roadmap #1). Merged, `v2026-10-14-2`.
- PR #3 long text up to 1000 bytes via fragments, plus `SendResult.Failed` (roadmap #2). Merged, `v2026-10-14-3`.
- PR #4 undecryptable placeholder (roadmap #3). Merged, `v2026-10-14-4`.
- Unit tests: 231 → 251, all passing locally and in CI.

### Phase 2 — autonomy
- Created cloud routine "Null Chat daily dev" (`trig_01BaGqhUnAt9AjNSJBaDwYvu`), cron `0 3 * * *` UTC = 06:00 Africa/Nairobi, model alias `opus`, repo Alphaguyasa/NullChat. First run 2026-10-15.
- The API attached all 31 of alpha's claude.ai connectors by default (incl. Stripe, Gmail); removed them all — the dev loop needs none and Stripe conflicts with the no-purchases policy.
- Push notifications have no routine setting; the run sends the report with the PushNotification tool (see CLAUDE.md).

### REPORT
| Field | Content |
|---|---|
| Done | Setup (#1); delivery receipts (#2); long messages up to 1000 bytes (#3); undecryptable placeholder (#4) |
| Merged | Yes ×4 — tags v2026-10-14-1 … -4 |
| Failed or blocked | Setup CI failed once (SDK setup step), fixed on the branch. Nothing open. |
| Roadmap | 20 items + tech debt left; no replan needed |
| Model | Opus 5.5; routine uses alias `opus`. ACTION FOR ALPHA: set the routine to "Automatically approve". |
| Needs approval | Auto-approve; two-phone check of #2/#3; cloud backup of history; signing/Play Store ($25, not paid); route strip privacy |
| Next | Roadmap #4 emulator instrumented tests in CI, #5 persist agreement keys (migration), #6 no plaintext DMs |
