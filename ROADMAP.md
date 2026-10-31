# Null Chat roadmap

Prioritized backlog. Work top-down; the daily run takes up to 3 items.
Priority: **P0** broken / security · **P1** high value · **P2** solid improvement · **P3** nice to have.
Effort: **S** < 2 h · **M** half a day · **L** a day or more (split into several PRs).

Audit of 2026-10-14; replanned 2026-10-22 (items 27–41); **replanned 2026-10-28**
(third audit, items 43–54, evidence in DAILY_LOG 2026-10-28). This round's theme is a
hostile or crowded mesh: what one modified phone, or a festival's worth of strangers,
can make this phone hold, drop or show. Bugs and abuse resistance come before features.

## Open

| # | Item | Pri | Effort | Notes |
|---|---|---|---|---|
| 54 | **Duty cycle under Doze**: no wake lock or alarm, so `delay` in the duty cycle may stall when the phone dozes. Measure on a phone first; fix only with evidence. | P3 | S | Needs phone evidence |
| 42 | **Link challenge**: a stranger in range of a contact can relay the contact's live ANNOUNCE onto its own link and be bound (see `LinkBindings`). A plain nonce does not stop a live relay; sign Nearby's per-connection raw authentication token instead, which differs on each of the relay's two links. New packet type. | P3 | M | Wire change, needs two-phone check |
| 21 | **Group channels** with a shared key, invite by QR. | P3 | L | |
| 22 | **Voice notes** (≤10 s Opus over Fragmenter). | P3 | L | |
| 23 | **Route strip**: show relay chain on tap. Privacy trade-off — see NEEDS_APPROVAL. | P3 | M | |

