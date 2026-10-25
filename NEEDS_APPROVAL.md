# Needs alpha's decision

Newest first. Claude does the safe default and keeps going; alpha answers from the
phone (reply in the Claude app or edit this file). Resolved items move to the bottom.

## Open

### 5l. Phone checks for block sender and locked notifications (2026-10-25)
*Block (#43), two phones, A and B:* on A, open B's chat, ⋮ → Block → Block. A
banner says B is blocked. B sends A a direct message, an image and a broadcast:
none appear on A, no notification shows, and on B the direct message stays at
`sent` (never `delivered`). Messages from a third phone still reach A, including
when B is the only phone between them. ⋮ → Unblock (or the banner's Unblock):
B's next message arrives as normal. A message B sent while blocked can still
arrive after Unblock if a phone was carrying it (up to a day); that is expected.
*Locked notifications (#44):* Settings → App lock on. Have B message A while A
is on another app or the home screen: the notification says only "New message",
with no name and no text, and a second message turns it into "2 new messages".
Tapping it asks to unlock first. Turn the lock off: the next notification shows
the name and text again.

### 5k. Phone checks for verified links, delete chat and router cost (2026-10-24)
*Verified links (#40):* the queue is now handed over only after the other phone's
signed announcement arrives over the same link. With B out of range, send B a
direct message from A: it shows `queued`. Bring B in range: within a few seconds
it turns `sent`, then `delivered`, and B shows as nearby, as before. If it stays
`queued` while B is listed nearby, that is this change failing; say so and it
will be reverted.
*Delete chat (#41), one phone:* open a chat with history, ⋮ → Delete chat →
Delete. The thread empties, the peer list preview clears, images are gone. A
message of yours still `queued` stays and still goes out. A timer set under
Disappearing messages is still shown. When the other phone comes back in range,
the old messages do not reappear.
*Router cost (#42), two or three phones:* nothing new to see. Broadcasts and direct
messages still reach `delivered`, with the same hop counts as before. It only
saves work on duplicate copies.

### 5j. One-phone checks for leaving mid-send and clock ordering (2026-10-23)
*Sends survive leaving the chat (#37):* with a second phone in range, send an
image and press Back at once. Reopen the chat: the image reaches `sent` and then
`delivered`. It must not sit at `queued`.
*Clock ordering (#39):* on phone B, set the date a day ahead and send A a message,
then set it back. On A, B's thread is not pinned above newer chats. The bubble
shows the time it arrived, not tomorrow's. Messages that arrive after it sit
below it.
*Received images (#38):* nothing to check by hand. Images from another phone
should look the same as before.

### 5i. Phone checks for nicknames, disappearing messages and battery modes (2026-10-22)
*Nicknames (#34), one phone:* open a chat, ⋮ → Rename, type "Mom", Save. The title,
the peer list and the next notification from that person read `Mom · <id>`. Rename
with an empty box to go back to the name they chose.
*Disappearing messages (#35), one phone:* ⋮ → Disappearing messages → 1 hour. The
subtitle shows the timer. Messages already in the chat stay. A message that arrives
now, image included, is gone about an hour later and does not come back when the
sender's phone reconnects.
*Battery modes (#36), **two phones** (radio timing changed):* Settings → Battery use →
Saver. A phone walking into range is found within about a minute (it was 30 s), and
messages still reach `delivered`. With Automatic, turning on the system battery saver
should do the same.

### 5h. Phone checks for the toolchain bump and app lock (2026-10-21)
*Nearby 19.5.1 (#31):* the transport library changed version. On two phones,
pair, send a direct message and an image, and check both reach `delivered`.
*App lock (#33), one phone, Android 9 or later with a screen lock:* Settings →
App lock → turn on "Ask to unlock". Leave the app for over a minute and come
back: the lock screen shows and the fingerprint/PIN prompt opens by itself.
Cancel it: the lock stays; Unlock asks again. Leave for under a minute (for
example to pick a photo): no lock. Rotating the phone never unlocks it. The
recent-apps thumbnail shows no chats (Android 13+). While locked, a second
phone's messages still arrive (notification shows). On a phone with no screen
lock the switch is greyed out with a hint.

### 5g. One-phone check for panic wipe (2026-10-20)
Emulators cannot show the part that matters. On a phone with history: Settings,
scroll to Panic wipe, tap "Wipe this phone". Cancel must leave everything as it
was. Tap again and choose Wipe. The app closes. Reopen it: the permission screen
shows again, the peer list and threads are empty, and Settings shows a **new**
id. On a second phone that had paired with it, the wiped phone appears as a new,
unverified contact. Android should list the app's storage as nearly empty.

### 5f. Two-phone check for reply with quote (2026-10-20)
On A, long-press a message from B and choose Reply. A "replying to" strip shows
the quoted words above the box; × cancels it. Send a reply. On both phones the
bubble shows the quote as a muted block above the answer, and the peer list and
B's notification show only the answer. A phone on an **older build** should see
the reply as two lines, `> quoted words` and the answer under it. Your own queued
or failed message offers no Reply.

### 5e. Two-phone check for PR #22 (delete for me, 2026-10-19)
B sends A a direct message and a broadcast. On A, long-press each and choose
Delete for me, then confirm. Both disappear and the peer list preview updates.
Now let B (or a third phone that relayed them) leave range and come back. Neither
message may reappear on A. Long-press, then Copy, must paste the text elsewhere.
A queued message (B out of range) must not offer Delete.

### 8. Dependabot PRs and the authorship rule (2026-10-19)
Dependabot now opens weekly grouped update PRs. Squash-merging one would make
dependabot[bot] the commit author, and your rule is that commits show only you.
*Default:* the daily run re-applies the bumps on its own branch as your commit
and closes the Dependabot PR (written into CLAUDE.md). If you would rather it
merge Dependabot PRs directly when green, say so.

### 9. Release APK is built but not run (2026-10-19)
PR #21 turned on R8 for release builds. CI builds it, but it is unsigned, so nobody
has run it on a phone yet. R8 can break code that uses reflection; the app uses
none and every library ships its own rules, so the risk is low. It becomes
checkable once signing is decided (item 4). The debug APK you install is unchanged.

### 5d. Two-phone check for PRs #17 and #19 (resend, display names, 2026-10-18)
*Resend (#17):* with B out of range, send an image from A. It must say `failed ·
tap to resend`. Bring B in range and tap the bubble on A. The image goes out
again, only one copy shows on each phone, and it reaches `delivered`.
*Names (#19):* on A open Settings (on the "you …" line), type a name and Save.
B's peer list should show `name · <A's id>` within a few seconds if in range,
otherwise the next time they connect. A phone on an older build must still see
A by id and still exchange messages (it ignores the new PROFILE packet).

### 7. ACTION FOR ALPHA: model (2026-10-15, still open 2026-10-19)
Anthropic's model docs now list **Claude Fable 5.1** (`claude-fable-5-1`) above
Opus 5.5 ("for demanding reasoning and long-horizon agentic work"). The MODEL
POLICY says to use the newest Opus-class model *or any higher tier*. The cloud run
cannot change the routine's model (no RemoteTrigger tool in the session).
*If Fable 5.1 is available on your current plan without upgrading*, switch the
routine "Null Chat daily dev" to it; otherwise keep `opus` (Opus 5.5 today).
Never upgrade the plan for this.

### 5c. Two-phone check for PR #15 (message notifications, 2026-10-16)
Phone B sends a direct message while A is on the peer list, on another app, or
locked. A should show one notification, and a second message turns it into "2 new
messages". Tapping it opens B's thread and clears the notification. With B's thread
open on A, no notification should appear. On A's lock screen only "New message"
shows. Broadcasts should not notify. Also check that `delivered · 1 hop` (PR #14)
appears under your sent messages.

### 5b. Two-phone check for PR #9 (no plaintext direct messages)
On two phones that have **never met** (or after clearing data on one): send a
direct message before they connect. It must show `queued`, not `sent`. After the
phones connect and announce, it must go out and reach `delivered`. Send a long
message (>55 bytes) or image to a peer you have no key for: it must fail with
"No encryption key for this person yet…" rather than going out.

### 2. Two-phone checks for radio changes (ongoing)
Emulators cannot run Bluetooth/Nearby. PRs labelled **needs two-phone check** are
merged once CI is green, but nobody has seen them work on real phones. When you
have two phones nearby, install the APK from the latest CI run (Actions → run →
`nchat-debug-apk` artifact) on both and check the items listed under each PR.
*Default:* keep merging on green CI and list unverified PRs here.

### 3. Should chat history be included in Google cloud backup? (2026-10-14)
**Update 2026-10-22:** the audit adds two reasons to exclude it. A backup made before a
panic wipe or before messages disappear brings them back on restore. A restore onto a
new phone also brings back "verified" contacts under an identity that cannot decrypt
them. Recommendation: (a). It is a small change (roadmap note under the open items)
once you say yes.
`android:allowBackup="true"`. Keys are already excluded, but the message database
and images are backed up to the user's Google account. For a privacy-focused
offline messenger that is arguably a leak; excluding it means history is lost when
changing phones.
*Default until you decide:* no change.
Options: (a) exclude database + attachments (most private), (b) keep as is,
(c) make it a setting.

### 4. Release signing and Play Store (2026-10-14)
A signed release build needs a keystore you create and keep (free); it would go in
GitHub Secrets, never in the repo. Publishing on Google Play needs a one-time
**US$25** developer fee — Claude will not pay or register. Free alternatives:
GitHub Releases with the APK, or F-Droid (free, open source only).
*Default:* only unsigned/debug builds; no publishing.

### 5. Route strip privacy (2026-10-14)
Showing `you → a4f2 → 9c11 → maya` requires every relay to append its id to the
packet, which tells everyone downstream who relayed. *Default:* not built; roadmap
item #23 waits for your call.

## Resolved

### Release tags from the cloud run (2026-10-15)
Tag pushes are refused from the cloud session. alpha approved roadmap #24, and
`release-tag.yml` (PR #11) now tags every merge to main with GITHUB_TOKEN. Its
first run created `v2026-10-15-2` … `-5` (#8–#11). `v2026-10-15-1` (#7,
`597f268`) could not be created: GitHub refuses the workflow token a tag on a
commit whose workflow files differ from main's, and #8 changed `android.yml`
after #7. Optional for alpha: `git tag v2026-10-15-1 597f268 && git push origin
v2026-10-15-1` from your PC. Not needed for rollback in practice, since `-2`
already contains #7. New merges are tagged as they land, so this cannot recur.

### "Automatically approve" for the daily routine (2026-10-14)
The routine settings have no permission option (only name, instructions, trigger,
connectors, behaviour = auto-fix PRs, notifications), so there is nothing to switch.
If a run ever stalls on an approval prompt, put it back at the top of Open with the
run log details. Auto-fix PRs stays **off**: the daily loop handles CI fixes within
the 2-attempt cap in the MERGE POLICY.
