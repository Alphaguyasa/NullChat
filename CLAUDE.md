# Offline Mesh Chat — Project Context

An Android chat app that works with no internet, no SIM, no router. Phones discover each other over Bluetooth LE / Wi-Fi Direct and relay messages for each other in a mesh.

## Stack
- Kotlin, Jetpack Compose, min SDK 26, target SDK 35
- Room for persistence
- Google Nearby Connections API (`P2P_CLUSTER`) as the first transport
- Protocol Buffers or manual ByteBuffer codec for packets
- Ed25519 + X25519 via Tink or libsignal for identity and E2E encryption
- Foreground service for background mesh operation

## Module structure
```
app/
  data/db/        Room entities, DAOs, migrations
  data/repo/      MessageRepository, PeerRepository
  mesh/transport/ Transport interface, NearbyTransport, BleTransport
  mesh/protocol/  Packet, PacketCodec, Fragmenter
  mesh/router/    MeshRouter, SeenCache, ForwardQueue
  mesh/crypto/    KeyManager, SessionCipher
  service/        MeshForegroundService
  ui/             Compose screens: PeerList, Chat, Settings, QrPair
```

## Packet format
Wire format, big-endian, must fit in ~180 bytes before fragmentation.

```
version      u8
packetType   u8    // 1=ANNOUNCE 2=MESSAGE 3=ACK 4=KEY_EXCHANGE 5=FRAGMENT
messageId    16B   // UUID, used for dedup
senderId     8B    // first 8 bytes of SHA-256 of sender public key
recipientId  8B    // all zero = broadcast
ttl          u8    // starts at 6, decremented per hop. NOT covered by the signature
timestamp    u64   // epoch millis
payloadLen   u16
payload      bytes // encrypted
signature    64B   // Ed25519 over all preceding bytes EXCEPT ttl
```

The signature deliberately skips `ttl`. Every relay must decrement it, so signing
it would invalidate the signature on the first forward and make multi-hop
delivery unverifiable. The trade is that `ttl` is unauthenticated in transit;
`PacketCodec.signedBytes` is the single source of truth for the signed region.

## Routing rules
Flooding with deduplication. No routing tables.

1. On receive, if `messageId` is in the seen-set, drop silently.
2. Add `messageId` to the seen-set (LRU, 1000 entries, 10 minute expiry).
3. If `recipientId` is this device or broadcast, decrypt and deliver to UI.
4. Decrement `ttl`. If `ttl > 0`, rebroadcast to all connected peers EXCEPT the peer it arrived from.
5. Before rebroadcast, wait a random 50–500ms jitter to avoid broadcast storms.

## Store-and-forward
- Messages for unreachable recipients go into `ForwardQueue`.
- Queue is capped at 500 messages with 24 hour expiry. Evict oldest first.
- On each new peer connection, offer queued messages addressed to that peer.

## Fragmentation
Payloads exceeding the negotiated MTU are split into FRAGMENT packets carrying
`(messageId, index, total)`. Reassembly buffer times out after 60 seconds.

## Identity
- Ed25519 keypair generated on first launch, stored in Android Keystore.
- User ID = first 8 bytes of SHA-256 of the public key.
- Contacts verified out-of-band by QR code containing the full public key.
- Fingerprints displayed as a word list so users can compare them verbally.

## Hard rules
- `MeshRouter` depends on the `Transport` INTERFACE only. Never import Nearby
  Connections classes outside `mesh/transport/NearbyTransport.kt`.
- All packets are encoded and decoded through `PacketCodec`. No ad-hoc
  serialization anywhere else.
- Every new packet type requires a round-trip encode/decode unit test.
- Never log message payloads, private keys, or full peer IDs. Truncate peer IDs
  to 4 hex chars in logs.
- Assume worst-case MTU of 20 bytes. Always request a larger MTU, always handle
  the failure path.
- Protocol layer (`mesh/protocol`, `mesh/router`) must be pure Kotlin with no
  Android framework dependencies, so it is unit-testable on the JVM.
- Do not write custom cryptographic primitives. Use Tink or libsignal.

## Permissions required
`BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` (SDK 31+),
`ACCESS_FINE_LOCATION` (SDK <= 31, **inclusive**), `NEARBY_WIFI_DEVICES` (SDK 33+),
`POST_NOTIFICATIONS` (SDK 33+), `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_CONNECTED_DEVICE`.

Install-time (not runtime, so absent from the permission flow but still required):
`ACCESS_WIFI_STATE` and `CHANGE_WIFI_STATE`, both SDK <= 31. Nearby needs these
even for Bluetooth-only operation; without them `startDiscovery` fails with
`8032 MISSING_PERMISSION_ACCESS_WIFI_STATE` on every scan and the mesh silently
never finds anyone.

Android 12 (SDK 31) needs `ACCESS_FINE_LOCATION` *and* the three `BLUETOOTH_*`
permissions together — Nearby Connections refuses to advertise or discover if any
required permission is missing, so capping location at SDK 30 breaks the app on
Android 12 with no error. Google's sample manifest uses `maxSdkVersion="31"`.

`POST_NOTIFICATIONS` is requested but never treated as essential: without it the
mesh still runs, the user just cannot see the foreground service notification.
See `ui/permissions/MeshPermissions.kt`, which is the single source of truth.

## Testing notes
- BLE and Wi-Fi Direct do NOT work on emulators. Transport code must be
  verified on physical devices.
- Router and codec logic must be testable on the JVM with a fake in-memory
  Transport that simulates a multi-node topology.

## Definition of done for v1
Two physical Android phones in airplane mode with Bluetooth on discover each
other, exchange text messages, and chat history survives an app restart on both.

