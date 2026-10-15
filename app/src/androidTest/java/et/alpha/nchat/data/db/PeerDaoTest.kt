package et.alpha.nchat.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import et.alpha.nchat.data.db.DatabaseTestFixtures.PEER_A
import et.alpha.nchat.data.db.DatabaseTestFixtures.PEER_B
import et.alpha.nchat.data.db.DatabaseTestFixtures.T0
import et.alpha.nchat.data.db.DatabaseTestFixtures.peer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PeerDaoTest {

    private lateinit var db: NChatDatabase
    private lateinit var peers: PeerDao

    @Before
    fun setUp() {
        db = DatabaseTestFixtures.createDatabase()
        peers = db.peerDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun insertsAndReadsBackAPeerIncludingItsKey() = runTest {
        val key = ByteArray(32) { it.toByte() }

        peers.insert(peer(id = PEER_A, displayName = "sam", publicKey = key))

        val stored = peers.findById(PEER_A)
        assertEquals("sam", stored?.displayName)
        assertArrayEquals(key, stored?.publicKey)
    }

    @Test
    fun returnsNullForAnUnknownPeer() = runTest {
        assertNull(peers.findById("deadbeefdeadbeef"))
    }

    @Test
    fun recordSeenCreatesAPeerOnFirstContact() = runTest {
        peers.recordSeen(id = PEER_A, displayName = "sam", now = T0)

        val stored = peers.findById(PEER_A)
        assertEquals("sam", stored?.displayName)
        assertEquals(T0, stored?.firstSeenAt)
        assertEquals(T0, stored?.lastSeenAt)
        assertFalse(stored!!.isVerified)
    }

    @Test
    fun recordSeenAdvancesLastSeenButKeepsFirstSeen() = runTest {
        peers.recordSeen(id = PEER_A, now = T0)

        peers.recordSeen(id = PEER_A, now = T0 + 60_000)

        val stored = peers.findById(PEER_A)
        assertEquals(T0, stored?.firstSeenAt)
        assertEquals(T0 + 60_000, stored?.lastSeenAt)
    }

    @Test
    fun recordSeenNeverClearsTheVerifiedFlag() = runTest {
        // Security-relevant: an attacker who can spoof an ANNOUNCE for this peer
        // id must not be able to strip the verification the user established by
        // scanning a QR code, because the UI reports that as an authenticated
        // channel.
        peers.recordSeen(id = PEER_A, now = T0)
        peers.setVerified(PEER_A, true)

        peers.recordSeen(id = PEER_A, displayName = "impostor", now = T0 + 1000)

        assertTrue(peers.findById(PEER_A)!!.isVerified)
    }

    @Test
    fun recordSeenDoesNotEraseKnownFieldsWithNulls() = runTest {
        val key = ByteArray(32) { 7 }
        peers.recordSeen(id = PEER_A, displayName = "sam", publicKey = key, now = T0)

        // A bare ANNOUNCE carrying neither name nor key.
        peers.recordSeen(id = PEER_A, displayName = null, publicKey = null, now = T0 + 5)

        val stored = peers.findById(PEER_A)
        assertEquals("sam", stored?.displayName)
        assertArrayEquals(key, stored?.publicKey)
    }

    @Test
    fun agreementKeyIsStoredAndNotReplaced() = runTest {
        val first = ByteArray(32) { 4 }
        peers.recordSeen(id = PEER_A, agreementKey = first, now = T0)

        // A bare ANNOUNCE leaves it alone, and a different key does not displace
        // it: PublicKeyDirectory is first-wins and is warmed from this column.
        peers.recordSeen(id = PEER_A, now = T0 + 5)
        peers.recordSeen(id = PEER_A, agreementKey = ByteArray(32) { 5 }, now = T0 + 10)

        assertArrayEquals(first, peers.findById(PEER_A)?.agreementKey)
    }

    @Test
    fun agreementKeyLearnedLaterIsFilledIn() = runTest {
        peers.recordSeen(id = PEER_A, now = T0)

        val key = ByteArray(32) { 6 }
        peers.recordSeen(id = PEER_A, agreementKey = key, now = T0 + 5)

        assertArrayEquals(key, peers.findById(PEER_A)?.agreementKey)
    }

    @Test
    fun recordSeenFillsInFieldsLearnedLater() = runTest {
        peers.recordSeen(id = PEER_A, now = T0)

        val key = ByteArray(32) { 3 }
        peers.recordSeen(id = PEER_A, displayName = "sam", publicKey = key, now = T0 + 5)

        val stored = peers.findById(PEER_A)
        assertEquals("sam", stored?.displayName)
        assertArrayEquals(key, stored?.publicKey)
    }

    @Test
    fun ordersPeersByMostRecentlySeen() = runTest {
        peers.recordSeen(id = PEER_A, now = T0)
        peers.recordSeen(id = PEER_B, now = T0 + 100)

        assertEquals(listOf(PEER_B, PEER_A), peers.observeAll().first().map { it.id })

        peers.touch(PEER_A, now = T0 + 200)

        assertEquals(listOf(PEER_A, PEER_B), peers.observeAll().first().map { it.id })
    }

    @Test
    fun observeAllEmitsAgainWhenAPeerAppears() = runTest {
        peers.observeAll().test {
            assertEquals(emptyList<PeerEntity>(), awaitItem())

            peers.recordSeen(id = PEER_A, now = T0)

            assertEquals(listOf(PEER_A), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeByIdEmitsNullUntilThePeerExists() = runTest {
        peers.observeById(PEER_A).test {
            assertNull(awaitItem())

            peers.recordSeen(id = PEER_A, displayName = "sam", now = T0)

            assertEquals("sam", awaitItem()?.displayName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun filtersPeersByPresenceWindow() = runTest {
        // Presence is derived from lastSeenAt, because a mesh peer that walks out
        // of range never sends a disconnect.
        peers.recordSeen(id = PEER_A, now = T0)
        peers.recordSeen(id = PEER_B, now = T0 - 300_000)

        val active = peers.observeActiveSince(since = T0 - 120_000).first()

        assertEquals(listOf(PEER_A), active.map { it.id })
    }

    @Test
    fun togglesVerificationAndReportsWhetherAPeerMatched() = runTest {
        peers.recordSeen(id = PEER_A, now = T0)

        assertEquals(1, peers.setVerified(PEER_A, true))
        assertTrue(peers.findById(PEER_A)!!.isVerified)

        assertEquals(1, peers.setVerified(PEER_A, false))
        assertFalse(peers.findById(PEER_A)!!.isVerified)

        assertEquals(0, peers.setVerified("absentabsentabse", true))
    }

    @Test
    fun forgetsAPeer() = runTest {
        peers.recordSeen(id = PEER_A, now = T0)

        assertEquals(1, peers.deleteById(PEER_A))
        assertEquals(0, peers.deleteById(PEER_A))
        assertNull(peers.findById(PEER_A))
    }
}
