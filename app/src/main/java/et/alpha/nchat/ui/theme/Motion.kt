package et.alpha.nchat.ui.theme

import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

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
 * Whether this device wants animation at all.
 *
 * Two separate ways a user says no. The developer-options animation scale set to
 * zero is the accessibility answer, and is also what instrumentation sets, so
 * honouring it makes animated screens testable. Battery saver matters more here
 * than in most apps: this is the app people reach for in a power cut, and it has
 * no business spending charge on fades.
 *
 * Sampled when the screen is composed rather than observed. Both are settings the
 * user leaves the app to change, so the next time the screen is entered is soon
 * enough, and neither is worth a content observer.
 */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        val saving = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        scale > 0f && !saving
    }
}
