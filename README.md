# Null Chat

An Android chat app that works with no internet, no SIM card and no Wi-Fi router.
Phones find each other over Bluetooth and Wi-Fi (Google Nearby Connections) and
carry messages for each other, so a message can reach someone several phones away.
There is no server and no account: your identity is a key pair made on your phone.

## What it does

- **Direct messages**, end-to-end encrypted, with honest delivery states:
  `queued` (waiting for a route) → `sent` → `delivered · N hops`.
- **Everyone**: a public thread for every phone in range (not encrypted, by design).
- **Relaying**: each phone passes messages on for others, and holds them for up to
  24 hours for phones that are not in range yet.
- **Images** up to about 14 KB, sent in pieces across the mesh.
- **Pairing by QR code** and a six-word fingerprint to confirm who you are talking to.
- Disappearing messages, block and forget, app lock, panic wipe, battery modes.

## How the mesh works

```
  A ))) B ))) C          A cannot reach C directly.
  |               1. A signs the packet and sends it to every phone in range (B).
  |               2. B checks the signature, drops it if seen before, and
  |                  after a short random delay passes it to everyone but A.
  |               3. C receives it, and its receipt travels back the same way.
  └── every hop takes one off the packet's ttl (starts at 6), so it dies out.
```

- Flooding, with no routing tables: a packet is dropped if its id was already seen,
  and relayed only after its Ed25519 signature checks out.
- Packets are at most 180 bytes before fragmentation; longer text and images are
  split into fragments and reassembled.
- Store and forward: up to 500 packets for up to 24 hours, offered to each phone
  that connects.

## Security in brief

- Identity: an Ed25519 signing key and an X25519 agreement key, generated on the
  phone with Tink and sealed by an Android Keystore key. Your id is the first
  8 bytes of the SHA-256 of your public key.
- Direct messages: X25519 → HKDF-SHA256 → AES-256-GCM.
- Every packet is signed; the relay hop count is the only unsigned field.
- Links prove which key is on the other end before a contact is shown as nearby,
  and each link is rate-limited, which limits what one modified phone can flood
  or fake.
- Names are whatever people type. The id comes from the key; verify it by pairing.
- Settings → Mesh shows how many forged, unreadable or over-limit packets were dropped.

Broadcasts in "Everyone" are readable by any phone in range, and anyone nearby can
see that your phone is taking part in the mesh.

## Build

Requirements: JDK 21 and the Android SDK (compileSdk 37).

```bash
./gradlew :app:assembleDebug             # debug APK in app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest         # JVM unit tests
./gradlew :app:lintDebug                 # Android lint
./gradlew :app:connectedDebugAndroidTest # instrumented tests on a phone or emulator
```

Every push builds a debug APK in GitHub Actions; download it from the run's
artifacts. Settings shows which build a phone is running.

Radios do not work on emulators. Changes to discovery or relaying are checked on
real phones with the [field check](docs/FIELD_CHECK.md).

## Stack

Kotlin, Jetpack Compose (Material 3), Hilt, Room, Google Nearby Connections, Tink,
CameraX and ZXing. minSdk 26, targetSdk 35.

## Roadmap

See [ROADMAP.md](ROADMAP.md).
