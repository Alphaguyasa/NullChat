package et.alpha.nchat.ui.chat

import android.content.Context
import et.alpha.nchat.data.media.AttachmentStore
import et.alpha.nchat.data.media.ImageScaler
import et.alpha.nchat.data.repo.MessageRepository
import et.alpha.nchat.data.repo.PeerRepository
import et.alpha.nchat.mesh.MeshCoordinator
import et.alpha.nchat.mesh.TransferTracker
import et.alpha.nchat.service.ForegroundConversation
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelThreadTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `thread rows are built off the main thread`() = runTest(dispatcher) {
        val mainThread = Thread.currentThread()
        val builtOn = CompletableDeferred<Thread>()

        val messages = mockk<MessageRepository>(relaxed = true) {
            every { observeMessages(any()) } returns flow {
                builtOn.complete(Thread.currentThread())
                emit(emptyList())
            }
            every { observeTimerChanges(any()) } returns flowOf(emptyList())
            every { observeConversation(any()) } returns flowOf(null)
        }
        val coordinator = mockk<MeshCoordinator>(relaxed = true) {
            every { boundPeers } returns MutableStateFlow(emptySet())
        }
        val tracker = mockk<TransferTracker> {
            every { transfers } returns MutableStateFlow(emptyMap())
        }
        val viewModel = ChatViewModel(
            messages = messages,
            peers = mockk<PeerRepository>(relaxed = true),
            coordinator = coordinator,
            imageScaler = mockk<ImageScaler>(relaxed = true),
            attachments = mockk<AttachmentStore>(relaxed = true),
            foregroundConversation = ForegroundConversation(),
            context = mockk<Context>(relaxed = true),
            transferTracker = tracker,
        )

        backgroundScope.launch { viewModel.state.collect {} }

        // The test dispatcher stands in for Main here. Before the fix the whole
        // pipeline, `threadRows` included, ran on whichever thread collected it.
        assertNotSame(mainThread, builtOn.await())
    }
}
