package et.alpha.nchat.mesh.transport

import et.alpha.nchat.data.db.toHexId
import et.alpha.nchat.mesh.protocol.Packet
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Tests for the one piece of [NearbyTransport] that is not Nearby glue.
 *
 * The transport itself needs Google Play services and real radios, so it is
 * verified on physical devices per CLAUDE.md. [parseAdvertisedPeerId] is
 * deliberately a top-level function free of Android types precisely so the
 * hostile-input path — endpoint names arrive over the air before any connection
 * exists — can be pinned down here on the JVM.
 */
class NearbyTransportTest {

    @Test
    fun `parses a well-formed advertised peer id`() {
        val peerId = ByteArray(Packet.PEER_ID_SIZE) { (0xA0 + it).toByte() }

        val parsed = parseAdvertisedPeerId(peerId.toHexId())

        assertArrayEquals(peerId, parsed)
    }

    @Test
    fun `round-trips every byte value`() {
        // Catches sign-extension bugs in the hex codec: 0x80..0xFF are negative
        // as Kotlin Bytes.
        val peerId = byteArrayOf(0, 1, 0x7F, -0x80, -1, 0x10, 0x0F, -0x71)

        assertArrayEquals(peerId, parseAdvertisedPeerId(peerId.toHexId()))
    }

    @Test
    fun `rejects a null name`() {
        assertNull(parseAdvertisedPeerId(null))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "a1b2c3d4e5f60718a1", // 18 chars, too long
            "a1b2c3d4e5f607", // 14 chars, too short
            "a1b2c3d4e5f6071", // 15 chars, odd length
            "a1b2c3d4e5f6071g", // right length, non-hex digit
            "A1B2C3D4E5F60718", // uppercase: we advertise lowercase, so not ours
            "................", // right length, no hex at all
        ],
    )
    fun `rejects a malformed name instead of throwing`(name: String) {
        // A throw here would escape a Nearby callback on the main thread and take
        // the app down, which is why this returns null rather than propagating.
        assertNull(parseAdvertisedPeerId(name))
    }
}
