package et.alpha.nchat.data.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the app is locked right now, given when it was last in the background.
 *
 * Pure Kotlin with the clock passed in, so the timing rules are unit-tested on
 * the JVM; the activity only reports lifecycle edges and successful unlocks.
 *
 * A short grace period rather than locking on every stop: picking an image
 * from the gallery, or the system's own credential screen on older Android,
 * also stops the activity, and asking for the PIN again after each of those
 * would train people to turn the lock off.
 *
 * @param initiallyLocked true when the lock is on at process start. A fresh
 *   process is always a cold start, so it locks straight away.
 */
class AppLockGate(
    initiallyLocked: Boolean,
    private val graceMillis: Long = GRACE_MILLIS,
) {

    private val _locked = MutableStateFlow(initiallyLocked)

    /** True while the lock screen must cover everything. */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /** Monotonic time the app last left the foreground unlocked, if it has. */
    private var backgroundedAt: Long? = null

    fun onBackground(now: Long, enabled: Boolean) {
        // While locked nothing needs timing: it stays locked until unlocked.
        if (enabled && !_locked.value) backgroundedAt = now
    }

    fun onForeground(now: Long, enabled: Boolean) {
        if (!enabled) {
            unlock()
            return
        }
        val since = backgroundedAt ?: return
        backgroundedAt = null
        if (now - since >= graceMillis) _locked.value = true
    }

    /** The user proved who they are, or turned the lock off. */
    fun unlock() {
        backgroundedAt = null
        _locked.value = false
    }

    companion object {
        /** Long enough to pick a photo, short enough that a pocketed phone locks. */
        const val GRACE_MILLIS: Long = 60_000L
    }
}
