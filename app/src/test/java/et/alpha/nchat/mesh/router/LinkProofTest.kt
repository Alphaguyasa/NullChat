package et.alpha.nchat.mesh.router

import et.alpha.nchat.mesh.protocol.Packet
import et.alpha.nchat.mesh.protocol.PacketType
import et.alpha.nchat.mesh.protocol.PacketVerifier
import et.alpha.nchat.mesh.protocol.Verification
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The router reports each verified ANNOUNCE with the link it arrived on, which
 * is what lets the coordinator tell a contact from a stranger using its name.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkProofTest {

    private fun MeshTestbed.proofsAt(node: TestNode): List<Pair<String, String>> {
        val seen = mutableListOf<Pair<String, String>>()
        detachedScope.launch {
            node.router.linkProofs.collect { seen.add(it.endpointId to it.peerId.hex()) }
        }
        return seen
    }

    @Test
    fun `a verified announcement is reported with the link it came in on`() = runTest {
        meshTest {
            val (a, b, c) = chain("A", "B", "C")
            val atB = proofsAt(b)
            val atC = proofsAt(c)
            settle()

            a.router.originate(testPacket(from = a.id, type = PacketType.ANNOUNCE))
            settle()

            assertEquals(listOf("A" to a.id.hex()), atB)
            // C hears A's announcement over B's link. Reported as such, so the
            // binder can see it does not prove B is A.
            assertEquals(listOf("B" to a.id.hex()), atC)
        }
    }

    @Test
    fun `a duplicate announcement is not reported twice`() = runTest {
        meshTest {
            val (a, _, c) = fullyConnect("A", "B", "C")
            val atC = proofsAt(c)
            settle()

            a.router.originate(testPacket(from = a.id, type = PacketType.ANNOUNCE))
            settle()

            // Direct from A, then again relayed by B after jitter: only the first.
            assertEquals(listOf("A" to a.id.hex()), atC)
        }
    }

    @Test
    fun `a forged announcement proves nothing`() = runTest {
        meshTest {
            verifier = PacketVerifier { Verification.INVALID }
            val (a, b) = chain("A", "B")
            val atB = proofsAt(b)
            settle()

            a.router.originate(testPacket(from = a.id, type = PacketType.ANNOUNCE))
            settle()

            assertTrue(atB.isEmpty())
        }
    }

    @Test
    fun `other packet types prove nothing`() = runTest {
        meshTest {
            val (a, b) = chain("A", "B")
            val atB = proofsAt(b)
            settle()

            a.router.originate(testPacket(from = a.id, type = PacketType.MESSAGE))
            a.router.originate(testPacket(from = a.id, type = PacketType.PROFILE))
            settle()

            assertTrue(atB.isEmpty())
        }
    }

    @Test
    fun `a link proof is reported with its digest and goes no further`() = runTest {
        meshTest {
            val (a, b, c) = chain("A", "B", "C")
            val digests = mutableListOf<Pair<String, ByteArray?>>()
            detachedScope.launch { b.router.linkProofs.collect { digests.add(it.endpointId to it.tokenDigest) } }
            val atC = proofsAt(c)
            settle()

            val digest = ByteArray(32) { 7 }
            // ttl is unsigned, so a hostile sender may claim more than 1.
            val packet = testPacket(
                from = a.id,
                to = Packet.BROADCAST_ID,
                type = PacketType.LINK_PROOF,
                payload = ByteArray(32) { 1 } + digest,
            )
            assertTrue(a.router.sendOnLink("B", packet))
            settle()

            assertEquals(1, digests.size)
            assertEquals("A", digests.single().first)
            assertArrayEquals(digest, digests.single().second)
            assertTrue(b.delivered.isEmpty(), "a link proof is not a message")
            assertTrue(atC.isEmpty(), "a link proof is never relayed")
            assertTrue(c.delivered.isEmpty())
        }
    }

    @Test
    fun `a forged link proof proves nothing`() = runTest {
        meshTest {
            verifier = PacketVerifier { Verification.INVALID }
            val (a, b) = chain("A", "B")
            val atB = proofsAt(b)
            settle()

            a.router.sendOnLink(
                "B",
                testPacket(from = a.id, type = PacketType.LINK_PROOF, payload = ByteArray(64)),
            )
            settle()

            assertTrue(atB.isEmpty())
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
