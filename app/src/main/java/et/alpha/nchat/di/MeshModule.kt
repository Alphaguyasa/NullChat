package et.alpha.nchat.di

import android.content.Context
import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import et.alpha.nchat.data.db.RoomForwardQueueStore
import et.alpha.nchat.mesh.crypto.DirectoryPacketVerifier
import et.alpha.nchat.mesh.crypto.KeyManager
import et.alpha.nchat.mesh.crypto.KeyManagerIdentity
import et.alpha.nchat.mesh.crypto.LocalIdentity
import et.alpha.nchat.mesh.protocol.PacketSigner
import et.alpha.nchat.mesh.protocol.PacketVerifier
import et.alpha.nchat.mesh.router.ForwardQueue
import et.alpha.nchat.mesh.router.ForwardQueueStore
import et.alpha.nchat.mesh.router.MeshRouter
import et.alpha.nchat.mesh.transport.NearbyTransport
import et.alpha.nchat.mesh.transport.RadioMonitor
import et.alpha.nchat.mesh.transport.Transport
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The process-lifetime scope the mesh runs in. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MeshScope

/**
 * Wires the mesh as process-wide singletons.
 *
 * These used to be constructed inside `MeshForegroundService`, which made them
 * unreachable from the UI — a screen cannot send through a router it has no
 * handle on. Hoisting them here gives the service and the ViewModels the same
 * instances, and leaves the service responsible only for lifecycle: the
 * notification, the duty cycle, and radio monitoring.
 */
@Module
@InstallIn(SingletonComponent::class)
object MeshModule {

    /**
     * Outlives every Activity and the service itself, so a message being sent
     * survives the screen that sent it going away mid-flight.
     *
     * [SupervisorJob] so one failed collector cannot take the whole mesh down.
     * The handler is the last resort under that: without one, an exception in a
     * child of a supervisor goes to the thread's uncaught handler and kills the
     * process, and the sticky service restarts it into the same failure. The
     * long-lived loops catch per item themselves, so what lands here is a single
     * relay or send. Logged by class only, since a message could carry content.
     */
    @Provides
    @Singleton
    @MeshScope
    fun provideMeshScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + logUncaught("MeshScope"))

    @Provides
    @Singleton
    fun provideKeyManager(@ApplicationContext context: Context): KeyManager = KeyManager(context)

    @Provides
    @Singleton
    fun provideLocalIdentity(identity: KeyManagerIdentity): LocalIdentity = identity

    /**
     * The signer is the identity: signing with a key other than the one the
     * peer id commits to would produce packets nobody can verify.
     */
    @Provides
    @Singleton
    fun providePacketSigner(identity: KeyManagerIdentity): PacketSigner = identity

    @Provides
    @Singleton
    fun providePacketVerifier(verifier: DirectoryPacketVerifier): PacketVerifier = verifier

    /**
     * Provided as the concrete type as well as [Transport], because the
     * coordinator needs `peerIdFor` to resolve Nearby's ephemeral endpoint ids
     * to protocol peer ids, and that mapping is not part of the interface.
     */
    @Provides
    @Singleton
    fun provideNearbyTransport(
        @ApplicationContext context: Context,
        identity: LocalIdentity,
    ): NearbyTransport = NearbyTransport(context = context, localPeerId = identity.peerId)

    @Provides
    @Singleton
    fun provideTransport(nearby: NearbyTransport): Transport = nearby

    @Provides
    @Singleton
    fun provideForwardQueueStore(store: RoomForwardQueueStore): ForwardQueueStore = store

    /**
     * Provided rather than constructor-injected because the cap, the expiry and
     * the clock are constructor defaults Hilt cannot supply.
     */
    @Provides
    @Singleton
    fun provideForwardQueue(store: ForwardQueueStore): ForwardQueue = ForwardQueue(store)

    @Provides
    @Singleton
    fun provideMeshRouter(
        transport: Transport,
        identity: LocalIdentity,
        forwardQueue: ForwardQueue,
        signer: PacketSigner,
        verifier: PacketVerifier,
        @MeshScope scope: CoroutineScope,
    ): MeshRouter = MeshRouter(
        transport = transport,
        localId = identity.peerId,
        scope = scope,
        forwardQueue = forwardQueue,
        signer = signer,
        verifier = verifier,
    )

    @Provides
    @Singleton
    fun provideRadioMonitor(@ApplicationContext context: Context): RadioMonitor =
        RadioMonitor(context)
}

/** Logs a coroutine failure that nothing else caught, instead of crashing; see `provideMeshScope`. */
internal fun logUncaught(tag: String): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, e -> Log.e(tag, "uncaught ${e::class.simpleName}") }
