package et.alpha.nchat.mesh.router

import et.alpha.nchat.mesh.protocol.Packet
import et.alpha.nchat.mesh.protocol.PacketCodec
import et.alpha.nchat.mesh.protocol.PacketVerifier
import et.alpha.nchat.mesh.protocol.Verification
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the router does with a signature, which is three different things.
 *
 * The distinction that matters and is easy to get wrong: a packet we can prove
 * is forged must be dropped outright, while a packet we simply cannot check —
 * the normal case for a relay carrying traffic between two strangers — must
 * still be forwarded, or multi-hop delivery stops working entirely.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PacketVerificationTest {

    /** Answers with whatever the test told it to, per sender. */
    private class ScriptedVerifier(
        private val default: Verification = Verification.VALID,
    ) : PacketVerifier {
        private val bySender = mutableMapOf<String, Verification>()

        fun say(sender: ByteArray, verdict: Verification) {
            bySender[sender.hex()] = verdict
        }

        override fun verify(packet: Packet): Verification =
            bySender[packet.senderId.hex()] ?: default

        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `a validly signed message is delivered`() = runTest {
        meshTest {
            verifier = ScriptedVerifier(Verification.VALID)
            val (a, b) = chain("A", "B")
            settle()

            a.router.originate(testPacket(from = a.id, to = b.id))
            settle()

            assertEquals(1, b.delivered.size)
            assertEquals(0, b.router.droppedUnverified)
        }
    }

    @Test
    fun `a forged message is dropped and never delivered`() = runTest {
        meshTest {
            verifier = ScriptedVerifier(Verification.INVALID)
            val (a, b) = chain("A", "B")
            settle()

            a.router.originate(testPacket(from = a.id, to = b.id))
            settle()

            assertTrue(b.delivered.isEmpty(), "a packet signed by the wrong key must not be shown")
            assertEquals(1, b.router.droppedUnverified)
        }
    }

    @Test
    fun `a forged message is not relayed onward`() = runTest {
        meshTest {
            verifier = ScriptedVerifier(Verification.INVALID)
            val (a, b, c) = chain("A", "B", "C")
            settle()

            a.router.originate(testPacket(from = a.id, to = c.id))
            settle()

            // Asserted on the wire, not on delivery: B must not spend bandwidth
            // carrying something it has proved is forged.
            assertTrue(
                hopsFrom("B").isEmpty(),
                "B should not relay a packet it knows is forged",
            )
            assertTrue(c.delivered.isEmpty())
        }
    }

    @Test
    fun `a message from an unknown sender is relayed but not delivered`() = runTest {
        meshTest {
            verifier = ScriptedVerifier(Verification.UNKNOWN_SENDER)
            val (a, b, c) = chain("A", "B", "C")
            settle()

            // Addressed to C, so B is a pure relay — the case that breaks if
            // "cannot verify" is treated as "failed".
            a.router.originate(testPacket(from = a.id, to = c.id))
            settle()

            assertEquals(
                listOf("C"),
                hopsFrom("B"),
                "B must forward for a sender it has never met, or multi-hop dies",
            )
            assertTrue(
                c.delivered.isEmpty(),
                "C cannot verify the sender either, so it must not show the message",
            )
            assertEquals(0, b.router.droppedUnverified, "unverifiable is not the same as forged")
        }
    }

    @Test
    fun `a broadcast from an unknown sender still floods`() = runTest {
        meshTest {
            verifier = ScriptedVerifier(Verification.UNKNOWN_SENDER)
            val (a, b, c) = chain("A", "B", "C")
            settle()

            a.router.originate(testPacket(from = a.id))
            settle()

            assertEquals(listOf("C"), hopsFrom("B"), "an unverifiable broadcast still propagates")
            assertTrue(b.delivered.isEmpty())
            assertTrue(c.delivered.isEmpty())
        }
    }

    @Test
    fun `a forged packet does not consume a dedup slot`() = runTest {
        meshTest {
            val scripted = ScriptedVerifier(Verification.INVALID)
            verifier = scripted
            val (a, b) = chain("A", "B")
            settle()

            val packet = testPacket(from = a.id, to = b.id)
            a.router.originate(packet)
            settle()
            assertTrue(b.delivered.isEmpty())

            // The same messageId, now verifying. If the forged copy had claimed
            // a seen-cache slot, this would be dropped as a duplicate and a real
            // message would be lost — which is the attack the ordering of
            // verify-then-markSeen exists to prevent.
            scripted.say(a.id, Verification.VALID)
            b.transport.deliver(from = "A", bytes = PacketCodec.encode(packet))
            settle()

            assertEquals(1, b.delivered.size, "the genuine packet must still get through")
        }
    }
}
