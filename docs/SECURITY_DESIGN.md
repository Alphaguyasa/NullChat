# Security design

Null Chat runs on a mesh of phones that do not trust each other. Any phone in
range can send anything, relay or drop anything, and lie about its name. This
document says what the app protects, against whom, how, and what it does not
protect. Numbers in brackets are the pull requests that made each change.

## What is protected, and from whom

| Asset | Protected from | Not protected from |
|---|---|---|
| Text and images of direct messages | Every phone that relays them, and anyone listening | The two phones in the conversation; someone holding an unlocked phone |
| Who sent a packet | Forgery by any other phone | — |
| "This contact is nearby" | A stranger advertising the contact's id | — |
| The phone's own storage and battery | Floods and oversized or malformed packets from one modified phone | Many cooperating phones, radio jamming |
| Messages on a lost phone | A finder, with app lock on; panic wipe | Google backup (see open gaps) |

Out of scope: hiding that a phone takes part in the mesh, hiding who talks to
whom (sender and recipient ids are in the clear so relays can work), and
resisting radio jamming.

## Identity

- Each phone makes an Ed25519 signing key and an X25519 agreement key with Tink.
  They are stored sealed by an AES-GCM key held in the Android Keystore, in
  preferences excluded from backup.
- A phone's id is the first 8 bytes of SHA-256 of its signing key. Names are
  free text and prove nothing; the id is what people verify.
- Verification is out of band: pairing by QR code shows a six-word fingerprint
  that both people compare.
- The QR code is trusted only for the signing key. The agreement key always comes
  from a signed announcement over the mesh; a code that disagrees with it is
  refused with "Codes don't match" (#91).

## Packets

- One format for everything, through one codec: version, type, message id,
  sender, recipient, ttl, timestamp, payload, Ed25519 signature (64 bytes).
- The signature covers every field except the ttl, which each relay must lower.
  A relay can shorten a packet's life but cannot change anything else.
- Each new packet type or payload has a round-trip test; a format older builds
  would misread needs a new version or a new type.

## Direct messages

- X25519 between the two phones' keys → HKDF-SHA256 (fixed info string) →
  AES-256-GCM.
- The nonce is the first 12 bytes of the random 16-byte message id. That saves
  12 bytes in a 71-byte payload. A repeated nonce under one key would break GCM;
  with 96 random bits that is not expected in the life of a pair of keys.
- A message is never sent in plaintext while the recipient's agreement key is
  unknown: it waits, `queued`, until a signed announcement brings the key (#9),
  and agreement keys are stored so a restart does not lose them (#8).
- A message that cannot be decrypted is shown as such, never as garbage text (#4).

## Links: who is actually nearby

Google Nearby gives each connection an endpoint and the name the other phone
advertised. The name is that phone's claim, so:

- A link belongs to a contact only after a signed announcement on that link
  (#40), and only after a LINK_PROOF packet signs the Nearby connection's own
  token, so an announcement recorded elsewhere cannot be replayed onto a new
  link (#73).
- Only proven links count as "online", "one hop away", or for the radar's relay
  pulses (#99).

## Routing under abuse

- Flooding with a seen-cache: a message id is remembered for 10 minutes, and only
  after its signature checks out, so a forged copy cannot block the real one. The
  cache holds 20 000 ids, enough for several images in flight (#96).
- Packets start with a ttl of 6 and relays wait 50–500 ms at random before
  passing them on.
- Each link has a token bucket (burst 1000 packets, 50 per second) ahead of
  decoding and signature checks, so a flood costs the receiver almost nothing (#77).
- Announcements and profiles are taken only if newer, by the sender's own clock,
  than the newest already taken, so replays cannot make a contact look nearby or
  keep it from being pruned (#80).
- Disappearing-message timers are signed, sealed and ordered by the sender's
  clock, with a 7-day replay window (#53).
- Bounded memory everywhere a stranger can add entries: the forward queue (500
  packets, 24 h), reassembled text (2000 bytes) (#90), the key directory (1024
  strangers) (#65), hop readings (256 peers) (#64), deleted-message tombstones
  (5000) (#86), transfers in progress (#63). A full queue reports what it drops
  instead of pretending it was sent (#59).
- A failure while handling one packet is caught and counted; it never crashes the
  app (#57). Settings → Mesh shows how many forged, unreadable and over-limit
  packets were dropped (#87).

## On the phone

- App lock (fingerprint, face or screen lock, after a minute away); while locked,
  notifications say only "New message" (#33, #44). If the phone loses its screen
  lock, app lock turns off instead of locking the user out (#89).
- Panic wipe clears the app's data and keys in one confirmed step (#30).
- Blocked senders are dropped before anything is stored and get no receipt (#43).
- Logs never contain message text, keys or full ids; peers appear as 4-hex tags.

## Open gaps

- **Google backup.** Chat history is in the app's backup, which a restore brings
  back after a panic wipe or after messages have disappeared. Keeping it out is a
  small change waiting on a product decision.
- **No forward secrecy.** Direct messages use the two phones' long-term keys, so
  someone who later takes both a phone's keys and a recording of the radio
  traffic can read past messages. A ratchet would fix this at the cost of state
  that must survive the mesh's long delays.
- **Older builds.** The receiving side still accepts plaintext direct messages
  from builds older than #9. A cutoff is planned once no such builds are in use.
- **Metadata.** Sender and recipient ids, packet sizes and timing are visible to
  every phone in range.
- **Broadcasts** in "Everyone" are signed but not encrypted, by design.
- **Many cooperating phones** can still fill shared bounds faster than one can;
  the limits above are per link.
