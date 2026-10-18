# Null Chat roadmap

Prioritized backlog. Work top-down; the daily run takes up to 3 items.
Priority: **P0** broken / security · **P1** high value · **P2** solid improvement · **P3** nice to have.
Effort: **S** < 2 h · **M** half a day · **L** a day or more (split into several PRs).

Audit of 2026-10-14. Baseline: 231 JVM unit tests passing; instrumented tests
exist but only run on a physical phone.

## Open

| # | Item | Pri | Effort | Notes |
|---|---|---|---|---|
| 11 | **Settings screen + display name.** Fourth destination; name carried in a PROFILE packet (type 6, not an ANNOUNCE field: old builds parse ANNOUNCE as exactly 64 bytes), shown on radar and peer list. | P1 | L | Part 1 (#18): PROFILE protocol + receive/store/show. Part 2: settings screen, own name, send PROFILE after ANNOUNCE. |
| 12 | **Release build hardening.** Enable R8 with keep rules for Tink/Nearby/Room/Hilt, measure APK size, add `assembleRelease` (unsigned) to CI. | P1 | M | No keystore in repo. |
| 13 | **Dependabot** for Gradle and GitHub Actions (weekly, grouped). | P2 | S | Free. |
| 14 | **Message actions.** Long-press: copy, delete locally, reply with quote. | P2 | M | |
| 15 | **Panic wipe.** Delete keys, database and attachments, then restart as a new identity. | P2 | M | Deletes user data on request only; confirm dialog. |
| 16 | **App lock.** Optional biometric/device-credential lock. | P2 | M | |
| 17 | **Disappearing messages** per conversation, swept by WorkManager. | P2 | M | |
| 18 | **Contact nicknames** (local rename). | P2 | S | |
| 19 | **Battery modes** over `DutyCycle`, auto-saver when system battery saver is on. | P2 | M | |
| 20 | **Tablet / landscape layout**: list + chat side by side on wide screens. | P2 | M | |
| 21 | **Group channels** with a shared key, invite by QR. | P3 | L | |
| 22 | **Voice notes** (≤10 s Opus over Fragmenter). | P3 | L | |
| 23 | **Route strip**: show relay chain on tap. Privacy trade-off — see NEEDS_APPROVAL. | P3 | M | |

### Tech debt (slot into light days)

- Stale KDoc: `KeyManager` says the X25519 key is "unused until SessionCipher lands". (`MeshCoordinator`'s `flushPending`/`TESTING.md` references were fixed in #9.)
- Receiving side still accepts plaintext direct messages from older builds (`openedBody` fallback). Once every phone runs a build with #9, consider rejecting them.
- Delete `ExampleUnitTest` and `ExampleInstrumentedTest`.
- `MeshCoordinator` is 745 lines; extract inbound handling (`InboundHandler`) once #1 and #2 land.
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
