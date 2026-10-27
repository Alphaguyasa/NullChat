package et.alpha.nchat.mesh.protocol

/**
 * What a TIMER packet carries: the disappearing-message timer its sender just
 * set on the chat it shares with the recipient.
 *
 * ```
 * format   u8   1
 * seconds  u32  0 = off, else how long a new message stays
 * ```
 *
 * Sealed like a direct message before it goes out, so relays see that two
 * phones exchanged something but not that one of them asked for its words to
 * vanish.
 *
 * ## Why a packet type of its own
 *
 * The same reasoning as [Profile]: every build so far fails `PacketCodec.decode`
 * on a type it does not know and drops the packet quietly, so an old phone
 * simply keeps its own timer. A field slipped into MESSAGE would instead have
 * shown up on old phones as part of the text.
 *
 * ## Why seconds and a range
 *
 * Whole seconds, because nothing finer means anything to a sweep that runs once
 * a minute. The range refuses values no build offers: under a minute the sweep
 * cannot honour it, and over a year it is "off" spelled differently. A value
 * outside it came from a modified client and is ignored rather than clamped,
 * because clamping would set a timer the sender never chose.
 */
class TimerSetting private constructor(
    /** Null when the sender turned the timer off. */
    val disappearAfterMillis: Long?,
) {

    fun encode(): ByteArray {
        val seconds = (disappearAfterMillis ?: 0L) / 1000L
        return byteArrayOf(
            FORMAT.toByte(),
            (seconds ushr 24).toByte(),
            (seconds ushr 16).toByte(),
            (seconds ushr 8).toByte(),
            seconds.toByte(),
        )
    }

    override fun equals(other: Any?): Boolean =
        other is TimerSetting && other.disappearAfterMillis == disappearAfterMillis

    override fun hashCode(): Int = disappearAfterMillis.hashCode()

    override fun toString(): String = "TimerSetting($disappearAfterMillis ms)"

    companion object {
        const val FORMAT: Int = 1
        const val PAYLOAD_SIZE: Int = 5

        const val MIN_SECONDS: Long = 60L
        const val MAX_SECONDS: Long = 366L * 24 * 60 * 60

        /** A setting for [millis], or null when it is not one the wire can carry. */
        fun of(millis: Long?): TimerSetting? {
            if (millis == null) return TimerSetting(null)
            if (millis % 1000L != 0L || millis / 1000L !in MIN_SECONDS..MAX_SECONDS) return null
            return TimerSetting(millis)
        }

        /** Parses a TIMER payload, or null if it is not one this build understands. */
        fun parse(payload: ByteArray): TimerSetting? {
            if (payload.size != PAYLOAD_SIZE) return null
            if (payload[0].toInt() and 0xFF != FORMAT) return null
            var seconds = 0L
            for (i in 1 until PAYLOAD_SIZE) seconds = (seconds shl 8) or (payload[i].toLong() and 0xFF)
            if (seconds == 0L) return TimerSetting(null)
            return of(seconds * 1000L)
        }
    }
}

/**
 * Builds the TIMER packet that tells [recipientId] about a timer change.
 *
 * The payload is still plaintext here; the coordinator seals it, exactly as it
 * seals a message, before the router signs it.
 */
fun timerPacket(
    messageId: ByteArray,
    senderId: ByteArray,
    recipientId: ByteArray,
    setting: TimerSetting,
    timestamp: Long,
    ttl: Int = Packet.DEFAULT_TTL,
): Packet = Packet(
    type = PacketType.TIMER,
    messageId = messageId,
    senderId = senderId,
    recipientId = recipientId,
    ttl = ttl,
    timestamp = timestamp,
    payload = setting.encode(),
    signature = ByteArray(Packet.SIGNATURE_SIZE),
)
