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
     * Shows the system prompt. Cancelling or failing leaves the app locked; the
     * lock screen's button asks again.
     */
    fun prompt(activity: FragmentActivity, onUnlocked: () -> Unit) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onUnlocked()
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
