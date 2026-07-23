package et.alpha.nchat.ui.theme

import androidx.compose.ui.graphics.Color

// The mesh palette. These are the only colors the UI is allowed to name; every
// Material slot in Theme.kt is filled from this list. Dark is the primary
// theme — see the design principles in CLAUDE.md.

// Dark (default)
val BgBase = Color(0xFF0E1A26)        // app background
val BgSurface = Color(0xFF16303F)     // cards, bubbles, sheets
val BgElevated = Color(0xFF1D3D4F)    // dialogs, menus
val Accent = Color(0xFF5DCAA5)        // mesh green — connection, outgoing
val AccentBright = Color(0xFF9FE1CB)  // active/live state, icon strokes
val AccentDim = Color(0xFF3D8A72)     // relay links, inactive nodes
val TextPrimary = Color(0xFFE8F1EE)
val TextSecondary = Color(0xFF8FA8A5)
val TextMuted = Color(0xFF5C7370)
val Warning = Color(0xFFEF9F27)       // queued / store-and-forward
val Danger = Color(0xFFE24B4A)        // unverified peer, failed delivery
val Verified = Color(0xFF5DCAA5)

// Light
val BgBaseLight = Color(0xFFF4F7F6)
val BgSurfaceLight = Color(0xFFFFFFFF)
val AccentLight = Color(0xFF0F6E56)
val TextPrimaryL = Color(0xFF0E1A26)

/**
 * Hairline used to separate surfaces. The design system forbids elevation
 * shadows, so 1dp of [AccentDim] at 20% is the only divider in the app.
 */
val Hairline = AccentDim.copy(alpha = 0.2f)
