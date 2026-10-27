package et.alpha.nchat.mesh.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class TimerSettingTest {

    @ParameterizedTest
    @ValueSource(longs = [0L, HOUR, DAY, 7 * DAY])
    fun `every offered timer round-trips through its payload`(millis: Long) {
        val setting = TimerSetting.of(millis.takeIf { it != 0L })!!

        assertEquals(setting, TimerSetting.parse(setting.encode()))
    }

    @Test
    fun `a timer round-trips through a whole packet`() {
        val sender = ByteArray(Packet.PEER_ID_SIZE) { (it + 1).toByte() }
        val recipient = ByteArray(Packet.PEER_ID_SIZE) { (it + 9).toByte() }
        val packet = timerPacket(
            messageId = ByteArray(Packet.MESSAGE_ID_SIZE) { 7 },
            senderId = sender,
            recipientId = recipient,
            setting = TimerSetting.of(DAY)!!,
            timestamp = 1_700_000_000_000L,
        )

        val decoded = PacketCodec.decode(PacketCodec.encode(packet))

        assertEquals(PacketType.TIMER, decoded.type)
        assertFalse(decoded.isBroadcast)
        assertArrayEquals(recipient, decoded.recipientId)
        assertEquals(DAY, TimerSetting.parse(decoded.payload)!!.disappearAfterMillis)
    }

    @Test
    fun `the wire form is the format byte and big-endian seconds`() {
        // One hour is 3600 s = 0x00000E10.
        assertArrayEquals(byteArrayOf(1, 0, 0, 0x0E, 0x10), TimerSetting.of(HOUR)!!.encode())
        assertArrayEquals(byteArrayOf(1, 0, 0, 0, 0), TimerSetting.of(null)!!.encode())
    }

    @Test
    fun `off is carried as zero seconds`() {
        assertNull(TimerSetting.parse(byteArrayOf(1, 0, 0, 0, 0))!!.disappearAfterMillis)
    }

    @Test
    fun `values no build offers are refused rather than clamped`() {
        assertNull(TimerSetting.of(59_000L))
        assertNull(TimerSetting.of(HOUR + 1))
        assertNull(TimerSetting.of((TimerSetting.MAX_SECONDS + 1) * 1000L))
        // 59 s and 0xFFFFFFFF s on the wire.
        assertNull(TimerSetting.parse(byteArrayOf(1, 0, 0, 0, 59)))
        assertNull(TimerSetting.parse(byteArrayOf(1, -1, -1, -1, -1)))
    }

    @Test
    fun `an unknown format or a wrong length is not read`() {
        assertNull(TimerSetting.parse(byteArrayOf(2, 0, 0, 0x0E, 0x10)))
        assertNull(TimerSetting.parse(byteArrayOf(1, 0, 0, 0x0E)))
        assertNull(TimerSetting.parse(byteArrayOf(1, 0, 0, 0x0E, 0x10, 0)))
        assertNull(TimerSetting.parse(ByteArray(0)))
    }

    private companion object {
        const val HOUR: Long = 60 * 60 * 1000L
        const val DAY: Long = 24 * HOUR
    }
}
