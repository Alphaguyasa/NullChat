package et.alpha.nchat.mesh.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * Wire-format tests for [PacketCodec].
 *
 * The golden test below is written directly from the packet format in CLAUDE.md,
 * field by field, and deliberately NOT generated from the encoder — otherwise it
 * would only prove the encoder agrees with itself.
 */
class PacketCodecTest {

    // region helpers

    private fun hex(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd-length hex" }
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /** A fully-populated packet of the given type, with distinct byte patterns per field. */
    private fun samplePacket(
        type: PacketType,
        payload: ByteArray = "hello mesh".toByteArray(),
    ) = Packet(
        type = type,
        messageId = ByteArray(Packet.MESSAGE_ID_SIZE) { (0x40 + it).toByte() },
        senderId = ByteArray(Packet.PEER_ID_SIZE) { (0x11 + it).toByte() },
        recipientId = ByteArray(Packet.PEER_ID_SIZE) { (0x21 + it).toByte() },
        ttl = 6,
        timestamp = 1_700_000_000_000L,
        payload = payload,
        signature = ByteArray(Packet.SIGNATURE_SIZE) { (it + 1).toByte() },
    )

    // endregion

    // region round-trip

    @ParameterizedTest
    @EnumSource(PacketType::class)
    fun `round-trips every packet type`(type: PacketType) {
        val original = samplePacket(type)

        val decoded = PacketCodec.decode(PacketCodec.encode(original))

        assertEquals(original, decoded)
        assertEquals(type, decoded.type)
    }

    @Test
    fun `round-trips an empty payload`() {
        val original = samplePacket(PacketType.ACK, payload = ByteArray(0))

        assertEquals(original, PacketCodec.decode(PacketCodec.encode(original)))
    }

    @Test
    fun `round-trips a maximum-size payload`() {
        val original = samplePacket(
            PacketType.MESSAGE,
            payload = ByteArray(Packet.MAX_PAYLOAD_SIZE) { it.toByte() },
        )

        val encoded = PacketCodec.encode(original)

        assertEquals(Packet.MAX_PACKET_SIZE, encoded.size)
        assertEquals(original, PacketCodec.decode(encoded))
    }

    @Test
    fun `round-trips a broadcast recipient of all zeroes`() {
        val original = samplePacket(PacketType.ANNOUNCE)
            .copy(recipientId = ByteArray(Packet.PEER_ID_SIZE))

        val decoded = PacketCodec.decode(PacketCodec.encode(original))

        assertTrue(decoded.isBroadcast)
        assertEquals(original, decoded)
    }

    @Test
    fun `preserves a timestamp with the high bit set`() {
        // u64 on the wire; Kotlin's Long is signed, so a large value must survive
        // the trip without being mangled into a negative or truncated value.
        val original = samplePacket(PacketType.MESSAGE).copy(timestamp = -1L)

        assertEquals(-1L, PacketCodec.decode(PacketCodec.encode(original)).timestamp)
    }

    @Test
    fun `preserves a full-range ttl`() {
        val original = samplePacket(PacketType.MESSAGE).copy(ttl = 255)

        assertEquals(255, PacketCodec.decode(PacketCodec.encode(original)).ttl)
    }

    // endregion

    // region golden vector

    @Test
    fun `encodes to the exact bytes required by the wire format`() {
        val packet = Packet(
            version = 1,
            type = PacketType.MESSAGE,
            messageId = hex("000102030405060708090a0b0c0d0e0f"),
            senderId = hex("1112131415161718"),
            recipientId = hex("2122232425262728"),
            ttl = 6,
            timestamp = 1_700_000_000_000L,
            payload = "hi".toByteArray(),
            signature = ByteArray(Packet.SIGNATURE_SIZE) { (it + 1).toByte() },
        )

        val expected =
            "01" +                               // version   u8
                "02" +                           // type      u8  = MESSAGE
                "000102030405060708090a0b0c0d0e0f" + // messageId   16B
                "1112131415161718" +             // senderId     8B
                "2122232425262728" +             // recipientId  8B
                "06" +                           // ttl       u8
                "0000018bcfe56800" +             // timestamp u64 = 1700000000000
                "0002" +                         // payloadLen u16
                "6869" +                         // payload   "hi"
                "0102030405060708090a0b0c0d0e0f10" + // signature 64B
                "1112131415161718191a1b1c1d1e1f20" +
                "2122232425262728292a2b2c2d2e2f30" +
                "3132333435363738393a3b3c3d3e3f40"

        assertEquals(expected, PacketCodec.encode(packet).toHex())
    }

    @Test
    fun `decodes the golden bytes back into the original packet`() {
        val packet = Packet(
            version = 1,
            type = PacketType.MESSAGE,
            messageId = hex("000102030405060708090a0b0c0d0e0f"),
            senderId = hex("1112131415161718"),
            recipientId = hex("2122232425262728"),
            ttl = 6,
            timestamp = 1_700_000_000_000L,
            payload = "hi".toByteArray(),
            signature = ByteArray(Packet.SIGNATURE_SIZE) { (it + 1).toByte() },
        )

        assertEquals(packet, PacketCodec.decode(PacketCodec.encode(packet)))
    }

    @Test
    fun `header size matches the field widths in the spec`() {
        // 1 + 1 + 16 + 8 + 8 + 1 + 8 + 2
        assertEquals(45, Packet.HEADER_SIZE)
        assertEquals(64, Packet.SIGNATURE_SIZE)
        assertEquals(180, Packet.MAX_PACKET_SIZE)
        assertEquals(180 - 45 - 64, Packet.MAX_PAYLOAD_SIZE)
    }

    // endregion

    // region truncated input

    @Test
    fun `rejects an empty buffer`() {
        assertThrows<PacketDecodeException.Truncated> { PacketCodec.decode(ByteArray(0)) }
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 20, 44, 45, 46, 60, 100, 108])
    fun `rejects a buffer truncated mid-packet`(length: Int) {
        val full = PacketCodec.encode(samplePacket(PacketType.MESSAGE))

        assertThrows<PacketDecodeException.Truncated> {
            PacketCodec.decode(full.copyOf(length))
        }
    }

