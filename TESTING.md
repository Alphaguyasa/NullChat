# v1 two-phone manual test

Verifies the CLAUDE.md definition of done: *two physical Android phones in
airplane mode with Bluetooth on discover each other, exchange text messages, and
chat history survives an app restart on both.*

BLE and Wi-Fi Direct do not work on emulators. Everything below needs real
hardware.

## Setup

Two phones, API 26+. Call them **A** and **B**.

On each:

1. `./gradlew installDebug` (or `adb -s <serial> install -r app-debug.apk`)
2. **Airplane mode ON** — this is the point of the test, not an inconvenience.
3. **Bluetooth ON** (leave it on after enabling airplane mode; Android keeps it)
4. **Location services ON** — required for BLE scanning through Android 12,
   regardless of the app's own permission grant
5. Launch, grant permissions when asked
6. Note the `You: xxxxxxxx` id on the peer list header — A and B must differ
7. **Check both clocks agree**, before airplane mode goes on

Start them within ~10 m of each other, no walls between.

> Step 7 is not housekeeping. A message's `timestamp` is taken from
> `System.currentTimeMillis()` on the *sending* phone, and threads are ordered by
> it, so a phone whose clock is hours out has its messages sort into the past and
> interleave wrongly on the other device. It looks exactly like a message-ordering
> bug in the app.
>
> This bit us on a real handset that was six hours behind with automatic time
> switched off. Check with:
>
> ```bash
> adb -s <serial> shell date +%s        # compare against `date +%s`
> adb -s <serial> shell settings put global auto_time 1
> ```
>
> Fix it *before* enabling airplane mode — with no network there is no NTP to
> sync from.

---

## 1. Preconditions are enforced

| # | Step | Expected |
|---|---|---|
| 1.1 | Turn Bluetooth **off** on A | Screen switches to *Almost there* / **Turn on Bluetooth** within ~2 s |
| 1.2 | Tap **Turn on Bluetooth**, accept | Screen dismisses itself, peer list returns — no restart needed |
| 1.3 | Turn Location off in quick settings | *Almost there* / **Open Location settings** |
| 1.4 | Turn Location back on | Peer list returns |
| 1.5 | Pull down the shade while blocked | Notification reads **Mesh paused** with which switch is off |

## 2. Discovery

| # | Step | Expected |
|---|---|---|
| 2.1 | Both apps open, wait up to **60 s** | Each peer list gains a row with the *other* phone's 8-char id, dot filled |
| 2.2 | Compare | A shows B's `You:` id and vice versa. Neither lists itself |
| 2.3 | Header on each | *1 device in range* |
| 2.4 | Notification on each | **Mesh active** / *1 device in range* |

> Discovery is duty-cycled 5 s on / 25 s off, so a peer can take up to ~30 s to
> appear even when both phones are on the desk. That is expected, not a fault.
> After 5 minutes with nothing found the cycle widens to 60 s — if you have left
> them apart a while, allow a full minute.

## 3. Direct messages, both directions

| # | Step | Expected |
|---|---|---|
| 3.1 | On A tap B's row, type `hello from A`, **Send** | Bubble appears right-aligned |
| 3.2 | Watch the tick | Dot (PENDING) flips to **✓** (SENT) within ~1 s |
| 3.3 | On B | Message appears **without touching B** — it is live, not on open |
| 3.4 | Open the thread on B, reply `hello from B` | Same, in reverse |
| 3.5 | On A | B's reply arrives live while the thread is open |
| 3.6 | Check both threads | Sent right-aligned, received left-aligned, timestamps sane |

## 4. Broadcast thread

| # | Step | Expected |
|---|---|---|
| 4.1 | On A open **Everyone**, send `broadcast test` | ✓ within ~1 s |
| 4.2 | On B open **Everyone** | Message present, labelled with A's short id |
| 4.3 | Send from B in **Everyone** | Arrives on A |

## 5. Delivery states

| # | Step | Expected |
|---|---|---|
| 5.1 | Turn Bluetooth off on **B** only. On A send `while you were out` | A's bubble stays a **dot** (PENDING) and does not tick |
| 5.2 | A's peer list | B's dot goes hollow; header returns to *No devices in range* |
| 5.3 | Turn B's Bluetooth back on, wait for reconnect (~30 s) | A's pending message flips to **✓** on its own |
| 5.4 | B | The message arrives |

> 5.3 is the forward queue flushing. It fires when B reconnects, and offers B
> the packets held for it plus anything queued for broadcast. A message that has
> already ticked will not be re-sent.

| # | Step | Expected |
|---|---|---|
| 5.5 | Repeat 5.1, then force-stop **both** apps before reconnecting | — |
| 5.6 | Relaunch both, wait for reconnect | The pending message still flips to **✓** and arrives on B |

> 5.5–5.6 are the point of persisting the queue: before it, a restart between
> composing and reconnecting lost the retry. This is the one queue behaviour no
> automated test on this machine covers — the JVM tests use an in-memory store,
> and the Room tests need a device.

## 6. Persistence — the milestone requirement

