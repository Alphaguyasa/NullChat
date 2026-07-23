package et.alpha.nchat.ui.theme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.ContextCompat

// The design system's motion rules, in one place: one duration, one easing
// curve, and one answer to whether this device wants animation at all. Colors
// and type already live in this package; motion is the third thing every screen
// has to agree on.

/**
 * 200ms — the short end of the design system's 200–250ms range.
 *
 * Motion in this app reports on a radio, not on a UI. A peer appearing is news,
 * and news should not take a quarter of a second to finish arriving.
 */
const val MotionDurationMs = 200

/** The app's only animation spec. */
val MotionSpec: FiniteAnimationSpec<Float> =
    tween(durationMillis = MotionDurationMs, easing = FastOutSlowInEasing)

/**
 * [MotionSpec] in the units a list animates a row's position with.
 *
 * Same curve and same duration — a row sliding aside to make room for a new
 * message has to finish at the same moment as the message that displaced it, or
 * the two read as unrelated events.
 */
val MotionPlacementSpec: FiniteAnimationSpec<IntOffset> =
    tween(durationMillis = MotionDurationMs, easing = FastOutSlowInEasing)

/**
 * [MotionSpec], or an instant one when the device has asked for no animation.
 *
 * For the animations that have to be given *a* spec and cannot simply be handed
 * null — a `Crossfade` still has to swap its content. Snapping is how those
 * honour [rememberAnimationsEnabled].
 */
fun motionSpec(animate: Boolean): FiniteAnimationSpec<Float> = if (animate) MotionSpec else snap()

/**
 * Whether this device wants animation at all.
 *
 * Two separate ways a user says no. The developer-options animation scale set to
 * zero is the accessibility answer, and is also what instrumentation sets, so
 * honouring it makes animated screens testable. Battery saver matters more here
 * than in most apps: this is the app people reach for in a power cut, and it has
 * no business spending charge on fades.
 *
 * Both are observed rather than sampled once. Battery saver is the reason —
 * Android switches it on by itself at a low-battery threshold, which lands
 * mid-conversation and with no recomposition to notice it. A setting that turns
 * itself on while the user is looking at the screen has to be watched.
 *
 * Reports "no animation" under inspection: a preview is a still image, and it
 * has no business registering receivers on the renderer's context either.
 */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    if (LocalInspectionMode.current) return false

    val context = LocalContext.current
    return produceState(initialValue = context.animationsEnabled(), context) {
        val refresh = { value = context.animationsEnabled() }

        val saverChanged = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = refresh()
        }
        ContextCompat.registerReceiver(
            context,
            saverChanged,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val scaleChanged = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            scaleChanged,
        )

        // Re-read on registering: either could have changed between the initial
        // sample above and the watchers being in place.
        refresh()

        awaitDispose {
            context.unregisterReceiver(saverChanged)
            context.contentResolver.unregisterContentObserver(scaleChanged)
        }
    }.value
}

private fun Context.animationsEnabled(): Boolean {
    val scale = Settings.Global.getFloat(
        contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
    val saving = getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
    return scale > 0f && !saving
}