    @Test
    fun `rejects a buffer whose payload is shorter than payloadLen claims`() {
        val full = PacketCodec.encode(samplePacket(PacketType.MESSAGE))

        // Drop a single trailing byte: header and signature sizes still look sane,
        // but the declared payload no longer fits.
        assertThrows<PacketDecodeException.Truncated> {
            PacketCodec.decode(full.copyOf(full.size - 1))
        }
    }

    @Test
    fun `rejects a buffer with trailing bytes after the signature`() {
        val full = PacketCodec.encode(samplePacket(PacketType.MESSAGE))
        val padded = full.copyOf(full.size + 3)

        assertThrows<PacketDecodeException.TrailingBytes> { PacketCodec.decode(padded) }
    }

    // endregion

    // region malformed fields

    @Test
    fun `rejects an unknown version byte`() {
        val bytes = PacketCodec.encode(samplePacket(PacketType.MESSAGE))
        bytes[0] = 0x02

        val e = assertThrows<PacketDecodeException.UnsupportedVersion> { PacketCodec.decode(bytes) }
        assertEquals(2, e.version)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 7, 99, 255])
    fun `rejects an unknown packet type`(type: Int) {
        val bytes = PacketCodec.encode(samplePacket(PacketType.MESSAGE))
        bytes[1] = type.toByte()

        val e = assertThrows<PacketDecodeException.UnknownPacketType> { PacketCodec.decode(bytes) }
        assertEquals(type, e.value)
    }

    @Test
    fun `rejects a declared payload length beyond the packet budget`() {
        val bytes = PacketCodec.encode(samplePacket(PacketType.MESSAGE))
        // payloadLen sits at offset 43, immediately after the u64 timestamp.
        bytes[43] = 0xFF.toByte()
        bytes[44] = 0xFF.toByte()

        val e = assertThrows<PacketDecodeException.OversizedPayload> { PacketCodec.decode(bytes) }
        assertEquals(0xFFFF, e.declared)
    }

    @Test
    fun `refuses to build a packet with an oversized payload`() {
        assertThrows<IllegalArgumentException> {
            samplePacket(PacketType.MESSAGE, payload = ByteArray(Packet.MAX_PAYLOAD_SIZE + 1))
        }
    }

    @Test
    fun `refuses to build a packet with a wrong-width id or signature`() {
        assertThrows<IllegalArgumentException> {
            samplePacket(PacketType.MESSAGE).copy(messageId = ByteArray(15))
        }
        assertThrows<IllegalArgumentException> {
            samplePacket(PacketType.MESSAGE).copy(senderId = ByteArray(7))
        }
        assertThrows<IllegalArgumentException> {
            samplePacket(PacketType.MESSAGE).copy(recipientId = ByteArray(9))
        }
        assertThrows<IllegalArgumentException> {
            samplePacket(PacketType.MESSAGE).copy(signature = ByteArray(63))
        }
    }

    @Test
    fun `refuses to build a packet with an out-of-range ttl`() {
        assertThrows<IllegalArgumentException> { samplePacket(PacketType.MESSAGE).copy(ttl = -1) }
        assertThrows<IllegalArgumentException> { samplePacket(PacketType.MESSAGE).copy(ttl = 256) }
    }

    // endregion

    // region signing surface

    @Test
    fun `signedBytes covers every byte preceding the signature except ttl`() {
        val packet = samplePacket(PacketType.MESSAGE)
        val encoded = PacketCodec.encode(packet)

        val signed = PacketCodec.signedBytes(packet)

        assertEquals(encoded.size - Packet.SIGNATURE_SIZE - Packet.TTL_SIZE, signed.size)

        // Everything up to the ttl byte, then everything after it, with the one
        // mutable field spliced out.
        val ttlOffset = 1 + 1 + Packet.MESSAGE_ID_SIZE + Packet.PEER_ID_SIZE + Packet.PEER_ID_SIZE
        val expected = encoded.copyOf(ttlOffset) +
            encoded.copyOfRange(ttlOffset + Packet.TTL_SIZE, encoded.size - Packet.SIGNATURE_SIZE)
        assertArrayEquals(expected, signed)
    }

    @Test
    fun `signedBytes ignores the signature field`() {
        val packet = samplePacket(PacketType.MESSAGE)
        val tampered = packet.copy(signature = ByteArray(Packet.SIGNATURE_SIZE) { 0x7F })

        assertArrayEquals(PacketCodec.signedBytes(packet), PacketCodec.signedBytes(tampered))
    }

    @Test
    fun `signedBytes survives a hop`() {
        // The reason ttl is excluded at all: a relay decrements it on every
        // forward, so if this were not stable, nothing past a direct neighbour
        // could ever verify.
        val packet = samplePacket(PacketType.MESSAGE).copy(ttl = 6)
        val relayed = packet.decrementTtl()!!

        assertEquals(5, relayed.ttl)
        assertArrayEquals(PacketCodec.signedBytes(packet), PacketCodec.signedBytes(relayed))
    }

    @Test
    fun `changing any signed field changes the signed bytes`() {
        val base = PacketCodec.signedBytes(samplePacket(PacketType.MESSAGE))
        val variants = listOf(
            samplePacket(PacketType.MESSAGE).copy(timestamp = 1L),
            samplePacket(PacketType.MESSAGE).copy(type = PacketType.ACK),
            samplePacket(PacketType.MESSAGE).copy(payload = "other".toByteArray()),
            samplePacket(PacketType.MESSAGE).copy(senderId = ByteArray(Packet.PEER_ID_SIZE) { 9 }),
            samplePacket(PacketType.MESSAGE)
                .copy(recipientId = ByteArray(Packet.PEER_ID_SIZE) { 9 }),
            samplePacket(PacketType.MESSAGE)
                .copy(messageId = ByteArray(Packet.MESSAGE_ID_SIZE) { 9 }),
        )

        variants.forEach { assertFalse(base.contentEquals(PacketCodec.signedBytes(it))) }
    }

    // endregion

    // region value semantics

    @Test
    fun `packets with equal field contents are equal`() {
        // Packet is used as a map and set key in the router, so structural
        // equality must compare array contents, not identity.
        assertEquals(samplePacket(PacketType.MESSAGE), samplePacket(PacketType.MESSAGE))
        assertEquals(
            samplePacket(PacketType.MESSAGE).hashCode(),
            samplePacket(PacketType.MESSAGE).hashCode(),
        )
    }

    @Test
    fun `packets differing in one field are not equal`() {
        val a = samplePacket(PacketType.MESSAGE)

        assertTrue(a != a.copy(ttl = 5))
        assertTrue(a != a.copy(payload = "different".toByteArray()))
        assertTrue(a != a.copy(senderId = ByteArray(Packet.PEER_ID_SIZE) { 0x99.toByte() }))
    }

    // endregion
}