**Waiting for alpha:** keeping chat history out of Google backup (NEEDS_APPROVAL #3).
The audit found it also undoes panic wipe and disappearing messages. It is an S-sized
change once decided.

### Tech debt (slot into light days)

- Receiving side still accepts plaintext direct messages from older builds (`openedBody` fallback). Once every phone runs a build with #9, consider rejecting them.
- `MeshCoordinator` is about 1200 lines. The send-side glue now has JVM tests (`sendHeld`, `markFlushed`, #72); an `OutboundSender` class split is still open if the file keeps growing.

## Done

| Date | Item | PR | Tag |
|---|---|---|---|
| 2026-10-14 | #1 Delivery receipts (ACK) | #2 | v2026-10-14-2 |
| 2026-10-14 | #2 Long text messages (up to 1000 bytes) | #3 | v2026-10-14-3 |
| 2026-10-14 | #3 Undecryptable message placeholder | #4 | v2026-10-14-4 |
| 2026-10-15 | #4 Instrumented tests in CI (API 34 emulator, 100 tests) | #7 | — (see NEEDS_APPROVAL, resolved) |
| 2026-10-15 | #5 Persist peers' agreement keys (Room 3→4) | #8 | v2026-10-15-2 |
| 2026-10-15 | #6 No plaintext direct messages (held until keyed) | #9 | v2026-10-15-3 |
| 2026-10-15 | #24 Release tags from CI (`release-tag.yml`) | #11, #12 | v2026-10-15-5 |
| 2026-10-16 | #7 Hop count on delivered messages (Room 4→5) | #14 | v2026-10-16-1 |
| 2026-10-16 | #8 Incoming message notifications (direct messages) | #15 | v2026-10-16-2 |
| 2026-10-16 | #9 Unread counts by local receive time (Room 5→6) | #16 | v2026-10-16-3 |
| 2026-10-18 | #10 Failed state + resend; queued rows expire with the queue | #17 | v2026-10-18-1 |
| 2026-10-18 | #11 Settings screen + display names (PROFILE packet, type 6) | #18, #19 | v2026-10-18-2, v2026-10-18-3 |
| 2026-10-19 | #12 Release build: R8 on, unsigned `assembleRelease` + APK size in CI | #21 | v2026-10-19-1 |
| 2026-10-19 | #14 part 1: copy and delete-for-me (tombstones stop replays) | #22 | v2026-10-19-2 |
| 2026-10-19 | #13 Dependabot (Gradle + Actions, weekly, grouped) | #23 | see log |
| 2026-10-20 | Dependabot Actions bumps re-applied (Node 24 majors) | #28 | see log |
| 2026-10-20 | #14 part 2: reply with quote (`> snippet` line, no wire change) | #29 | see log |
| 2026-10-20 | #15 Panic wipe (Settings → confirm → `clearApplicationUserData`) | #30 | see log |
| 2026-10-21 | #25 Toolchain bump: AGP 9.4.1, Gradle 9.8.1, Kotlin 2.4.21, KSP 2.3.12 + Dependabot #27 libraries | #31 | see log |
| 2026-10-21 | #26 compileSdk 37 + androidx (core 1.19.1, Compose BOM 2026.09.00, hilt-navigation-compose 1.4.0, Room 2.8.5) | #32 | see log |
| 2026-10-21 | #16 App lock (system fingerprint / face / screen lock, 60 s grace) | #33 | see log |
| 2026-10-22 | #18 Contact nicknames (local only, Room 6→7) | #34 | see log |
| 2026-10-22 | #17 Disappearing messages, part 1: local timer per chat (Room 7→8; in-process sweep, not WorkManager — see PR) | #35 | see log |
| 2026-10-22 | #19 Battery modes (automatic / normal / saver over `DutyCycle`) | #36 | see log |
| 2026-10-23 | #27 Sends survive leaving the chat (mesh scope; cancelled transfers fail the row) | #37 | see log |
| 2026-10-23 | #28 Bound received images (refuse over 14 KB; two-pass decode under 1024 px) | #38 | see log |
| 2026-10-23 | #29 Order by this phone's clock (`receivedAt`; future threads pulled back at start; no schema change) | #39 | see log |
| 2026-10-24 | #30 A link belongs to a peer only after a verified ANNOUNCE on it (`LinkBindings`) | #40 | see log |
| 2026-10-24 | #31 part 1: Delete chat (thread menu; own queued messages and the timer stay; tombstoned) | #41 | see log |
| 2026-10-24 | #32 Router: duplicates dropped before the Ed25519 check; inbound ttl clamped to 6 | #42 | see log |
| 2026-10-25 | #31 part 2: Block sender (Room 8→9; dropped before storing, not acknowledged, still relayed) | #43 | see log |
| 2026-10-25 | #33 App lock hides notification text ("New message" only, sender name too) | #44 | see log |
| 2026-10-25 | #34 Bundle the fonts (static 400/500 OFL subsets in `res/font`; Play Services font download removed) | #45 | see log |
| 2026-10-26 | Dependabot #46 re-applied (Tink 1.24.0, Gradle 9.8.2, test-only kotlinx-serialization 1.12.0) | #47 | see log |
| 2026-10-26 | #36 Message info (long-press → Info: state and hops, sender id, both clocks, timer) | #48 | see log |
| 2026-10-26 | #31 part 3: Forget contact (thread, row and queued sends removed; keys kept, since the id commits to them; not while blocked) | #49 | see log |
| 2026-10-26 | #35 `InboundHandler`: the inbound path out of `MeshCoordinator`, behind an `InboundStore`; 18 JVM tests | #50 | see log |
| 2026-10-26 | #37 Search in a chat (⋮ → Search; in memory, not `LIKE`, so case folds in every script; matches tinted and bold; ↑/↓ wrap) | #51 | see log |
| 2026-10-26 | #38 Share into Null Chat (`SEND text/plain`) and Forward (text): the peer list picks the chat, the text lands in its box, never sent unseen | #52 | see log |
| 2026-10-27 | #39 Disappearing messages, part 2: TIMER packet (type 7, sealed, store-and-forwarded); last change wins, ordered by the sender's clock with a 7-day replay window | #53 | see log |
| 2026-10-27 | #40 Carry others' mail: each new neighbour is handed up to 32 queued packets for other recipients (copies; newest first; never twice to the same neighbour) | #54 | see log |
| 2026-10-27 | #20 Two panes from 840dp window width (list a third, 320–420dp; chat or "Pick a chat" beside it; pairing and settings stay full-window) | #55 | see log |
| 2026-10-28 | #41 Export a chat (⋮ → Export chat → warning → system file picker; plain text, images as `[image]`, bubble times and delivery words) | #56 | see log |
| 2026-10-28 | #43 A failure in the mesh never crashes the app (scope handlers; inbound, key release, sweep and notifications caught per item) | #57 | see log |
| 2026-10-28 | #44 part 1: a packet whose sender we could not verify is relayed live but never held in the forward queue | #58 | see log |
| 2026-10-28 | #44b Evicted means failed: the full forward queue reports what its cap drops, and our own still-queued rows among them turn `failed` at once | #59 | see log |
| 2026-10-28 | #45 Held messages always leave: pending rows looked up per chat (no 100-row cap); one release pass over every keyed chat after start-up warm-up, so more than 64 restored keys strand nothing | #60 | see log |
| 2026-10-28 | #46 Online and "1 hop" from bound links only: the coordinator publishes `boundPeers` (links that proved their id); chat subtitle and peer list use it, the "devices in range" count still counts every link | #61 | see log |
| 2026-10-29 | #47 Fragment bounds (non-final chunks full size; total ≤ ceil(64 KiB / chunk)) | #62 | see log |
| 2026-10-29 | #48 `TransferTracker` forgets: completed transfers leave at once (ids remembered, bounded, for stragglers), newest 32 failures kept, stalled incoming dropped after 60 s | #63 | see log |
| 2026-10-29 | #49 part 1: strangers unheard for 7 days with no thread, message, verification, nickname or block are pruned at start; hop readings capped at 256 peers | #64 | see log |
| 2026-10-29 | #49 part 2: key directory pins kept peers and holds ≤ 1024 strangers (LRU); peer list shows kept, online or heard-within-the-hour peers only | #65 | see log |
| 2026-10-29 | #50 Mute a chat: `conversations.isMuted` (Room 9→10), menu toggle, "muted" in the subtitle, notifier skips muted threads | #66 | see log |
| 2026-10-29 | #51 Full-screen image: tap to open, pinch zoom 1–4x, pan within the overhang, double tap resets | #67 | see log |
| 2026-10-30 | #52 part 1: local timer changes are stamped; a contact's TIMER sent more than 15 min before our own change no longer undoes it | #68 | see log |
| 2026-10-30 | #53 Peer list order: online first by name, then most recently heard; the open chat's row is marked in two panes | #69 | see log |
| 2026-10-30 | Tech debt: stale `KeyManager` KDoc fixed; template `Example*` tests and the Hilt-bound `MainActivityPreview` removed (the list and chat keep their stateless previews) | #70 | see log |
| 2026-10-31 | #52 part 2: timer change lines in the thread (Room 10→11, `timer_changes`) | #71 | see log |
| 2026-10-31 | Tech debt: `releaseHeld` / `offerQueued` glue extracted to `sendHeld` / `markFlushed` with JVM tests | #72 | see log |
