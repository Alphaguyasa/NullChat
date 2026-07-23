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
