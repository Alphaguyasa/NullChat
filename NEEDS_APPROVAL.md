# Needs alpha's decision

Newest first. Claude does the safe default and keeps going; alpha answers from the
phone (reply in the Claude app or edit this file). Resolved items move to the bottom.

## Open

### 7. ACTION FOR ALPHA: model (2026-10-15)
Anthropic's model docs now list **Claude Fable 5.1** (`claude-fable-5-1`) above
Opus 5.5 ("for demanding reasoning and long-horizon agentic work"). The MODEL
POLICY says to use the newest Opus-class model *or any higher tier*. The cloud run
cannot change the routine's model (no RemoteTrigger tool in the session).
*If Fable 5.1 is available on your current plan without upgrading*, switch the
routine "Null Chat daily dev" to it; otherwise keep `opus` (Opus 5.5 today).
Never upgrade the plan for this.

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
