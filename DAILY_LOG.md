# Daily log

Newest run first. Each entry ends with the REPORT table that was sent to alpha.

---

## 2026-10-16 — daily run (cloud routine)

**Model:** Opus 5.5 (`claude-opus-5-5`). Claude Fable 5.1 is still listed above it;
the run has no RemoteTrigger tool, so it cannot switch → ACTION FOR ALPHA stays open
(NEEDS_APPROVAL #7).

- Main CI was green at the start (`2a6c2cb`). There is still no Android SDK in the cloud box,
  so CI was the only build.
- Room identity hashes are now computed locally: `SchemaIdentityKey` is
  re-implemented from the androidx sources and matches all committed schemas, so a
  hand-written `N.json` no longer needs a failed CI run to learn its hash.
- **PR #14** roadmap #7: `messages.hops` (Room 4→5) stores the hop count from
  the ACK, and the bubble reads `delivered · N hops`. Counts outside 1..6 are dropped.
  108 instrumented tests green on the first attempt. Merged, `v2026-10-16-1`.
- **PR #15** roadmap #8: notifications for direct messages, on their own channel. A tap opens
  the thread. Sender and text are hidden on the lock screen. Nothing is posted for the
  thread on screen, and broadcasts never notify. Green on the first attempt. Merged,
  `v2026-10-16-2`. Needs two-phone check.
- **PR #16** roadmap #9: the unread badge already existed, but it compared the sender's
  clock with ours. Offline phones never sync their clocks, so new messages could count
  as read, or a badge could stick. `messages.receivedAt` (Room 5→6) now drives
  unread, and leaving a thread marks what arrived meanwhile as read.
- Branch reuse: after each squash-merge the designated branch restarts from main
  with a `merge -s ours` of its old head, so there is no force-push.

### REPORT
| Field | Content |
|---|---|
| Done | #7 hop count (PR #14); #8 notifications (PR #15); #9 unread by local receive time (PR #16) |
| Merged | #14 yes `v2026-10-16-1`; #15 yes `v2026-10-16-2`; #16 see notification |
| Failed or blocked | Nothing failed. No local Android build possible (CI only) |
| Roadmap | 14 open items + tech debt; no replan needed |
| Model | Opus 5.5. ACTION FOR ALPHA: switch the routine to Claude Fable 5.1 if your plan includes it without an upgrade |
| Needs approval | Model; two-phone checks for #9 (yesterday) and #15 (notifications); backup; signing; route strip |
| Next | #10 failed state + retry, #11 settings + display name (split), #12 release build hardening |

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
