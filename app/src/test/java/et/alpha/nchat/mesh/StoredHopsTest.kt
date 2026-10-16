package et.alpha.nchat.mesh

import et.alpha.nchat.mesh.protocol.Acknowledgement
import et.alpha.nchat.mesh.protocol.Packet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StoredHopsTest {

    private fun ack(hops: Int) = Acknowledgement(ByteArray(Packet.MESSAGE_ID_SIZE), hops)

    @Test
    fun `a direct neighbour's receipt is one hop`() {
        assertEquals(1, storedHops(ack(1)))
    }

    @Test
    fun `the longest real trip is the full ttl budget`() {
        assertEquals(Packet.DEFAULT_TTL, storedHops(ack(Packet.DEFAULT_TTL)))
    }

    @Test
    fun `zero means the recipient did not know`() {
        assertNull(storedHops(ack(0)))
    }

    @Test
    fun `a count beyond the ttl budget is not stored`() {
        assertNull(storedHops(ack(Packet.DEFAULT_TTL + 1)))
        assertNull(storedHops(ack(Acknowledgement.MAX_HOPS)))
    }

    @Test
    fun `the count survives the wire`() {
        val parsed = Acknowledgement.parse(ack(3).encode())!!
        assertEquals(3, storedHops(parsed))
    }
}
