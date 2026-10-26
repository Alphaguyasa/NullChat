# Daily log

Newest run first. Each entry ends with the REPORT table that was sent to alpha.

---

## 2026-10-26 — daily run (cloud routine)

**Model:** Opus 5.5 (`claude-opus-5-5`). Anthropic's models page is unchanged: Opus 5.5
is still the newest Opus, and Claude Fable 5.1 sits above it. There is still no
RemoteTrigger tool in the session, so the ACTION FOR ALPHA stays open (NEEDS_APPROVAL #7).

- Main CI was green at the start (`ff8ad5b`, `v2026-10-25-3`). `gh` REST works with the
  session's proxy; GraphQL does not (as before).
- **Dependabot #46** (Monday): Tink 1.23.0→1.24.0, Gradle 9.8.1→9.8.2, and the test-only
  kotlinx-serialization-core force 1.11.0→1.12.0. All minor or patch, green on #46.
  Re-applied as **PR #47** (Alphaguyasa commit), green, merged, `v2026-10-26-1`. #46
  closed with a link to it.
- **PR #48**, roadmap #36: message info. Every bubble's long-press menu has Info. Your
  own messages show the bubble's delivery words and hop rule, so queued is never called
  sent. Incoming messages show the full sender id, the sender's stamp and this phone's
  arrival time as separate rows, plus a note when the sender's clock is over a minute
  ahead. The dialog shows the disappearing time when there is one. The rules sit in the
  pure `messageInfoRows`, with 6 JVM tests. They passed locally in a scratch JVM project
  (Room annotations stubbed), and the PR was green on the first attempt. Merged.
- **PR #49**, roadmap #31 part 3: Forget contact. The design note the roadmap asked for:
  `PublicKeyDirectory` being first-wins does not matter here. A peer id is the hash of its
  signing key, and an ANNOUNCE is signed by the key it carries, so nobody but the peer
  can file different keys under that id. Forget therefore **keeps** the keys in memory,
  because dropping them would only make the peer's relayed packets unverifiable until its
  next announcement. It removes the thread (tombstoned first), its timer, the attachments
  and the peer row (name, nickname, verification). It also withdraws our queued messages
  to the peer from the forward queue, through the new `ForwardQueue.cancel` /
  `MeshRouter.cancelQueued`, so they do not go out after their rows are gone. It is not
  offered while the peer is blocked, because the block lives on the row and forgetting
  would lift it. Router tests pass locally (61, +2 new).
- Unit tests +9 (MessageInfoTest 6, ForwardQueueTest 1, ForwardQueueRoutingTest 1,
  DeleteRuleTest 1). No schema change.

### REPORT
| Field | Content |
|---|---|
| Done | Dependabot bumps (PR #47); #36 message info (PR #48); #31 part 3 forget contact (PR #49) |
| Merged | #47 yes `v2026-10-26-1`; #48 yes `v2026-10-26-2`; #49 see notification |
| Failed or blocked | Nothing failed. No local Android build (CI only; JVM pieces tested in scratch projects) |
| Roadmap | 11 open (#35, #37–#42, #20–#23); no replan needed |
| Model | Opus 5.5. ACTION FOR ALPHA: switch the routine to Claude Fable 5.1 if your plan includes it without an upgrade |
| Needs approval | Model; backup (#3, recommended: exclude history); phone checks 5m (new), 5l, 5k, 5j, 5i, 5h, 5g, 5f, #22, #17/#19, #15, #9; Dependabot authorship; release APK unrun; signing; route strip |
| Next | #35 `InboundHandler` extraction + JVM tests, #37 search in a chat, #38 share into Null Chat |

---

## 2026-10-25 — daily run (cloud routine)

**Model:** Opus 5.5 (`claude-opus-5-5`). Anthropic's models page is unchanged: Opus 5.5
is still the newest Opus, and Claude Fable 5.1 sits above it. There is still no
RemoteTrigger tool in the session, so the ACTION FOR ALPHA stays open (NEEDS_APPROVAL #7).

- Main CI was green at the start (`0fab337`, `v2026-10-24-3`). No PRs were open, and no
  Dependabot PRs (they come on Mondays).
- **PR #43**, roadmap #31 part 2: block sender. `peers.isBlocked` (NOT NULL DEFAULT 0),
  Room 8→9, with a migration test. Messages and fragments from a blocked peer are dropped
  before they are stored, so they are not shown, not notified and not acknowledged.
  Relaying is unchanged. They are not tombstoned, because a spammer's flood would push
  the user's real deletions out of the bounded set. The chat menu has Block (with a
  confirm) and Unblock, and a banner shows while blocked. Green on the first attempt.
  Merged, `v2026-10-25-1`. Forget peer stays open as #31 part 3, now P2/S. It needs a
  design note first, because the key directory is first-wins.
- **Room identity hash script rebuilt.** It was not committed last time. The new one
  sorts with Java's English `Collator`, as Room does, and reproduces all eight committed
  hashes before writing `9.json`. The 8→9 migration test passed on the first CI run.
- **PR #44**, roadmap #33: with the app lock on, notifications say only "New message"
  (or "N new messages"), with no name and no text. Green on the first attempt. Merged.
- **PR #45**, roadmap #34: fonts are bundled in `res/font`. These are static 400/500
  instances of the OFL variable fonts from google/fonts, made with fontTools, subset to
  Latin, Greek, Cyrillic and symbols, hinting dropped. They total about 945 KB raw,
  roughly 0.45 MB compressed (the roadmap guessed 300 KB). `ui-text-google-fonts` and
  `font_certs.xml` are removed, so the app no longer asks Play Services to download
  fonts. Licences are in `assets/licenses/`. None of the fonts has a Reserved Font
  Name, so the subsets may keep their names. A new `ArchitectureRulesTest` case keeps
  downloadable fonts out.
- Unit tests +8 (PeerBlockTest 4, MessageNotificationPolicyTest 3, ArchitectureRulesTest 1).
  Instrumented +3 (PeerDaoTest 2, migration 8→9).

### REPORT
| Field | Content |
|---|---|
| Done | #31 part 2 block sender (PR #43); #33 lock hides notification text (PR #44); #34 bundled fonts (PR #45) |
| Merged | #43 yes `v2026-10-25-1`; #44 yes `v2026-10-25-2`; #45 see notification |
| Failed or blocked | Nothing failed. No local Android build (CI only) |
| Roadmap | 13 open (#31 part 3 forget peer, #35–#42, #20–#23); no replan needed |
| Model | Opus 5.5. ACTION FOR ALPHA: switch the routine to Claude Fable 5.1 if your plan includes it without an upgrade |
| Needs approval | Model; backup (#3, recommended: exclude history); phone checks 5l (new), 5k, 5j, 5i, 5h, 5g, 5f, #22, #17/#19, #15, #9; Dependabot authorship; release APK unrun; signing; route strip |
| Next | #35 `InboundHandler` extraction + JVM tests, #36 message info, #37 search in a chat |

---

## 2026-10-24 — daily run (cloud routine)

**Model:** Opus 5.5 (`claude-opus-5-5`). Anthropic's pages still list Opus 5.5
(2026-09-22) as the newest Opus, with Claude Fable 5.1 above it. There is still no
RemoteTrigger tool in the session, so the ACTION FOR ALPHA stays open (NEEDS_APPROVAL #7).

- Main CI was green at the start (`cc6be04`, `v2026-10-23-3`). No PRs were open.
- **PR #40**, roadmap #30: a link belongs to a peer only after a verified ANNOUNCE
  arrives on it. The router reports each first-seen, verified ANNOUNCE with its link
  (`linkProofs`). The pure `LinkBindings` binds an endpoint only when the proof's id
  matches the id the endpoint advertised. Connecting no longer marks a peer online
  or drains its queue. A stranger in range of the real contact can still relay that
  contact's live announcement onto its own link, and closing that needs a
  challenge (wire change). That is now roadmap #42. Green on the first attempt.
  Merged, `v2026-10-24-1`. Needs two-phone check (5k).
- **PR #41**, roadmap #31 part 1: Delete chat from the thread menu. Rows are
  tombstoned and then deleted. The user's own queued messages and the thread's
  disappearing timer stay. No schema change. Green on the first attempt. Merged.
  Part 2 (forget peer, block sender, Room 8→9) stays as #31.
- **PR #42**, roadmap #32: the router drops a duplicate before the Ed25519 check
  (`SeenCache.contains` only reads, so the eviction argument for verifying before
  `markSeen` still holds). Inbound ttl above 6 is clamped. A forged 255 used to
  flood past the radius and read as -249 hops.
- Local JVM checks: the router and protocol packages build in a scratch Gradle
  project with no SDK. Maven Central returned 429 there, and Google's Maven Central
  mirror worked. 59 router tests pass. Each new router test failed on the code
  before its fix.
- Unit tests +16 (LinkBindingsTest 8, LinkProofTest 4, InboundCostTest 3,
  DeleteRuleTest 1). Instrumented +1 (MessageDaoTest).

### REPORT
| Field | Content |
|---|---|
| Done | #30 verified links (PR #40); #31 part 1 delete chat (PR #41); #32 router dedup before verify + ttl cap (PR #42) |
| Merged | #40 yes `v2026-10-24-1`; #41 yes `v2026-10-24-2`; #42 see notification |
| Failed or blocked | Nothing failed. No local Android build (CI only) |
| Roadmap | 14 open (#31 part 2, #33–#42, #20–#23); new #42 link challenge; no replan needed |
| Model | Opus 5.5. ACTION FOR ALPHA: switch the routine to Claude Fable 5.1 if your plan includes it without an upgrade |
| Needs approval | Model; backup (#3, recommended: exclude history); phone checks 5k (new), 5j, 5i, 5h, 5g, 5f, #22, #17/#19, #15, #9; Dependabot authorship; release APK unrun; signing; route strip |
| Next | #31 part 2 forget peer / block sender, #33 lock hides notification text, #34 bundle fonts |

---

## 2026-10-23 — daily run (cloud routine)

**Model:** Opus 5.5 (`claude-opus-5-5`). The models page still lists Claude Fable 5.1
above it and no newer Opus. There is still no RemoteTrigger tool in the session, so
the ACTION FOR ALPHA stays open (NEEDS_APPROVAL #7).

- **Main CI at the start: cancelled, not broken.** The emulator job for #36's merge
  (`88fb3b3`) ran every test to BUILD SUCCESSFUL in 20 s. It then hung after
  `adb emu kill` until the 45-minute job timeout cancelled it. The same tree had
  passed on the PR. One re-run was green, so main was green before any work
  started. This is an emulator-runner teardown hang, not the app. If it comes
  back, the next step is a shorter timeout on that step plus one re-run, not a
  code change.
- **PR #37**, roadmap #27: sends survive leaving the chat. `send`, `sendImage` and
  `resend` run in the mesh scope (`outliveCaller`), and the screen only waits for
  the result. A transfer cancelled partway (the mesh scope itself ending) marks the
  row FAILED, under `NonCancellable`. Green on the first attempt. Merged,
  `v2026-10-23-1`.
- **PR #38**, roadmap #28: bound received images. Reassembled JPEGs over 14 KB are
  dropped before they are stored. Display decodes in two passes and stays under
  1024 px. Green on the first attempt. Merged.
- **PR #39**, roadmap #29: order by this phone's clock. The thread, its preview and
  thread activity now use `receivedAt`. Threads already pinned in the future are
  pulled back once at start. A bubble's time is clamped to its arrival. It needed
  no schema change: `receivedAt` already existed (Room 5→6), so there is no
  migration. The thread query now sorts on a column the `(conversationId,
  timestamp)` index does not cover. That is fine at chat sizes; an index would
  need a migration if it ever matters.
  Attempt 1 failed one instrumented test: `observeMessagesReflectsBothDirections…`
  stored its incoming row with the real clock (2026) and its outgoing row at T0
  (2023), so arrival order flipped them. The test now pins `now`. Attempt 2 was
  green. Merged.
- **New: JVM checks without the Android SDK.** Pure files (the protocol package,
  `ImagePolicy`, the new helpers) and their tests ran in a scratch Kotlin/JVM
  Gradle project: 51 tests. The main #27 test failed when the old behaviour
  was put back, as it should.
- Unit tests +25 (SendLifetimeTest 7, InboundImageTest 4, ImagePolicyTest 10
  cases, ShownTimeTest 4). Instrumented +4 (MessageDaoTest 1, MessageRepositoryTest 3).

### REPORT
| Field | Content |
|---|---|
| Done | #27 sends survive leaving the chat (PR #37); #28 bound received images (PR #38); #29 local-clock ordering (PR #39) |
| Merged | #37 yes `v2026-10-23-1`; #38 yes `v2026-10-23-2`; #39 yes `v2026-10-23-3` (expected) |
| Failed or blocked | #39 failed CI once (a test used the real clock), fixed on the branch. Main's post-merge emulator job hung on teardown once; re-run green |
| Roadmap | 15 open (#30–#41, #20–#23); no replan needed |
| Model | Opus 5.5. ACTION FOR ALPHA: switch the routine to Claude Fable 5.1 if your plan includes it without an upgrade |
| Needs approval | Model; backup (#3, recommended: exclude history); phone checks 5j (new), 5i, 5h, 5g, 5f, #22, #17/#19, #15, #9; Dependabot authorship; release APK unrun; signing; route strip |
| Next | #30 link belongs to a peer only after a verified ANNOUNCE, #31 delete chat / block sender, #32 router dedup before verify |

---

## 2026-10-22 — daily run (cloud routine)

**Model:** Opus 5.5 (`claude-opus-5-5`). The models page lists Claude Fable 5.1, Opus 5.5,
Sonnet 5.5 and Haiku 5.5, so nothing is newer than Fable 5.1. There is still no
RemoteTrigger tool in the session, so the run cannot switch models, and the ACTION FOR
ALPHA stays open (NEEDS_APPROVAL #7).

- Main CI was green at the start (`f08334f`, tag `v2026-10-21-3`). There is still no
  Android SDK here, so CI was the build.
- **New: Room schema hashes computed locally.** A small script re-implements Room's
  identity hash (MD5 over the sorted table, field, index and foreign-key keys). It
  reproduces all six committed hashes, and it produced `7.json` and `8.json`. Both
  migrations passed CI on the first try, so database work no longer needs a failed
  CI round to learn the hash.
- **PR #34**, roadmap #18: contact nicknames. `peers.nickname`, Room 6→7. They are
  local only, normalised like display names, and never overwritten by an announcement.
  The peer list, chat title and notifications all use them. Notifications now show a
  peer's name instead of only the short id. Green on the first attempt. Merged,
  `v2026-10-22-1`.
- **PR #35**, roadmap #17 part 1: disappearing messages with a local timer per chat,
  Room 7→8 (`conversations.disappearAfterMillis`, `messages.expiresAt` + index). The
  expiry is stamped once, when a row is written, from local arrival time, so a timer
  never reaches back into history. A sweep every minute in the mesh scope writes
  tombstones, then deletes the rows, then the images. Queued rows wait until they are
  sent. A deviation from the roadmap text: there is no WorkManager, because the
  foreground service already keeps the scope alive (see the PR). Part 2 (syncing the
  timer to the other phone) became roadmap #39. Green on the first attempt. Merged.
- **PR #36**, roadmap #19: battery modes, Automatic / Normal / Saver. Saver keeps the
  5 s scan but rests longer: a 60 s cycle, then 180 s once backed off. Automatic
  follows the system battery saver (`PowerSaveMonitor`, a not-exported receiver).
  The radio timing changed, so it needs a two-phone check (NEEDS_APPROVAL 5i).
- **Replan.** Three items were done and four would have been left (under 5), so a
  second audit (code, security, battery, UX, tests, features) added roadmap #27–#41.
  Top findings, each checked against the code:
  - Sends run in `viewModelScope`, so leaving mid-image strands the row as `queued`
    (#27).
  - Inbound images are not size-checked before `decodeFile` (#28).
  - Ordering uses the sender's clock (#29).
  - An unauthenticated endpoint name can drain the forward queue for a peer id (#30).
  - The router verifies every duplicate before the seen-cache check (#32).
  - Fonts are downloadable, which needs internet (#34).
  - It also strengthens NEEDS_APPROVAL #3 (backup).
- Unit tests +17 (PeerRenameTest 4, PeerLabelTest 3, notification title 1,
  DisappearingTimerTest 3, DutyCycleTest 3, BatteryModeTest 3). Instrumented +12
  (migrations 2, PeerDaoTest 2, MessageRepositoryTest 7, SettingsScreenTest 1).

### REPORT
| Field | Content |
|---|---|
| Done | #18 nicknames (PR #34); #17 disappearing messages part 1 (PR #35); #19 battery modes (PR #36) |
| Merged | #34 yes `v2026-10-22-1`; #35 yes `v2026-10-22-2` (expected); #36 see notification |
| Failed or blocked | Nothing failed. No local Android build (CI only) |
| Roadmap | Replanned: 15 new items (#27–#41) + 4 older ones open. Top: sends survive leaving the chat, bound received images, local-clock ordering |
| Model | Opus 5.5. ACTION FOR ALPHA: switch the routine to Claude Fable 5.1 if your plan includes it without an upgrade |
| Needs approval | Model; backup (#3, now recommended: exclude history); phone checks 5i (new: nicknames, disappearing, battery modes on two phones), 5h, 5g, 5f, #22, #17/#19, #15, #9; Dependabot authorship; release APK unrun; signing; route strip |
| Next | #27 sends survive leaving the chat, #28 bound received images, #29 local-clock ordering |

---

## 2026-10-21 — daily run (cloud routine)

**Model:** Opus 5.5 (`claude-opus-5-5`). The models page lists Claude Fable 5.1, Opus 5.5,
Sonnet 5.5 and Haiku 5.5; nothing newer than Fable 5.1. This session has no RemoteTrigger
tool, so it cannot switch, and the ACTION FOR ALPHA stays open (NEEDS_APPROVAL #7).

- Main CI was green at the start (`2b8d35e`, tag `v2026-10-20-3`). There is still no Android
  SDK here (dl.google.com is blocked; maven.google.com answers but redirects there), so CI
  was the only build.
- **PR #31** roadmap #25: AGP 9.4.1, Gradle 9.8.1, Kotlin 2.4.21, KSP 2.3.12, plus the
  libraries group (Nearby 19.5.1, Tink 1.23.0, coroutines 1.11.0, ZXing 3.5.4, JUnit 6.1.3,
  Turbine 1.2.1, MockK 1.14.11). This is a major bump, so it got its own PR. The wrapper
  files are Dependabot's own. `android.disallowKotlinSourceSets=false` stays for now; KSP
  2.3 may not need it, and removing it can be tried on its own. Green on the first attempt.
  Merged, `v2026-10-21-1`. Dependabot #25 and #27 closed with a link. Nearby changed, so it
  needs a two-phone check (NEEDS_APPROVAL 5h).
- **PR #32** roadmap #26: compileSdk 37 (targetSdk stays 35), core-ktx 1.19.1, Compose BOM
  2026.09.00, hilt-navigation-compose 1.4.0, Room 2.8.5. Green on the first attempt. Merged.
  Dependabot #26 closed.
- **PR #33** roadmap #16: app lock. It uses the system prompt (androidx.biometric 1.1.0, weak
  biometrics or the device PIN/pattern/password), so the app stores no PIN of its own. It
  locks at cold start, and on return after 60 s or more away; shorter trips, such as the
  photo picker, do not lock. The lock screen covers the chats instead of replacing them, so
  the mesh keeps relaying, and the chats are hidden from accessibility services while it is
  up. On Android 13+ the recents thumbnail is off while the lock is on. It needs Android 9+:
  before that, the library draws an AppCompat dialog this app has no theme for.
  `MainActivity` is now a `FragmentActivity`. Needs a one-phone check (NEEDS_APPROVAL 5h).
- Unit tests +10 (AppLockGateTest). Instrumented +4 (SettingsScreenTest 3, LockScreenTest 1).

### REPORT
| Field | Content |
|---|---|
| Done | #25 toolchain bump (PR #31); #26 compileSdk 37 + androidx (PR #32); #16 app lock (PR #33) |
| Merged | #31 yes `v2026-10-21-1`; #32 yes `v2026-10-21-2` (expected); #33 see notification |
| Failed or blocked | Nothing failed. No local Android build (CI only) |
| Roadmap | 7 open items + tech debt; no replan needed yet (replan when under 5) |
| Model | Opus 5.5. ACTION FOR ALPHA: switch the routine to Claude Fable 5.1 if your plan includes it without an upgrade |
| Needs approval | Model; phone checks 5h (Nearby bump + app lock, new), 5g, 5f, #22, #17/#19, #15, #9; Dependabot authorship; release APK unrun; backup; signing; route strip |
| Next | #17 disappearing messages, #18 contact nicknames, #19 battery modes |

---

## 2026-10-20 — daily run (cloud routine)

**Model:** Opus 5.5 (`claude-opus-5-5`). The models page still lists Claude Fable 5.1 above
it and nothing newer. This session has no RemoteTrigger tool, so it cannot switch, and the
ACTION FOR ALPHA stays open (NEEDS_APPROVAL #7).

- Main CI was green at the start (`d4873f4`, tag `v2026-10-19-3`). There is still no Android
  SDK here. The protocol package and its tests (169, including the new `QuoteTest`) were run
  locally in a JVM-only Gradle project. Everything else was checked by CI.
- **Dependabot, first Monday.** Four PRs. Only Actions (#24) was green. Its bumps were
  re-applied as **PR #28** (checkout v7, setup-java v6, setup-gradle v6, upload-artifact v7,
  which clears the Node 20 warning). Merged, `v2026-10-20-1`; #24 closed with a link.
  The Gradle groups all failed in Lint, and they depend on each other. #25: AGP 9.4.1
  needs Gradle ≥ 9.6, and the wrapper is 9.5. #27: Nearby 19.5.1 is built with Kotlin
  2.4 metadata, and Kotlin is 2.2. #26: every androidx bump needs compileSdk ≥ 37. They
  are left open and became roadmap #25 (AGP + Gradle + Kotlin + KSP together, a major bump
  with its own PR) and #26 (compileSdk 37 + androidx).
- **PR #29** roadmap #14 part 2: reply with quote. The quote is a `> snippet` line inside the
  text, so the wire format is unchanged and older builds show it email style. A binary
  reference would have shown up there as garbage. Snippets are capped at 64 bytes on a code
  point boundary. Previews, notifications and Copy show only the answer. Green on the first
  attempt. Merged. Two-phone look in NEEDS_APPROVAL 5f.
- **PR #30** roadmap #15: panic wipe in Settings, with one confirm dialog, using
  `ActivityManager.clearApplicationUserData()`. That is the same as "Clear storage": the
  database, files, every prefs file and the app's Keystore keys, after which the process is
  killed. It uses no hand-kept list of files that a new prefs file could slip past. If the
  system refuses, the screen says nothing was erased. The app closes rather than restarting
  itself. Needs a real-phone check (NEEDS_APPROVAL 5g).
- Unit tests +14 (QuoteTest 10, PanicWipeTest 3, notification policy 1). Instrumented +4
  (QuoteBubbleTest 1, SettingsScreenTest 3).

### REPORT
| Field | Content |
|---|---|
| Done | Actions bumps from Dependabot (PR #28); #14 part 2 reply with quote (PR #29); #15 panic wipe (PR #30) |
| Merged | #28 yes `v2026-10-20-1`; #29 yes `v2026-10-20-2`; #30 see notification |
| Failed or blocked | Dependabot #25/#26/#27 fail CI, as they need each other plus compileSdk 37. Left open and moved to roadmap #25/#26. No local Android build (CI only) |
| Roadmap | 10 open items + tech debt (2 new: toolchain bump, compileSdk 37); no replan needed yet |
| Model | Opus 5.5. ACTION FOR ALPHA: switch the routine to Claude Fable 5.1 if your plan includes it without an upgrade |
| Needs approval | Model; phone checks 5g wipe (new), 5f reply (new), #22, #17/#19, #15, #9; Dependabot authorship; release APK unrun; backup; signing; route strip |
| Next | #25 toolchain bump (AGP 9.4 / Gradle 9.8 / Kotlin 2.4), #26 compileSdk 37 + androidx, #16 app lock |

---

## 2026-10-19 — daily run (cloud routine)

**Model:** Opus 5.5 (`claude-opus-5-5`). The models page still lists Claude Fable 5.1 above
it and nothing newer. This session has no RemoteTrigger tool, so it cannot switch →
ACTION FOR ALPHA stays open (NEEDS_APPROVAL #7).

- Main CI was green at the start (`3a5c997`, tag `v2026-10-18-4`). There is still no Android
  SDK (Google Maven is blocked). The pure-Kotlin `Tombstones` tests were run locally in a
  throwaway Gradle JVM project (9/9). Everything else was checked by CI.
- **PR #21** roadmap #12: release builds now run R8 (`optimization.enable = true`, the AGP 9.3
  DSL, so the default rules are included). `src/main/keepRules/nchat.keep` only has
  `-dontwarn` lines for optional annotations, plus line numbers. CI now builds an unsigned
  `assembleRelease` (R8 took about 75 s), writes APK sizes to the job summary and uploads
  `mapping.txt`. Green on the first attempt. Merged, `v2026-10-19-1`. The release APK has
  not been run on a phone (NEEDS_APPROVAL #9).
- **PR #22** roadmap #14 part 1: a long-press menu with Resend, Copy and Delete for me.
  Delete asks first and is not offered for our own queued messages, since those still go
  out. A deleted id becomes a 7-day tombstone (prefs, capped at 1000), and the coordinator
  drops replays of it, because relay forward queues (24 h) outlive the 10-minute
  seen-cache. A Room table would have needed a migration, and the schema hash cannot be
  computed here without Room's sources. Green on the first attempt. Merged, `v2026-10-19-2`.
  Needs two-phone check. Reply with quote stays open as #14 part 2.
- **PR #23** roadmap #13: `.github/dependabot.yml`, weekly on Mondays at 04:00 Nairobi time.
  Gradle is grouped as build-tooling (AGP/Kotlin/KSP/Hilt together), androidx and
  libraries; GitHub Actions is one group. Merging Dependabot's own PRs would break the
  authorship rule, so CLAUDE.md now says to re-apply the bumps as alpha's commit
  (NEEDS_APPROVAL #8). CI also warns that Node 20 actions are deprecated, so the first
  Actions update is due anyway.
- Unit tests +12 (TombstonesTest 9, DeleteRuleTest 3). Instrumented +2 (MessageRepositoryTest).

### REPORT
| Field | Content |
|---|---|
| Done | #12 R8 release build in CI (PR #21); #14 part 1 copy + delete for me (PR #22); #13 Dependabot (PR #23) |
| Merged | #21 yes `v2026-10-19-1`; #22 yes `v2026-10-19-2`; #23 see notification |
| Failed or blocked | Nothing failed; all green on the first attempt. No local Android build (CI only) |
| Roadmap | 10 open items + tech debt; no replan needed yet (replan when under 5) |
| Model | Opus 5.5. ACTION FOR ALPHA: switch the routine to Claude Fable 5.1 if your plan includes it without an upgrade |
| Needs approval | Model; two-phone checks #22 (new), #17/#19, #15, #9; Dependabot authorship (new); release APK unrun (new); backup; signing; route strip |
| Next | #14 part 2 reply with quote, #15 panic wipe, #16 app lock |

---

## 2026-10-18 — daily run (cloud routine)

**Model:** Opus 5.5 (`claude-opus-5-5`). The docs still list Claude Fable 5.1 above it.
This session has no RemoteTrigger tool, so it cannot switch → ACTION FOR ALPHA stays
open (NEEDS_APPROVAL #7).

- Main CI was green at the start (`82a3183`, tag `v2026-10-16-3`). There is still no Android SDK
  (dl.google.com is blocked). New this run: the pure-Kotlin protocol code and its tests were
  compiled and run locally with kotlinc 2.2 + JUnit 5 from Maven Central. The rest relied on CI.
- **PR #17** roadmap #10: FAILED was already shown in the bubble. What was new:
  (a) PENDING rows older than the queue's 24 h lifetime become FAILED (at start and every 15 min).
  Before this they read `queued` forever.
  (b) Tap or long-press a failed bubble to resend. The resend is a new message that replaces the
  failed row in one transaction. A fresh id is used because fragment ids derive from the message
  id and would hit seen-caches.
  Green on the first attempt. Merged, `v2026-10-18-1`. Needs two-phone check.
- **PR #18** roadmap #11 part 1: a new `PROFILE` packet type (6) carries the display name.
  It is not an ANNOUNCE field, because every existing build parses ANNOUNCE as exactly 64 bytes.
  Names are normalised (no control, bidi or zero-width characters, ≤48 bytes) and stored on the
  existing `peers.displayName` column, so there is no migration. The peer list shows `name · id`.
  Attempt 1 failed one unit test: `PacketCodecTest` used 6 as its "unknown packet type" example.
  Fixed on the branch, and this time the whole protocol suite (155 tests) was run locally first.
  Merged, `v2026-10-18-2`.
- **PR #19** roadmap #11 part 2: Settings destination (fourth), own name in prefs, PROFILE sent
  after each ANNOUNCE and on change. Green on the first attempt. Merged, `v2026-10-18-3`.
  Needs two-phone check.

### REPORT
| Field | Content |
|---|---|
| Done | #10 failed state + resend (PR #17); #11 display names (PRs #18, #19) |
| Merged | #17 yes `v2026-10-18-1`; #18 yes `v2026-10-18-2`; #19 yes `v2026-10-18-3` |
| Failed or blocked | #18 failed CI once (a stale test assumed type 6 was unknown), fixed on the second attempt. Nothing blocked. No local Android build (CI only) |
| Roadmap | 12 open items + tech debt; no replan needed |
| Model | Opus 5.5. ACTION FOR ALPHA: switch the routine to Claude Fable 5.1 if your plan includes it without an upgrade |
| Needs approval | Model; two-phone checks for #17/#19 (new), #15, #9; backup; signing; route strip |
| Next | #12 release build hardening (R8), #13 Dependabot, #14 message actions |

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
