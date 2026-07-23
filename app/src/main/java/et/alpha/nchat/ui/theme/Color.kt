package et.alpha.nchat.ui.theme

import androidx.compose.ui.graphics.Color

// The mesh palette. These are the only colors the UI is allowed to name; every
// Material slot in Theme.kt is filled from this list. Dark is the primary
// theme — see the design principles in CLAUDE.md.
//
// Ratios in the comments are WCAG 2.1 contrast against BgBase. Two tokens are
// lighter here than the values written in the design notes: at #5C7370 TextMuted
// measured 3.47:1 and at #E24B4A Danger measured 4.47:1, and both are used for
// body-sized text, which needs 4.5:1. The same notes require every screen to
// pass a contrast check against BgBase, so the two rules disagreed and the
// legibility one won. Each is nudged to the smallest value that clears the bar
// with a little margin, keeping its hue.

// Dark (default)
val BgBase = Color(0xFF0E1A26)        // app background
val BgSurface = Color(0xFF16303F)     // cards, bubbles, sheets
val BgElevated = Color(0xFF1D3D4F)    // dialogs, menus
val Accent = Color(0xFF5DCAA5)        // mesh green — connection, outgoing. 8.75:1
val AccentBright = Color(0xFF9FE1CB)  // active/live state, icon strokes. 11.81:1
val AccentDim = Color(0xFF3D8A72)     // relay links, inactive nodes. 4.25:1 —
                                      // never text, and non-text needs 3:1
val TextPrimary = Color(0xFFE8F1EE)   // 15.28:1
val TextSecondary = Color(0xFF8FA8A5) // 6.96:1
val TextMuted = Color(0xFF718C88)     // 4.86:1
val Warning = Color(0xFFEF9F27)       // queued / store-and-forward. 8.09:1
val Danger = Color(0xFFE75A55)        // unverified peer, failed delivery. 5.02:1
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
