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
import dagger.hilt.android.AndroidEntryPoint
import et.alpha.nchat.MainActivity
import et.alpha.nchat.R
import et.alpha.nchat.data.db.preferredName
import et.alpha.nchat.di.logUncaught
import et.alpha.nchat.data.settings.AppLockStore
import et.alpha.nchat.data.settings.BatteryModeStore
import et.alpha.nchat.data.settings.MeshEnabledStore
import et.alpha.nchat.data.repo.MessageRepository
import et.alpha.nchat.data.repo.PeerRepository
import et.alpha.nchat.mesh.MeshCoordinator
import et.alpha.nchat.mesh.router.MeshRouter
import et.alpha.nchat.mesh.transport.DutyCycle
import et.alpha.nchat.mesh.transport.NearbyTransport
import et.alpha.nchat.mesh.transport.PowerSaveMonitor
import et.alpha.nchat.mesh.transport.RadioBlocker
import et.alpha.nchat.mesh.transport.RadioMonitor
import et.alpha.nchat.mesh.transport.RadioState
import et.alpha.nchat.mesh.transport.ScanLoop
import et.alpha.nchat.mesh.transport.Transport
import et.alpha.nchat.ui.permissions.MeshPermissions
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Keeps the mesh alive while the app is in the background.
 *
 * Runs the discovery duty cycle, keeps the notification current, and pauses the
 * mesh when the radio goes away. It no longer *constructs* the [Transport] or
 * [MeshRouter]: those are process-wide singletons from `MeshModule`, because the
 * UI has to be able to send through the same router this service is driving.
 *
 * What that leaves is a lifecycle owner, not a container.
 */
@AndroidEntryPoint
class MeshForegroundService : Service() {

    @Inject lateinit var coordinator: MeshCoordinator

    @Inject lateinit var transport: NearbyTransport

    @Inject lateinit var radioMonitor: RadioMonitor

    @Inject lateinit var messageNotifier: MessageNotifier

    @Inject lateinit var foregroundConversation: ForegroundConversation

    @Inject lateinit var peerRepository: PeerRepository

    @Inject lateinit var messageRepository: MessageRepository

    @Inject lateinit var appLock: AppLockStore

    @Inject lateinit var batteryMode: BatteryModeStore

    @Inject lateinit var powerSave: PowerSaveMonitor

    @Inject lateinit var meshEnabled: MeshEnabledStore

    private val scope = CoroutineScope(SupervisorJob() + logUncaught(TAG))

    private lateinit var radio: StateFlow<RadioState>

