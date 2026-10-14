package et.alpha.nchat.mesh.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AcknowledgementTest {

    private val acked = ByteArray(Packet.MESSAGE_ID_SIZE) { (0x30 + it).toByte() }

    @Test
    fun `an acknowledgement round-trips through its payload`() {
        val parsed = Acknowledgement.parse(Acknowledgement(acked, hops = 3).encode())!!

        assertArrayEquals(acked, parsed.ackedMessageId)
        assertEquals(3, parsed.hops)
    }

    @Test
    fun `hops above 127 survive the unsigned byte`() {
        // A signed read of the byte would turn 200 into -56.
        assertEquals(200, Acknowledgement.parse(Acknowledgement(acked, hops = 200).encode())!!.hops)
    }

    @Test
    fun `an acknowledgement fits the payload budget`() {
        val encoded = Acknowledgement(acked, hops = 1).encode()

        assertEquals(Acknowledgement.PAYLOAD_SIZE, encoded.size)
        assertTrue(encoded.size <= Packet.MAX_PAYLOAD_SIZE)
    }

    @Test
    fun `a payload of the wrong length is not an acknowledgement`() {
        assertNull(Acknowledgement.parse(ByteArray(16)))
        assertNull(Acknowledgement.parse(ByteArray(18)))
        assertNull(Acknowledgement.parse(ByteArray(0)))
    }

    @Test
    fun `bad fields are refused at construction`() {
        assertThrows<IllegalArgumentException> { Acknowledgement(ByteArray(15), hops = 1) }
        assertThrows<IllegalArgumentException> { Acknowledgement(acked, hops = -1) }
        assertThrows<IllegalArgumentException> { Acknowledgement(acked, hops = 256) }
    }

    @Test
    fun `an acknowledgement packet round-trips through the codec`() {
        val packet = acknowledgementPacket(
            messageId = ByteArray(Packet.MESSAGE_ID_SIZE) { 7 },
            senderId = ByteArray(Packet.PEER_ID_SIZE) { 1 },
            recipientId = ByteArray(Packet.PEER_ID_SIZE) { 2 },
            acknowledgement = Acknowledgement(acked, hops = 2),
            timestamp = 1_700_000_000_000L,
        )

        val decoded = PacketCodec.decode(PacketCodec.encode(packet))

        assertEquals(PacketType.ACK, decoded.type)
        assertEquals(packet, decoded)
        val ack = Acknowledgement.parse(decoded.payload)!!
        assertArrayEquals(acked, ack.ackedMessageId)
        assertEquals(2, ack.hops)
    }
}
