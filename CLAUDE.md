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
ttl          u8    // starts at 6, decremented per hop
timestamp    u64   // epoch millis
payloadLen   u16
payload      bytes // encrypted
signature    64B   // Ed25519 over all preceding bytes
```

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
`BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`,
`ACCESS_FINE_LOCATION` (SDK < 31), `NEARBY_WIFI_DEVICES` (SDK 33+),
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`.

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
