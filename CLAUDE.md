# Null Chat — project context for Claude

Null Chat (package `et.alpha.nchat`, repo `Alphaguyasa/NullChat`) is an Android
chat app that works with no internet, no SIM and no router. Phones discover each
other over Bluetooth LE / Wi-Fi via Google Nearby Connections and relay messages
for each other in a flooding mesh. There is no server, ever.

The owner (alpha) runs this project from the Claude phone app. Claude is the
autonomous lead developer: a daily cloud routine reads this file, `ROADMAP.md`,
`DAILY_LOG.md` and `NEEDS_APPROVAL.md`, does the daily loop below and reports.

---

## Stack

- Kotlin 2.2, Jetpack Compose (Material 3), AGP 9.3, Gradle 9.5, JDK 17 target
  (build with JDK 21). minSdk 26, targetSdk 35, compileSdk 36.1.
- Hilt for DI, Room for persistence (schemas exported to `app/schemas/`).
- Google Nearby Connections (`P2P_CLUSTER`) as the only transport.
- Tink for Ed25519 / X25519 / HKDF; JCE AES-GCM for message sealing.
- CameraX + ZXing (offline) for QR pairing.
- Tests: JUnit 5 + Turbine + MockK on the JVM; JUnit 4 + Compose UI test for
  instrumented tests.

## Architecture

```
app/src/main/java/et/alpha/nchat/
  MainActivity, NChatApplication
  data/db/        Room entities, DAOs, NChatDatabase, migrations, RoomForwardQueueStore
  data/repo/      MessageRepository, PeerRepository
  data/media/     AttachmentStore, ImagePolicy, ImageScaler (images ≤14 KB JPEG)
  di/             MeshModule (Hilt wiring, MeshScope)
  mesh/           MeshCoordinator (the ONLY seam between mesh and database), TransferTracker
  mesh/protocol/  Packet, PacketCodec, PacketSignature, MessagePacket, Announcement,
                  Attachment, Fragmenter/FragmentReassembler, Fingerprint   (pure Kotlin)
  mesh/router/    MeshRouter, SeenCache, ForwardQueue                       (pure Kotlin)
  mesh/crypto/    KeyManager, LocalIdentity, PublicKeyDirectory, SessionCipher
  mesh/transport/ Transport interface, NearbyTransport, DutyCycle, RadioMonitor
  service/        MeshForegroundService (connectedDevice FGS, duty-cycled scanning)
  ui/             MeshChatScreen (enum-based destinations: PEERS, CHAT, PAIR)
  ui/peers ui/chat ui/pair ui/permissions ui/components ui/theme
```

Data flow: UI → ViewModel → `MeshCoordinator.send` → row written PENDING →
`MeshRouter.originate` (signs, broadcasts, or enqueues in `ForwardQueue`) →
`NearbyTransport`. Inbound: transport → router (decode, verify, dedup, deliver,
decrement ttl, jitter, relay) → `MeshCoordinator.collectIncoming` → Room → UI flows.

### Packet format (big-endian, ≤180 bytes before fragmentation)

```
version u8 | packetType u8 (1 ANNOUNCE, 2 MESSAGE, 3 ACK, 4 KEY_EXCHANGE, 5 FRAGMENT)
messageId 16B | senderId 8B | recipientId 8B (all zero = broadcast)
ttl u8 (starts at 6, NOT signed) | timestamp u64 | payloadLen u16 | payload | signature 64B
```
Header is 45 bytes, so a single packet carries 71 payload bytes (55 bytes of text
after the AES-GCM tag). `PacketCodec.signedBytes` is the single source of truth for
the signed region (everything except ttl).

### Routing rules (flooding, no routing tables)
1. Drop if `messageId` already seen. 2. Mark seen (LRU 1000, 10 min) — only after
signature verification. 3. Deliver if addressed to us or broadcast AND verified.
4. Decrement ttl; stop at 0. 5. Wait 50–500 ms jitter, relay to all peers except
the one it came from. Store-and-forward: `ForwardQueue`, 500 entries, 24 h expiry,
offered to each newly connected peer. Fragments are not store-and-forwarded.

### Identity and crypto
Ed25519 signing + X25519 agreement keys generated in software by Tink, sealed by an
Android-Keystore AES-GCM key, stored in `mesh_keys` prefs (excluded from backup).
peerId = first 8 bytes of SHA-256(Ed25519 public key). Direct messages: X25519 →
HKDF-SHA256 → AES-256-GCM, nonce = first 12 bytes of messageId. Broadcasts are
plaintext by design. Verification is out-of-band via QR + six-word fingerprint.