    private val dutyCycle = DutyCycle()
    private val scanLoop by lazy { ScanLoop(transport, dutyCycle) { Log.d(TAG, it) } }

    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    /**
     * False when [onCreate] refused to start (permissions missing, or the system
     * would not let us go foreground). Such an instance only waits for its own
     * [stopSelf] to land, and must neither ask to be restarted nor touch the
     * transport it never started.
     */
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "starting")

        // START_STICKY brings this service back after the process dies, and
        // revoking a permission in Settings is exactly what kills it. Without the
        // permissions the mesh cannot scan anyway, and on API 34+ startForeground
        // would throw on every restart. The app starts us again once its
        // permission gate passes.
        val missing = MeshPermissions.missing(this)
        if (missing.isNotEmpty()) {
            Log.w(TAG, "not starting: ${missing.size} permission(s) missing")
            stopSelf()
            return
        }
        // The user turned the mesh off. start() already checks, but a start
        // request can race the switch, and it must not win.
        if (!meshEnabled.enabled.value) {
            Log.i(TAG, "not starting: the mesh is turned off")
            stopSelf()
            return
        }

        createNotificationChannel()
        messageNotifier.createChannel()

        // Sampled before the first post so the notification is accurate on its
        // very first frame, rather than flashing "paused" on a healthy device.
        val initialRadio = radioMonitor.current()

        // Posted before anything else can fail: a foreground service that does not
        // call startForeground within a few seconds of being started is killed.
        try {
            startForeground(NOTIFICATION_ID, buildNotification(peerCount = 0, initialRadio))
        } catch (e: Exception) {
            if (!isForegroundStartRefusal(e)) throw e
            Log.w(TAG, "foreground start refused: ${e::class.simpleName}")
            stopSelf()
            return
        }
        running = true

        radio = radioMonitor.state.stateIn(scope, SharingStarted.Eagerly, initialRadio)

        // Idempotent: START_STICKY can recreate this service against singletons
        // that are already running, and MeshRouter.start() throws on a second call.
        coordinator.ensureStarted()
        observeNotificationState()
        notifyArrivals()
        runDutyCycle()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The notification's Stop action. Stored first, so neither the app's
        // next start nor a sticky restart brings the mesh back unasked.
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "stop requested from the notification")
            meshEnabled.setEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }
        // START_STICKY: if the system reclaims us under memory pressure, come back.
        // A mesh node that silently stops relaying is worse than one that restarts.
        // A refused start is not reclaimed, it is unable to run: restarting it
        // would only refuse again.
        return if (running) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "stopping")
        // The transport holds Nearby connections that outlive the process's
        // coroutines, so it is stopped explicitly before the scope is cancelled.
        // runBlocking is acceptable here and nowhere else: onDestroy is the last
        // chance to release the radio, and the calls are non-blocking underneath.
        if (running) runBlocking { runCatching { transport.stop() } }
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Republishes the notification whenever the peer count or the radio state
     * changes.
     *
     * Peers come from transport state rather than connection events because the
     * router consumes those, and because a count is state: after a reconnect
     * storm we want the current number, not a replay of every transition.
     */
    private fun observeNotificationState() = scope.launch {
        combine(transport.connectedPeers, radio) { peers, radioState ->
            peers.size to radioState
        }.collect { (peerCount, radioState) ->
            Log.d(TAG, "peers=$peerCount blockers=${radioState.blockers}")
            notifications.notify(NOTIFICATION_ID, buildNotification(peerCount, radioState))
        }
    }

    /**
     * Notifies about new messages, and clears a thread's notification as soon
     * as the user opens it.
     *
     * Lives here because this service is what keeps the process alive while
     * the app is in the background, which is the only time a notification is
     * worth anything.
     */
    private fun notifyArrivals() {
        scope.launch {
            coordinator.arrivals.collect { arrival ->
                // Caught per arrival: one failed lookup or notification must not
                // end this collector and silence every later message.
                try {
                    // Read per arrival rather than captured once, so turning the
                    // lock on takes effect from the very next message.
                    val hideContent = appLock.enabled.value
                    val senderName = if (hideContent) null else peerRepository.findPeer(arrival.senderId)?.preferredName
                    messageNotifier.onArrival(
                        arrival,
                        foregroundConversation.current.value,
                        senderName,
                        hideContent,
                        // Read per arrival too, so a mute applies to the next message.
                        isMuted = messageRepository.isMuted(arrival.conversationId),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "arrival notification failed: ${e::class.simpleName}")
                }
            }
        }
        scope.launch {
            foregroundConversation.current.filterNotNull().collect { messageNotifier.cancel(it) }
        }
        scope.launch {
            coordinator.withdrawn.collect { messageNotifier.cancel(it) }
        }
    }

    /**
     * Scans in bursts instead of continuously, and not at all while the radio
     * cannot work.
     *
     * Advertising is started once per readiness window and left up (a failed
     * start is retried each scan window, see [ScanLoop]); only discovery
     * cycles. See [DutyCycle] for why, and [Transport.stopDiscovery] for why
     * advertising must not cycle with it.
     */
    private fun runDutyCycle() = scope.launch {
        // The user's battery mode, applied to the schedule from the next rest.
        launch {
            combine(batteryMode.mode, powerSave.saverOn) { mode, systemSaver -> mode.saves(systemSaver) }
                .collect { saving ->
                    Log.i(TAG, "battery saver schedule ${if (saving) "on" else "off"}")
                    dutyCycle.isSaving = saving
                }
        }

        // A peer appearing resets the backoff, so a device that has gone quiet for
        // five minutes returns to fast scanning the moment anyone turns up.
        launch {
            transport.connectedPeers.collectLatest { peers ->
                if (peers.isNotEmpty()) dutyCycle.onPeerFound()
            }
        }

        while (isActive) {
            // Waiting beats scanning into a disabled radio: Nearby would fail every
            // single window with a misleading permission error, and the wakeups
            // would cost battery to accomplish exactly nothing.
            if (!radio.value.isReady) {
                Log.i(TAG, "radio not ready (${radio.value.blockers}), pausing mesh")
                radio.first { it.isReady }
                Log.i(TAG, "radio ready, resuming mesh")
            }

            scanLoop.runWhileReady { radio.value.isReady }

            // The radio went away mid-session. Release everything rather than hold
            // endpoints that cannot possibly still be live.
            Log.i(TAG, "radio lost, releasing transport")
            runCatching { transport.stop() }
                .onFailure { Log.w(TAG, "transport stop failed: ${it.message}") }
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

    private fun buildNotification(peerCount: Int, radioState: RadioState): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // The whole point of surfacing blockers here: the service runs with no UI
        // on screen, so this notification is the only place a user can find out
        // why they have been sitting at "0 devices in range" for an hour.
        val blockers = radioState.blockers
        val text = when {
            blockers.size > 1 -> getString(R.string.mesh_notification_blocked_both)
            blockers.firstOrNull() == RadioBlocker.BLUETOOTH_OFF ->
                getString(R.string.mesh_notification_blocked_bluetooth)
            blockers.firstOrNull() == RadioBlocker.LOCATION_SERVICES_OFF ->
                getString(R.string.mesh_notification_blocked_location)
            else -> resources.getQuantityString(R.plurals.mesh_peer_count, peerCount, peerCount)
        }

        // An explicit intent to this service, immutable: nothing else can fill
        // it in, and only this app's notification holds it.
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, MeshForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(
                getString(
                    if (blockers.isEmpty()) R.string.mesh_notification_title
                    else R.string.mesh_notification_title_paused,
                ),
            )
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .addAction(0, getString(R.string.mesh_notification_stop), stop)
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
        private const val ACTION_STOP = "et.alpha.nchat.action.STOP_MESH"

        /** Starts the mesh, if it is not already running. Safe to call repeatedly. */
        fun start(context: Context) {
            // A service started this way that never reaches startForeground is
            // an app crash on some API levels, so do not start one that would
            // refuse. The permission gate normally guarantees this already.
            if (MeshPermissions.missing(context).isNotEmpty()) return
            context.startForegroundService(Intent(context, MeshForegroundService::class.java))
        }

        /**
         * Stops the mesh and releases the radio. Callers store the user's
         * choice in [MeshEnabledStore] first; this only acts on it.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, MeshForegroundService::class.java))
        }
    }
}
