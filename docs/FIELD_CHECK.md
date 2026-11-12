# Field check

A scripted check for real phones, about 30 minutes. Radios do not work on
emulators, so this is the only test of discovery, relaying and anything else
that goes over the air. Run it before calling a radio change done, and whenever
several changes marked "needs two-phone check" have piled up.

## What you need

- Phones **A** and **B**; a third phone **C** for the relay part.
- The same build on every phone: Settings, last line, `Build 1.0.N (N) · commit`.
  Write it down. A check on mixed builds tests nothing.
- Bluetooth, Wi-Fi and location on; no SIM or internet needed. Turn mobile data
  off to be sure nothing goes through a server (there is none).
- A note for each step: pass, fail, or "odd" with what you saw.

On every phone first: Settings → Mesh, note the four "Dropped since Null Chat
started" counts. They are compared at the end.

## 1. Discovery (3 min)

1. Open Null Chat on A and B, side by side.
2. Each phone shows the other on the radar and the "Everyone" row within
   30 seconds (60 seconds in battery saver).
3. Lock both screens for a minute, unlock: they are still connected.

Fail if a phone never shows the other, or shows it only after restarting the app.

## 2. Pairing (3 min)

1. On A: Pair, show code. On B: Pair, scan it. Then the other way round.
2. Both show "paired" and the same six words. Compare the words aloud.
3. The "unverified" badge is gone from the contact on both phones.

Fail on "Codes don't match" for a real code, or different words on the two phones.

## 3. Direct messages (4 min)

1. A sends B a short message: `queued` → `sent` → `delivered · 1 hop`.
2. B replies with a long one (several sentences): it arrives whole.
3. Turn B's mesh off (Settings → Mesh). A sends a message: it stays `queued`,
   never `sent`. Turn B's mesh on: it arrives and turns `delivered`.

Fail if anything shows `sent` or `delivered` before the other phone had it.

## 4. Images (4 min)

1. A sends B a photo: a progress bar, then the image on B, then `delivered`.
2. Send two photos back to back, then a text: everything arrives once, nothing
   shows twice.

## 5. Relay through a third phone (6 min)

1. Put A and C far enough apart that they do not see each other directly
   (another room, or 30+ m outdoors), with B in the middle.
2. A sends C a text: it arrives, and A shows `delivered · 2 hops`.
3. A sends C an image: it arrives once.
4. Walk C into A's range: C now shows as one hop away.

## 6. Out of range and back (4 min)

1. Take B away from A until it drops off A's radar.
2. A sends B two messages: both stay `queued`.
3. Bring B back: both arrive in order and turn `delivered` within a minute.

## 7. Contact in a crowd (3 min)

1. With A, B and C together, B renames itself in Settings.
2. A and C show the new name within a minute.
3. Only paired contacts pulse on A's radar when they relay; an unpaired phone
   never makes a contact's node pulse.

## 8. Counters and wipe (3 min)

1. Settings → Mesh on each phone. "Forged packets", "Unreadable packets" and
   "Over the rate limit" are still at the counts noted at the start. "Relays
   that missed a phone" may have gone up; that is normal while people move.
2. Optional, on a spare phone only: Settings → Panic wipe. The app closes;
   reopened, it has a new id and no messages, and the other phones show it as a
   new, unverified contact.

## Reporting

Note the build line, the phones' Android versions, and each step's result.
For a failure, say what you did, what you expected and what you saw, and the
time it happened, so it can be matched to the logs
(`adb logcat -s MeshCoordinator NearbyTransport MeshService`; they hold only
short peer tags, never message text or full ids).