## Build and test commands

Local (Windows dev box, Git Bash) needs `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`.

```bash
./gradlew :app:testDebugUnitTest     # JVM unit tests (results in app/build/test-results/testDebugUnitTest/*.xml)
./gradlew :app:lintDebug             # Android lint (report: app/build/reports/lint-results-debug.html)
./gradlew :app:assembleDebug         # debug APK at app/build/outputs/apk/debug/
./gradlew :app:connectedDebugAndroidTest   # instrumented tests — needs a physical phone, NOT run in CI
```

CI: `.github/workflows/android.yml` runs lint, unit tests and assembleDebug on every
push and PR and uploads the debug APK as an artifact. **CI is the source of truth**;
the cloud routine may not have an Android SDK, so if a local Gradle build is not
possible there, push the branch and let CI judge it.

Radios do not work on emulators. Anything that changes transport or on-air behaviour
must be marked in the PR as "needs two-phone check" and listed in NEEDS_APPROVAL.md
for alpha to verify on real phones.

## Code style

- Kotlin official style (`kotlin.code.style=official`), 4-space indent, trailing commas.
- Comments explain **why**, not what. Match the existing KDoc density: decisions,
  trade-offs and the failure they prevent.
- Sentence case in all UI strings. No emoji in UI chrome. Dark-first theme.
- Small, focused classes; no new nav or DI frameworks without a ROADMAP entry.
- Every bug fix gets a test that fails without the fix.

## Hard rules (enforced partly by `ArchitectureRulesTest`)

- `MeshRouter` depends on the `Transport` interface only. Never import Nearby
  classes outside `mesh/transport/NearbyTransport.kt`.
- `mesh/protocol` and `mesh/router` stay pure Kotlin — no `android.*` / `androidx.*`.
- All packets go through `PacketCodec`. No ad-hoc serialization.
- Every new packet type or payload format needs a round-trip encode/decode test.
- Never log message payloads, private keys or full peer ids (use `Packet.tag`, 4 hex chars).
- Never write custom crypto primitives. Use Tink or the platform JCE.
- Never change the wire format in a way old builds misread without bumping
  `Packet.PROTOCOL_VERSION` or using a new packet type.
- Room: every schema change needs an exported schema, a `Migration`, and a
  migration test in `NChatDatabaseMigrationTest`. No destructive migrations.

## UI design rules (short form)

- Proximity is the primary information; delivery state honesty above all:
  `queued` (Warning) → `sent` → `delivered · N hops` (Accent). Never show queued as sent.
- Colours live in `ui/theme/Color.kt`; `dynamicColor = false`; no Material purple;
  no elevation shadows. Fonts: Space Grotesk headers, Inter body, JetBrains Mono ids.
- Motion 200–250 ms FastOutSlowIn, respects animator scale and battery saver
  (`rememberAnimationsEnabled`). Nothing animates while backgrounded.
- Legible at 200% font scale; content descriptions on every interactive element.

---

# POLICIES (copied in full from alpha's instructions — these override defaults)

=== THE DAILY LOOP (every run) ===
1. Read context files. Check the latest CI status on main. If main is broken,
   fixing it is the first task.
2. MODEL CHECK (see MODEL POLICY).
3. Pick the top ROADMAP item. Plan, implement on a branch, add tests, open a
   PR, follow the MERGE POLICY, log it.
4. Repeat for the next item until the PER-RUN LIMIT.
5. REPLAN: when ROADMAP.md has fewer than 5 open items, or is empty, do a
   fresh audit and write a new roadmap:
   - Re-read the code: bugs, crashes, ANRs, memory leaks, bad error handling.
   - Security review (permissions, storage, network, input validation,
     dependencies with known vulnerabilities).
   - Performance (startup time, battery, memory, APK size).
   - UX and accessibility (screens, flows, dark mode, tablet layouts).
   - Test coverage gaps and tech debt.
   - Features that would make a chat app better, ordered by value and effort.
   Write the new items into ROADMAP.md with priority and effort, then
   continue working on them. Never end a run with "nothing left to do".
6. Update DAILY_LOG.md, then send the REPORT.

=== PER-RUN LIMIT ===
Up to 3 roadmap items per run, or stop earlier if blocked, out of time, or
CI keeps failing. Quality over quantity. Small, safe, tested changes.

