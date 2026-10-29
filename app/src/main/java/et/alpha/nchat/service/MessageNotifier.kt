package et.alpha.nchat.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import et.alpha.nchat.MainActivity
import et.alpha.nchat.R
import et.alpha.nchat.data.db.directConversationId
import et.alpha.nchat.mesh.Arrival
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts one notification per direct conversation with unseen messages.
 *
 * Each conversation's notification is keyed by its id as the notification tag,
 * so a second message replaces the first instead of stacking, and opening the
 * thread can clear exactly that one.
 *
 * ## Lock screen
 *
 * `VISIBILITY_PRIVATE` with a public version that says only "New message".
 * Who wrote and what they said stay behind the lock: a phone left on a table
 * is the most likely way anyone else reads this app's messages.
 *
 * With the app lock on, the private version says no more than the public one
 * (see [MessageNotificationPolicy.title]).
 */
@Singleton
class MessageNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val notifications by lazy { context.getSystemService(NotificationManager::class.java) }

    /** Unseen messages per conversation since its notification was last cleared. */
    private val unseen = mutableMapOf<String, Int>()

    fun createChannel() {
        // IMPORTANCE_DEFAULT: makes a sound, but does not pop up over whatever
        // the user is doing. The user can raise or lower it in system settings.
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.messages_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.messages_channel_description)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        notifications.createNotificationChannel(channel)
    }

    /**
     * Notifies about [arrival] unless policy says otherwise.
     *
     * [senderName] is the peer's nickname or chosen name, if any; the caller
     * looks it up because this class has no database access of its own.
     * [hideContent] is whether the app lock is on, and [isMuted] whether the
     * user muted this thread.
     *
     * Without POST_NOTIFICATIONS on SDK 33+ the system drops the post silently,
     * which is the right outcome: the user said no.
     */
    @Synchronized
    fun onArrival(
        arrival: Arrival,
        foregroundConversationId: String?,
        senderName: String?,
        hideContent: Boolean,
        isMuted: Boolean = false,
    ) {
        if (!MessageNotificationPolicy.shouldNotify(arrival, foregroundConversationId, isMuted)) return

        val count = (unseen[arrival.conversationId] ?: 0) + 1
        unseen[arrival.conversationId] = count

        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(context.getString(R.string.messages_notification_public))
            .build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(MessageNotificationPolicy.title(arrival, senderName, hideContent))
            .setContentText(MessageNotificationPolicy.text(arrival, count, hideContent))
            .setNumber(count)
            .setContentIntent(openThread(arrival.senderId))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()

        notifications.notify(arrival.conversationId, NOTIFICATION_ID, notification)
    }

    /** Clears [conversationId]'s notification, because the user is reading it. */
    @Synchronized
    fun cancel(conversationId: String) {
        unseen.remove(conversationId)
        notifications.cancel(conversationId, NOTIFICATION_ID)
    }

    private fun openThread(peerId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_PEER_ID, peerId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // The request code keeps one PendingIntent per conversation; with a
        // shared code, UPDATE_CURRENT would point every notification's tap at
        // whichever peer messaged last.
        return PendingIntent.getActivity(
            context,
            directConversationId(peerId).hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        const val CHANNEL_ID = "messages"

        /** Constant; the conversation id in the tag tells notifications apart. */
        const val NOTIFICATION_ID = 2
    }
}
