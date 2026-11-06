package et.alpha.nchat.ui.pair

import et.alpha.nchat.data.repo.PeerRepository
import et.alpha.nchat.mesh.crypto.LocalIdentity
import et.alpha.nchat.mesh.crypto.PublicKeyDirectory
import et.alpha.nchat.mesh.protocol.Announcement
import et.alpha.nchat.mesh.protocol.toPairingUri
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QrPairViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val me = Announcement(ByteArray(32) { 1 }, ByteArray(32) { 2 })
    private val them = Announcement(ByteArray(32) { 3 }, ByteArray(32) { 4 })

    private val identity = object : LocalIdentity {
        override val peerId: ByteArray get() = me.peerId
        override val publicKey: ByteArray get() = me.signingKey
        override val announcement: Announcement get() = me
    }

    // Relaxed for recordSeen, whose return type is the DAO's and of no interest here.
    private val peers = mockk<PeerRepository>(relaxed = true) {
        coEvery { markVerified(any(), any()) } returns true
    }

    private lateinit var directory: PublicKeyDirectory
    private lateinit var viewModel: QrPairViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        directory = PublicKeyDirectory()
        viewModel = QrPairViewModel(identity, peers, directory)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a code held in view is saved once`() = runTest(dispatcher) {
        // The camera reads the same code on every frame while the first save is
        // still running; before the fix each frame launched its own save.
        repeat(5) { viewModel.onScanned(them.toPairingUri()) }
        advanceUntilIdle()

        coVerify(exactly = 1) { peers.markVerified(any(), any()) }
        coVerify(exactly = 1) { peers.recordSeen(any(), any(), any(), any(), any()) }
        assertTrue(viewModel.state.value.outcome is ScanOutcome.Paired)
    }

    @Test
    fun `scan another lets the next code through`() = runTest(dispatcher) {
        viewModel.onScanned(them.toPairingUri())
        advanceUntilIdle()

        viewModel.reset()
        viewModel.onScanned(them.toPairingUri())
        advanceUntilIdle()

        coVerify(exactly = 2) { peers.markVerified(any(), any()) }
    }

    @Test
    fun `our own code and foreign codes save nothing`() = runTest(dispatcher) {
        viewModel.onScanned(me.toPairingUri())
        advanceUntilIdle()
        assertEquals(ScanOutcome.Ourselves, viewModel.state.value.outcome)

        viewModel.onScanned("https://example.com")
        advanceUntilIdle()
        assertEquals(ScanOutcome.NotAPairingCode, viewModel.state.value.outcome)

        coVerify(exactly = 0) { peers.markVerified(any(), any()) }
    }

    @Test
    fun `an altered code is refused and does not block a genuine one`() = runTest(dispatcher) {
        directory.record(them)
        val altered = Announcement(them.signingKey, ByteArray(32) { 9 })

        viewModel.onScanned(altered.toPairingUri())
        advanceUntilIdle()
        assertEquals(ScanOutcome.CodesDontMatch, viewModel.state.value.outcome)

        viewModel.onScanned(them.toPairingUri())
        advanceUntilIdle()
        assertTrue(viewModel.state.value.outcome is ScanOutcome.Paired)
        coVerify(exactly = 1) { peers.markVerified(any(), any()) }
    }
}
