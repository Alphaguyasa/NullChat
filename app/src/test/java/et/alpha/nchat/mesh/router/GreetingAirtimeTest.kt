package et.alpha.nchat.mesh.router

import et.alpha.nchat.mesh.protocol.PacketType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a greeting costs the mesh when a link forms; see `GreetingCache`.
 * A copy of the last greeting keeps its id, so the seen-cache stops it where it
 * is already known and lets it through where it is not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GreetingAirtimeTest {

    @Test
    fun `a copy sent to a peer that already heard it costs one packet`() = runTest {
        meshTest {
            val (a, _, _, d) = chain("A", "B", "C", "D")
            settle()
            val greeting = testPacket(from = a.id, type = PacketType.ANNOUNCE)
            a.router.originate(greeting)
            settle()

            // A and C come into range of each other directly.
            link("A", "C")
            settle()
            val before = transmissions.size
            a.router.sendOnLink("C", greeting)
            settle()

            assertEquals(listOf("A" to "C"), hops().drop(before))
            assertEquals(1, d.delivered.count { it.type == PacketType.ANNOUNCE })
        }
    }

    @Test
    fun `a fresh greeting on the same link floods the whole mesh again`() = runTest {
        // The cost the copy avoids: every node relays a new id once.
        meshTest {
            val (a, _, _, _) = chain("A", "B", "C", "D")
            a.router.originate(testPacket(from = a.id, type = PacketType.ANNOUNCE))
            settle()
            link("A", "C")
            settle()

            val before = transmissions.size
            a.router.originate(testPacket(from = a.id, type = PacketType.ANNOUNCE))
            settle()

            assertTrue(transmissions.size - before >= 4, "fresh flood took ${transmissions.size - before}")
        }
    }

    @Test
    fun `a copy reaches the part of the mesh that never heard it`() = runTest {
        // Two groups meet: A's greeting went round A-B, and E-F never heard it.
        meshTest {
            val (a, _) = chain("A", "B")
            val (_, f) = chain("E", "F")
            settle()
            val greeting = testPacket(from = a.id, type = PacketType.ANNOUNCE)
            a.router.originate(greeting)
            settle()

            link("A", "E")
            settle()
            a.router.sendOnLink("E", greeting)
            settle()

            assertEquals(1, node("E").delivered.count { it.messageId.contentEquals(greeting.messageId) })
            assertEquals(1, f.delivered.count { it.messageId.contentEquals(greeting.messageId) })
        }
    }
}
