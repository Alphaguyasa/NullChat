# Needs alpha's decision

Newest first. Claude does the safe default and keeps going; alpha answers from the
phone (reply in the Claude app or edit this file). Resolved items move to the bottom.

## Open

### 1. Turn on "Automatically approve" for the daily routine (setup, 2026-10-14)
Scheduled runs have nobody to approve tool calls. In the Claude app → the
"Null Chat daily dev" routine → settings, switch permissions to **Automatically approve**.
Until then, runs may stall waiting for approval.

### 2. Two-phone checks for radio changes (ongoing)
Emulators cannot run Bluetooth/Nearby. PRs labelled **needs two-phone check** are
merged once CI is green, but nobody has seen them work on real phones. When you
have two phones nearby, install the APK from the latest CI run (Actions → run →
`nchat-debug-apk` artifact) on both and check the items listed under each PR.
*Default:* keep merging on green CI and list unverified PRs here.

### 3. Should chat history be included in Google cloud backup? (2026-10-14)
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

_(none yet)_
