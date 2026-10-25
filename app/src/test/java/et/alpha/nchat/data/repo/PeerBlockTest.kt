package et.alpha.nchat.data.repo

import et.alpha.nchat.data.db.PeerDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PeerBlockTest {

    private val peer = "a1b2c3d4e5f60718"
    private val dao = mockk<PeerDao> {
        coEvery { setBlocked(any(), any()) } returns 1
    }
    private val repository = PeerRepository(dao)

    @Test
    fun `block and unblock reach the table`() = runTest {
        assertTrue(repository.block(peer))
        coVerify { dao.setBlocked(peer, true) }

        assertTrue(repository.block(peer, blocked = false))
        coVerify { dao.setBlocked(peer, false) }
    }

    @Test
    fun `blocking an unknown peer reports it`() = runTest {
        coEvery { dao.setBlocked(any(), any()) } returns 0
        assertFalse(repository.block(peer))
    }

    // The inbound path asks this for every message; a sender with no row yet
    // is a stranger, not a blocked one, and must still be delivered.
    @Test
    fun `a peer with no row is not blocked`() = runTest {
        coEvery { dao.isBlocked(peer) } returns null
        assertFalse(repository.isBlocked(peer))
    }

    @Test
    fun `a blocked peer reads as blocked`() = runTest {
        coEvery { dao.isBlocked(peer) } returns true
        assertTrue(repository.isBlocked(peer))
    }
}
