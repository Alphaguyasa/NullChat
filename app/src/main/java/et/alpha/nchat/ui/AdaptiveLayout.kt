package et.alpha.nchat.ui

/**
 * Whether the peer list and the open chat sit side by side.
 *
 * From 840dp, Material's "expanded" width: a tablet in landscape, a large
 * tablet or a foldable held open, and most phones on their side. Below it the
 * list is too narrow for a peer label and its proximity at 200% font scale once
 * a chat takes the rest. Width only, not a device type: a phone in a split-screen
 * window gets one pane, a phone on its side gets two.
 */
internal fun usesTwoPanes(widthDp: Int): Boolean = widthDp >= TWO_PANE_MIN_WIDTH_DP

/**
 * The list pane's width in two-pane mode: a third of the window, but never under
 * the width a phone's list is designed for, nor so wide that the chat loses
 * its room on a 13-inch tablet.
 */
internal fun listPaneWidthDp(widthDp: Int): Int = (widthDp / 3).coerceIn(LIST_PANE_MIN_DP, LIST_PANE_MAX_DP)

internal const val TWO_PANE_MIN_WIDTH_DP: Int = 840
private const val LIST_PANE_MIN_DP: Int = 320
private const val LIST_PANE_MAX_DP: Int = 420
