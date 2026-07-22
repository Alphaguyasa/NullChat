package et.alpha.nchat.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import et.alpha.nchat.data.db.RoomForwardQueueStore
import et.alpha.nchat.mesh.crypto.LocalIdentity
import et.alpha.nchat.mesh.crypto.PlaceholderLocalIdentity
import et.alpha.nchat.mesh.router.ForwardQueue
import et.alpha.nchat.mesh.router.ForwardQueueStore
import et.alpha.nchat.mesh.router.MeshRouter
import et.alpha.nchat.mesh.transport.NearbyTransport
import et.alpha.nchat.mesh.transport.RadioMonitor
import et.alpha.nchat.mesh.transport.Transport
import javax.inject.Qualifier
import javax.inject.Singleton
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
     */
    @Provides
    @Singleton
    @MeshScope
    fun provideMeshScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideLocalIdentity(@ApplicationContext context: Context): LocalIdentity =
        PlaceholderLocalIdentity(context)

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
        @MeshScope scope: CoroutineScope,
    ): MeshRouter = MeshRouter(
        transport = transport,
        localId = identity.peerId,
        scope = scope,
        forwardQueue = forwardQueue,
    )

    @Provides
    @Singleton
    fun provideRadioMonitor(@ApplicationContext context: Context): RadioMonitor =
        RadioMonitor(context)
}