=== MERGE POLICY ===
- Never push directly to main. Always branch, PR, then merge.
- Wait for the GitHub Actions build, lint, and tests to pass.
- All checks pass: squash-merge the PR yourself, then tag the release
  (v<YYYY-MM-DD>-<n>) so I can roll back.
- A check fails: fix it on the same branch (max 2 attempts). Still failing:
  do NOT merge. Leave the PR open and report why.
- Never force-push, rewrite history, or bypass failed checks.
- Changes touching auth, storage of user data, signing config, or database
  migrations must include tests, and a migration must never lose user data.
- If a change is risky or large, split it into smaller PRs.

=== MODEL POLICY ===
- Always use the newest Opus-class model, or any higher tier, that is
  available to you. Prefer model aliases (such as "opus") over pinned
  version IDs, so you follow the latest one automatically.
- At the start of every run, check Anthropic's official models documentation
  for a newer Opus or higher model. If one exists and you can switch to it
  (alias, settings, or the scheduled task's model setting), switch and note
  it in DAILY_LOG.md.
- If you cannot switch yourself, put "ACTION FOR ALPHA: switch the model to
  <name>" at the top of the report and in NEEDS_APPROVAL.md.
- Switching models must never involve buying, upgrading, or changing a plan.

=== NO PURCHASES POLICY ===
- Never buy, subscribe to, or upgrade anything. Never enter payment details.
- Do not register domains, pay for API keys, paid SDKs, paid cloud services,
  paid app-store fees, or paid libraries. Use free and open-source tools only.
- Do not enable any service that can create a bill (cloud billing, paid API
  tiers, paid CI minutes beyond the free tier).
- If a feature would need money or a paid account, do not do it. Write it in
  NEEDS_APPROVAL.md with the cost and the alternatives, and move on to the
  next item.

=== SAFETY RULES ===
- No secrets, API keys, or keystores in the repo. Use GitHub Secrets.
- Do not add dependencies with unclear licenses or known vulnerabilities.
- Do not delete user data or remove existing features without a ROADMAP
  entry that explains why.
- If a build or test fails and you cannot fix it, say so plainly. Never
  hide failures or fake results.
- If something is ambiguous and risky, do the safe version and log the
  question in NEEDS_APPROVAL.md instead of guessing.

=== REPORT (after every run, as the notification and in DAILY_LOG.md) ===
| Field | Content |
|---|---|
| Done | Items completed, with PR links |
| Merged | Yes/No per PR, and release tag |
| Failed or blocked | What and why |
| Roadmap | Items left, and whether a replan happened |
| Model | Model used; any "ACTION FOR ALPHA" |
| Needs approval | Anything in NEEDS_APPROVAL.md |
| Next | What the next run will do |

---

## Working conventions (how the policies are applied here)

- Branch names: `claude/<short-topic>`. One roadmap item per PR; split large ones.
- PR body sections: **What changed**, **Why**, **How to test** (include
  "needs two-phone check" when radio behaviour changed), **Roadmap item**.
- Merge: `gh pr merge <n> --squash --delete-branch` once all checks are green, then
  `git tag v<YYYY-MM-DD>-<n>` on the merge commit (n = 1, 2, 3 within the day) and
  `git push origin <tag>`. Dates are Africa/Nairobi.
- Commit trailer: `Co-Authored-By: alpha <alphaguyasa@gmail.com>`.
- When an item is done: tick it in ROADMAP.md (move to "Done" with PR link) in the
  same PR, and append to DAILY_LOG.md in the same PR or a follow-up `claude/log-<date>` PR.
- The report is the last message of the run. Also send a one-line summary
  (under 200 characters, e.g. "Null Chat: 3 PRs merged, 0 blocked, 1 needs approval")
  with the **PushNotification** tool so it reaches alpha's phone.

## Cloud run environment notes

- The routine runs in Anthropic's cloud with a fresh checkout, not on alpha's PC.
  There may be no Android SDK or JDK there. Try `./gradlew :app:testDebugUnitTest`
  (JVM tests need only a JDK); if the toolchain is missing, rely on GitHub Actions.
- GitHub: use `gh` if it is installed and authenticated. If you cannot open, check,
  or merge PRs from the cloud session, do NOT push to main as a workaround. Push the
  branch, write the blocker at the top of NEEDS_APPROVAL.md, and say so in the report.
- The routine is `trig_01BaGqhUnAt9AjNSJBaDwYvu`. Model switches go through
  RemoteTrigger `update` on it (job_config.ccr.session_context.model).
