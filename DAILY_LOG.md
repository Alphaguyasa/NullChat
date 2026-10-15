# Daily log

Newest run first. Each entry ends with the REPORT table that was sent to alpha.

---

## 2026-10-15 — run 1 (scheduled cloud routine)

**Model:** Opus 5.5 (`claude-opus-5-5`). Docs list Claude Fable 5.1 as a higher
tier; the run cannot switch models itself → ACTION FOR ALPHA (NEEDS_APPROVAL #7).

- Main CI green at start (`ccac2c6`). No Android SDK in the cloud box (dl.google.com
  blocked), so CI was the only build; `gh` REST works, GraphQL does not.
- Single designated branch `alpha/beautiful-davinci-d89i0m` per the routine's
  environment; each next PR restarts it from main (merge `-s ours` of the old
  branch head instead of a force-push).
- **PR #7** roadmap #4: emulator job (API 34, KVM) runs all instrumented tests.
  100 tests green. Merged.
- **PR #8** roadmap #5: `peers.agreementKey`, migration 3→4 with test, restore at
  start, schema-drift CI step. Attempt 1 failed the new migration test (hand-written
  `4.json` carried the old identity hash; no SDK to generate it locally). Fixed with
  the hash Room reported; 103 instrumented tests green. Merged.
- **PR #9** roadmap #6: direct messages are held (PENDING) until the recipient's
  agreement key is known, released when it arrives; long text/images to an
  unkeyed peer fail with a reason. Needs two-phone check.
- Tags: refused by the cloud proxy; listed in NEEDS_APPROVAL #6 with SHAs.
- Unit tests 251 → 261 (+6 PublicKeyDirectory, +4 hold/release); instrumented
  100 → 103 (+1 migration, +2 PeerDao).

### Follow-up (same day, after alpha's replies)
- alpha said yes to roadmap #24. PR #10 recorded it; then, at alpha's request, PR #11
  added `release-tag.yml` straight away. Its first run created `v2026-10-15-2` … `-5`
  but failed on `-1`: GitHub refuses GITHUB_TOKEN a tag on a commit whose workflow
  files differ from main's (#7's `android.yml` was changed again by #8). PR #12 makes
  the workflow never backfill behind an existing tag, so that commit is not retried
  out of order. `-1` is optional for alpha to create by hand.

### REPORT
| Field | Content |
|---|---|
| Done | #4 emulator tests in CI (PR #7); #5 persist agreement keys (PR #8); #6 no plaintext DMs (PR #9) |
| Merged | #7 yes, #8 yes; #9 see notification. Tags not pushed (proxy refuses) — SHAs in NEEDS_APPROVAL |
| Failed or blocked | PR #8 CI failed once (schema hash), fixed. Tag pushes blocked from cloud |
| Roadmap | 17 open + 1 new (#24 release tags from CI); no replan needed |
| Model | Opus 5.5. ACTION FOR ALPHA: switch the routine to Claude Fable 5.1 if your plan has it without upgrading |
| Needs approval | Model; missing tags / tag workflow; two-phone check of #9 (plus #2/#3 from yesterday); backup; signing; route strip |
| Next | #7 hop count on delivered, #8 notifications, #9 unread counts |

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