| # | Step | Expected |
|---|---|---|
| 6.1 | Note the last few messages on both | — |
| 6.2 | Force-stop both (Settings → Apps → NChat → Force stop) | — |
| 6.3 | Relaunch both | Every thread and message is still there, in order, with ticks intact |
| 6.4 | Check `You:` id on each | **Unchanged** from before the restart |
| 6.5 | Send a new message | Still works; new message appends below the old ones |

> 6.4 matters: the id is now the first 8 bytes of SHA-256 of this device's
> Ed25519 public key, which `KeyManager` seals under an Android Keystore key. If
> it changes, existing threads orphan. Clearing app data *will* legitimately
> change it — it discards the keypair, and the device becomes a different device.

## 7. Message length

| # | Step | Expected |
|---|---|---|
| 7.1 | Type until the counter passes 55 | Counter turns red, **Send** greys out |
| 7.2 | Delete back to ≤ 55 | Send re-enables |
| 7.3 | Paste ~20 emoji | Counter reads ~80 bytes and blocks, despite only 20 characters |
| 7.4 | Send exactly 55 bytes of ASCII | Sends and arrives whole |

## 8. Range

| # | Step | Expected |
|---|---|---|
| 8.1 | Walk B ~30–50 m away or put it behind a wall | A's dot goes hollow within a minute or two |
| 8.2 | Send from A | Stays PENDING |
| 8.3 | Walk back into range | Reconnects; pending message ticks and arrives |

## 9. Background operation

| # | Step | Expected |
|---|---|---|
| 9.1 | Press Home on B; leave A open | **Mesh active** notification persists on B |
| 9.2 | Send from A | B's notification stays accurate; message is there when B is reopened |
| 9.3 | Lock B's screen, send from A, wait 2 min, unlock | Message present |

## 10. Verification and pairing

| # | Step | Expected |
|---|---|---|
| 10.1 | On A, open B's thread before pairing | Red banner: *You have not verified this contact*, with a **Pair** button |
| 10.2 | A's peer list | B's row carries a red **unverified** chip |
| 10.3 | Open **Everyone** | Different banner: *Messages to Everyone are not encrypted* |
| 10.4 | Tap **Pair a contact** on A | QR code, `You:` id, and six words |
| 10.5 | Compare the six words on A with A's own words shown on B after pairing | They match |
| 10.6 | On A, scan B's code | *Verified <B's id>* plus B's six words |
| 10.7 | Back on A's peer list | B's **unverified** chip is gone |
| 10.8 | Open B's thread on A | The unverified banner is gone |
| 10.9 | Scan A's own code on A | *That is your own code* — refused, not paired |
| 10.10 | Scan any non-NChat QR | *That is not an NChat pairing code* |

> Pairing is one-directional. Scanning B's code on A verifies B **on A only**; B
> must scan A's code to verify A. Both phones showing no banner is the finished
> state.

> 10.5 is the check that matters if you cannot scan: the six words are 48 bits,
> so they are a spot-check for reading aloud, not a proof. Scanning compares all
> 32 bytes.

## 11. Three phones — relay (optional)

Needs a third phone **C** and enough space that **A and C cannot hear each
other** while both can hear **B**. Roughly 30–50 m apart with B in the middle,
or two rooms with B in the corridor.

| # | Step | Expected |
|---|---|---|
| 11.1 | Confirm A lists B but **not** C, and C lists B but not A | Precondition — without this the test proves nothing |
| 11.2 | From A, send in **Everyone** | Arrives on C, relayed by B |
| 11.3 | Check B | Message shown once, not duplicated |
| 11.4 | Send several | No storm, no repeats; each appears once everywhere |

> This is the only test that exercises multi-hop routing on real hardware. The
> 4-hop chain, TTL expiry and no-echo behaviour are covered by
> `MeshRouterTest` on the JVM, but nothing has yet proven flooding works over
> actual radios.

---

## Known-broken, do not report as bugs

- **Broadcast messages are sent in plaintext.** The **Everyone** thread has no
  single recipient, so there is no pair of keys to agree on. Anyone in Bluetooth
  range reads it. The thread carries a banner saying so.
- **A direct message falls back to plaintext** if the recipient has not yet
  announced itself, rather than failing to send. This is the weakest point in
  the design: two phones that have only just met will exchange their first
  messages in the clear. Watch for the `no key for …, sending in the clear`
  warning in logcat.
- **First key wins, forever.** A peer's key is trust-on-first-use and cannot be
  replaced without forgetting the peer. There is no key rotation.
- **The six-word fingerprint is 48 bits.** It is a spot-check for reading aloud,
  not a proof — scanning the QR compares all 32 bytes and is the real check.
- **No ACK**, so a sent message never advances past one tick to ✓✓.
- **No display names** — peers show as 8 hex characters.
- A message that fails to send is queued for its recipient and retried when that
  recipient connects. The queue holds 500 packets for 24 hours, evicting oldest
  first, and survives a restart. There is still no retry *limit* and no FAILED
  transition: an undeliverable message is retried on every reconnect until it
  expires.

## If discovery never happens

1. Both phones have Location services on? (Not just the permission.)
2. Both on Bluetooth, both out of battery saver?
3. `adb logcat -s MeshService NearbyTransport` — a repeating
   `MISSING_PERMISSION_*` means a toggle is off, not a permission problem
4. Give it a full 60 s; the duty cycle may have backed off
5. Force-stop both and relaunch to reset the backoff