## Reference documentation
- Nearby Connections: https://developers.google.com/nearby/connections/overview
- Strategies: https://developers.google.com/nearby/connections/strategies
- Android BLE: https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview
- Bluetooth permissions: https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
- Wi-Fi Direct: https://developer.android.com/develop/connectivity/wifi/wifi-direct
- Foreground services: https://developer.android.com/develop/background-work/services/fgs
- Room: https://developer.android.com/training/data-storage/room
- Tink: https://developers.google.com/tink
- Android Keystore: https://developer.android.com/privacy-and-security/keystore
- Signal X3DH: https://signal.org/docs/specifications/x3dh/
- Reference implementation to study: https://github.com/permissionlesstech/bitchat




# Mesh Chat — UI design system

Append this to `CLAUDE.md`. Claude Code will apply it to every screen it builds.

## Design principle

This is not a WhatsApp clone. The whole point of the app is that there is no
server — the network is made of the people physically around you. The UI should
make that visible and make it feel like an instrument, not a social app.

Three rules that drive every decision:

1. **Proximity is the primary information.** Signal strength, hop distance, and
   who is relaying for whom are first-class UI, not buried in settings.
2. **Dark-first.** This app gets used at night, at protests, at festivals, in
   power cuts. Dark is the default theme, not an option.
3. **State honesty.** Never show a message as sent when it is only queued.
   Delivery state is the most important thing on the screen.

## Color tokens

Dark theme is primary. Define both in `ui/theme/Color.kt`.

```kotlin
// Dark (default)
val BgBase        = Color(0xFF0E1A26)  // app background
val BgSurface     = Color(0xFF16303F)  // cards, bubbles, sheets
val BgElevated    = Color(0xFF1D3D4F)  // dialogs, menus
val Accent        = Color(0xFF5DCAA5)  // mesh green — connection, outgoing
val AccentBright  = Color(0xFF9FE1CB)  // active/live state, icon strokes
val AccentDim     = Color(0xFF3D8A72)  // relay links, inactive nodes
val TextPrimary   = Color(0xFFE8F1EE)
val TextSecondary = Color(0xFF8FA8A5)
val TextMuted     = Color(0xFF5C7370)
val Warning       = Color(0xFFEF9F27)  // queued / store-and-forward
val Danger        = Color(0xFFE24B4A)  // unverified peer, failed delivery
val Verified      = Color(0xFF5DCAA5)

// Light
val BgBaseLight    = Color(0xFFF4F7F6)
val BgSurfaceLight = Color(0xFFFFFFFF)
val AccentLight    = Color(0xFF0F6E56)
val TextPrimaryL   = Color(0xFF0E1A26)
```

Use Material 3 `dynamicColor = false`. The brand identity matters more than
Material You theming here.

## Typography

- Display / headers: **Space Grotesk** (technical, slightly mechanical)
- Body and chat: **Inter**
- Peer IDs, fingerprints, hop counts: **JetBrains Mono**

Load via `androidx.compose.ui.text.googlefonts`. Sentence case everywhere, never
Title Case. Two weights only: 400 and 500.

## Signature screens

### Peer list — the radar
This is the screen that makes the app. Not a flat list of names.

- A live concentric-ring visualization at the top: this device at center, peers
  positioned by hop count, ring radius = hops away. Node size = signal strength.
- Peers pulse gently when a packet passes through them. Use
  `rememberInfiniteTransition` with slow, low-amplitude alpha animation. Subtle,
  not a disco.
- Below the radar, a conventional list for actually tapping into a chat.
- Empty state: rings drawn dim with a slow sweep animation and "searching for
  peers nearby". Never a sad-face illustration.

Draw the radar with Compose `Canvas`, not an image. Cap the animation at 30fps
and pause it when the screen is not resumed — battery matters in this app.

### Chat
- Outgoing bubbles `Accent` at 12% alpha with a 1dp `Accent` border, incoming
  bubbles solid `BgSurface`. Corner radius 18dp, with the tail corner at 4dp.
- Delivery state as a small monospace suffix, not checkmarks:
  `queued` (Warning) → `sent` → `2 hops` (TextMuted) → `delivered` (Accent).
  Showing hop count on delivered messages is the whole personality of the app.
- If a message travelled through relays, tapping it expands a thin route
  strip showing the anonymized relay chain: `you → a4f2 → 9c11 → maya`.
- Unverified contact: a persistent thin `Danger` bar at the top of the thread,
  not a dismissible dialog.

### QR pairing
- Full-bleed dark screen, QR rendered in `AccentBright` on `BgBase`.
- Fingerprint shown as six words in JetBrains Mono, large, spaced out, so two
  people can read them aloud to each other.

## Motion

- All transitions 200–250ms, `FastOutSlowInEasing`.
- Peer appearing: fade + scale from 0.9. Peer leaving: fade + scale to 0.95.
  Never slide — position on the radar carries meaning.
- Message send: bubble scales from 0.96 with a brief `AccentBright` border flash
  that decays as delivery state advances.
- Respect `Settings.Global.ANIMATOR_DURATION_SCALE` and disable animation when
  battery saver is on.

## Components to build in `ui/components/`

`MeshRadar`, `PeerNode`, `MessageBubble`, `DeliveryStateLabel`, `RouteStrip`,
`SignalBars`, `FingerprintWords`, `EmptyState`, `PermissionRationaleSheet`.

## Rules

- No Material default purple anywhere.
- No elevation shadows. Separate surfaces with 1dp `AccentDim` at 20% alpha.
- No emoji in UI chrome. Icons from Material Symbols outlined, 20dp inline.
- Every screen must be legible at 200% font scale and pass a contrast check
  against `BgBase`.
- Do not animate anything while the app is backgrounded.



