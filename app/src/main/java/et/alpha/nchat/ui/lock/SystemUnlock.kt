package et.alpha.nchat.ui.lock

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import et.alpha.nchat.R

/**
 * The phone's own unlock: fingerprint or face, falling back to PIN, pattern or
 * password.
 *
 * Weak biometrics plus the device credential, not strong only: this gate only
 * keeps a borrowed phone out of the chats, it does not release a key, and
 * BIOMETRIC_STRONG with DEVICE_CREDENTIAL is not supported on Android 9 and 10
 * at all. The credential fallback also means nobody can be locked out of their
 * own messages by a wet finger.
 */
object SystemUnlock {

    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /**
     * False when the phone has no screen lock, so there is nothing to ask for.
     *
     * Also false before Android 9. There the library draws its own fingerprint
     * dialog with AppCompat, and this app has no AppCompat theme for it to
     * inflate against; Android 8 is a sliver of minSdk and not worth a theme
     * that only that dialog would use.
     */
    fun isAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /**
     * True when this phone can never answer the prompt, so a lock left on
     * would keep the user out of their own messages until they clear its data.
     *
     * The screen lock was removed (which itself asked for the PIN), or a
     * backup with the lock on was restored onto a phone without one. Not a
     * sensor that is busy or locked out for now: those pass, and the PIN
     * fallback covers them.
     */
    fun cannotEverUnlock(context: Context): Boolean =
        unlockIsImpossible(Build.VERSION.SDK_INT, BiometricManager.from(context).canAuthenticate(AUTHENTICATORS))

    /**
     * Shows the system prompt. Cancelling or failing leaves the app locked; the
     * lock screen's button asks again. [onImpossible] runs instead when the
     * prompt errors because the phone has no way left to unlock.
     */
    fun prompt(activity: FragmentActivity, onImpossible: () -> Unit = {}, onUnlocked: () -> Unit) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onUnlocked()
                }

                // Asked again rather than read from the error code: the codes
                // differ between Android versions, the availability check is
                // the one the Settings switch already trusts.
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (cannotEverUnlock(activity)) onImpossible()
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.lock_prompt_title))
                // No negative button: with DEVICE_CREDENTIAL allowed the system
                // supplies "use PIN" itself, and the builder rejects both.
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build(),
        )
    }
}

/**
 * The pure half of [SystemUnlock.cannotEverUnlock], for the JVM tests.
 *
 * @param status what `BiometricManager.canAuthenticate` returned.
 */
internal fun unlockIsImpossible(sdkInt: Int, status: Int): Boolean =
    sdkInt < Build.VERSION_CODES.P || status in PERMANENTLY_UNAVAILABLE

private val PERMANENTLY_UNAVAILABLE = setOf(
    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
    BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
)
