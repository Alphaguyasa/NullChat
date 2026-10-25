# Null Chat roadmap

Prioritized backlog. Work top-down; the daily run takes up to 3 items.
Priority: **P0** broken / security · **P1** high value · **P2** solid improvement · **P3** nice to have.
Effort: **S** < 2 h · **M** half a day · **L** a day or more (split into several PRs).

Audit of 2026-10-14; **replanned 2026-10-22** (second audit: bugs, security,
battery, UX, tests, features). Items 27–41 come from that audit, with the evidence in
DAILY_LOG 2026-10-22. Bugs come first: the most common failures of a phone with no
network are a wrong clock and leaving the app mid-send.

## Open

| # | Item | Pri | Effort | Notes |
|---|---|---|---|---|
| 31 | **Forget peer** (part 3; delete chat shipped in #41, block sender in #43). Remove a contact's row, key and chat in one step. Careful: `PublicKeyDirectory` is first-wins, so forgetting a key lets the next ANNOUNCE for that id set a new one; needs a design note before code. | P2 | S | |
| 34 | **Bundle the fonts**: `GoogleFont.Provider` needs internet. On a phone that was never online the fingerprints render in a proportional font. Ship the OFL fonts in `res/font`. | P2 | S | APK +~300 KB |
| 35 | **`InboundHandler` extraction + JVM tests** for the inbound path (collectIncoming, fragments, offerQueued, releaseHeld). Replaces the tech-debt line. | P2 | M | |
| 36 | **Message info** on long-press: sent time, received time, state, hops, sender id. | P2 | S | |
| 37 | **Search in a chat** (LIKE over the body, highlighted). | P2 | M | |
| 38 | **Share into Null Chat / forward a message** (`SEND text/plain` + peer picker). | P2 | M | No wire change |
| 39 | **Disappearing messages, part 2**: tell the other phone the timer (new packet type, old builds ignore it). | P2 | M | Wire change |
| 40 | **Carry others' mail**: offer queued unicast to any new neighbour (capped), not only to the recipient, so a chain of phones can deliver. | P2 | M | Needs two-phone check |
| 20 | **Tablet / landscape layout**: list + chat side by side on wide screens. | P2 | M | |
| 21 | **Group channels** with a shared key, invite by QR. | P3 | L | |
| 22 | **Voice notes** (≤10 s Opus over Fragmenter). | P3 | L | |
| 23 | **Route strip**: show relay chain on tap. Privacy trade-off — see NEEDS_APPROVAL. | P3 | M | |
| 42 | **Link challenge**: a stranger in range of a contact can still relay the contact's live ANNOUNCE onto its own link and be bound (see `LinkBindings`). Close it with a nonce the link must sign (new packet type). | P3 | M | Wire change, needs two-phone check |
| 41 | **Export a chat to a file** (system file picker, text, never automatic). | P3 | M | |

**Waiting for alpha:** keeping chat history out of Google backup (NEEDS_APPROVAL #3).
The audit found it also undoes panic wipe and disappearing messages. It is an S-sized
change once decided.

### Tech debt (slot into light days)

- Stale KDoc: `KeyManager` says the X25519 key is "unused until SessionCipher lands". (`MeshCoordinator`'s `flushPending`/`TESTING.md` references were fixed in #9.)
- Receiving side still accepts plaintext direct messages from older builds (`openedBody` fallback). Once every phone runs a build with #9, consider rejecting them.
- Delete `ExampleUnitTest` and `ExampleInstrumentedTest`.
- `MeshCoordinator` is now about 1250 lines; see roadmap #35.
- `MainActivityPreview` composes `MeshChatScreen`, which needs Hilt ViewModels; replace with a stateless preview.

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
