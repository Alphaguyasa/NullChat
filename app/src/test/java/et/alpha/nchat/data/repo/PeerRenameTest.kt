package et.alpha.nchat.data.repo

import et.alpha.nchat.data.db.PeerDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PeerRenameTest {

    private val peer = "a1b2c3d4e5f60718"
    private val dao = mockk<PeerDao> {
        coEvery { setNickname(any(), any()) } returns 1
    }
    private val repository = PeerRepository(dao)

    @Test
    fun `a nickname is stored trimmed`() = runTest {
        assertTrue(repository.rename(peer, "  Mom  "))
        coVerify { dao.setNickname(peer, "Mom") }
    }

    // A right-to-left override would let a typed name render as a different one.
    @Test
    fun `hidden format characters are stripped`() = runTest {
        repository.rename(peer, "Mo‮m")
        coVerify { dao.setNickname(peer, "Mom") }
    }

    @Test
    fun `blank input removes the nickname`() = runTest {
        repository.rename(peer, "   ")
        coVerify { dao.setNickname(peer, null) }
    }

    @Test
    fun `renaming an unknown peer reports it`() = runTest {
        coEvery { dao.setNickname(any(), any()) } returns 0
        assertFalse(repository.rename(peer, "Mom"))
    }
}
