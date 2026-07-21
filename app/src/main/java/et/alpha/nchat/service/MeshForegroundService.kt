package et.alpha.nchat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import et.alpha.nchat.MainActivity
import et.alpha.nchat.R
import et.alpha.nchat.mesh.crypto.LocalIdentity
import et.alpha.nchat.mesh.crypto.PlaceholderLocalIdentity
import et.alpha.nchat.mesh.router.MeshRouter
import et.alpha.nchat.mesh.transport.DutyCycle
import et.alpha.nchat.mesh.transport.NearbyTransport
import et.alpha.nchat.mesh.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Keeps the mesh alive while the app is in the background.
 *
 * Owns the lifecycle of exactly two things — the [Transport] and the
 * [MeshRouter] — and nothing else. It creates them in [onCreate], runs the
 * discovery duty cycle, keeps the notification's peer count current, and tears
 * everything down in [onDestroy].
 *
 * The service is the only place these objects are constructed, so there is one
 * router and one transport per process no matter how many times the app is
 * launched.
 */
class MeshForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob())

    private lateinit var identity: LocalIdentity
    private lateinit var transport: NearbyTransport
    private lateinit var router: MeshRouter

    private val dutyCycle = DutyCycle()

    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "starting")

        createNotificationChannel()
        // Posted before anything else can fail: a foreground service that does not
        // call startForeground within a few seconds of being started is killed.
        startForeground(NOTIFICATION_ID, buildNotification(peerCount = 0))

        identity = PlaceholderLocalIdentity(this)
        transport = NearbyTransport(context = this, localPeerId = identity.peerId)
        router = MeshRouter(transport = transport, localId = identity.peerId, scope = scope)

        router.start()
        observePeerCount()
        runDutyCycle()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: if the system reclaims us under memory pressure, come back.
        // A mesh node that silently stops relaying is worse than one that restarts.
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "stopping")
        // The transport holds Nearby connections that outlive the process's
        // coroutines, so it is stopped explicitly before the scope is cancelled.
        // runBlocking is acceptable here and nowhere else: onDestroy is the last
        // chance to release the radio, and the calls are non-blocking underneath.
        runBlocking { runCatching { transport.stop() } }
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Republishes the notification whenever the reachable peer set changes.
     *
     * Driven by transport state rather than connection events because the router
     * consumes those, and because a count is state: after a reconnect storm we
     * want the current number, not a replay of every transition.
     */
    private fun observePeerCount() = scope.launch {
        transport.connectedPeers.collect { peers ->
            Log.d(TAG, "peer count now ${peers.size}")
            notifications.notify(NOTIFICATION_ID, buildNotification(peers.size))
        }
    }

    /**
     * Scans in bursts instead of continuously.
     *
     * Advertising is started once and left up; only discovery cycles. See
     * [DutyCycle] for why, and [Transport.stopDiscovery] for why advertising
     * must not cycle with it.
     */
    private fun runDutyCycle() = scope.launch {
        runCatching { transport.startAdvertising() }
            .onFailure { Log.e(TAG, "advertising failed to start: ${it.message}") }

        // A peer appearing resets the backoff, so a device that has gone quiet for
        // five minutes returns to fast scanning the moment anyone turns up.
        launch {
            transport.connectedPeers.collectLatest { peers ->
                if (peers.isNotEmpty()) dutyCycle.onPeerFound()
            }
        }

        while (isActive) {
            val backedOff = dutyCycle.isBackedOff()
            runCatching { transport.startDiscovery() }
                .onFailure { Log.e(TAG, "discovery failed to start: ${it.message}") }

            delay(dutyCycle.scanMillis)

            runCatching { transport.stopDiscovery() }
                .onFailure { Log.w(TAG, "stopDiscovery failed: ${it.message}") }

            val idle = dutyCycle.idleMillis()
            Log.d(TAG, "scan window closed, idling ${idle}ms (backedOff=$backedOff)")
            delay(idle)
        }
    }

    private fun createNotificationChannel() {
        // IMPORTANCE_LOW: ongoing and silent. This notification exists because the
        // platform requires one, not because the user needs to be interrupted.
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.mesh_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.mesh_channel_description)
            setShowBadge(false)
        }
        notifications.createNotificationChannel(channel)
    }

    private fun buildNotification(peerCount: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val text = resources.getQuantityString(
            R.plurals.mesh_peer_count,
            peerCount,
            peerCount,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.mesh_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // The peer count is not sensitive, but nothing about who those peers
            // are ever reaches the lock screen.
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    companion object {

        private const val TAG = "MeshService"
        private const val CHANNEL_ID = "mesh"
        private const val NOTIFICATION_ID = 1

        /** Starts the mesh, if it is not already running. Safe to call repeatedly. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, MeshForegroundService::class.java))
        }

        /** Stops the mesh and releases the radio. */
        fun stop(context: Context) {
            context.stopService(Intent(context, MeshForegroundService::class.java))
        }
    }
}
