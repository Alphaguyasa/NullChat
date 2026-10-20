package et.alpha.nchat.data.wipe

import android.app.ActivityManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Erases everything this app holds on the phone: identity keys, every message,
 * every image, every setting. The next launch is a new device with a new id.
 *
 * One platform call, `ActivityManager.clearApplicationUserData`, rather than
 * deleting the pieces by hand. It is what "Clear storage" in system settings
 * does: the database, `filesDir`, every preferences file and the app's Android
 * Keystore entries all go, and the process is killed before anything can write
 * again. A hand-rolled list would be one new prefs file away from leaving
 * something behind, and would race the mesh service still writing rows. The
 * cost is that the app closes instead of restarting itself (the clear also
 * cancels any alarm that could reopen it), which the confirm dialog says.
 *
 * It also revokes runtime permissions and clears notifications. For a panic
 * wipe that is wanted: a notification on the lock screen is message content.
 */
@Singleton
class PanicWipe internal constructor(private val clear: () -> Boolean) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        clear = {
            val activities = context.getSystemService(ActivityManager::class.java)
            activities?.clearApplicationUserData() == true
        },
    )

    /**
     * Starts the wipe. True means the system accepted it and the process is
     * about to die; false means nothing was erased, and the user must be told
     * so rather than left believing the phone is clean.
     */
    fun wipe(): Boolean = runCatching(clear).getOrDefault(false)
}
