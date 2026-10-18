package et.alpha.nchat.mesh.protocol

/**
 * What a PROFILE packet carries: the name its sender chose to be shown by.
 *
 * ```
 * format       u8   1
 * displayName  ≤48B UTF-8, normalised (see [normalize])
 * ```
 *
 * ## Why a packet type of its own, not a field on ANNOUNCE
 *
 * `Announcement.parse` in every build so far accepts exactly 64 bytes and
 * nothing else. A name appended to the keys would make old builds reject the
 * whole announcement and lose the keys with it — the peer would become
 * unverifiable to them over a cosmetic field. An unknown packet type, by
 * contrast, fails `PacketCodec.decode` on an old build and is dropped quietly,
 * so the name simply does not show there. That is the "new packet type" route
 * CLAUDE.md allows instead of a protocol version bump.
 *
 * ## Why the name is not trusted for anything
 *
 * The router verifies a PROFILE's signature against the sender's known key, so
 * the name genuinely comes from that peer id. It is still a label the peer
 * picked for itself: anyone can call themselves "Maya". Identity stays the key
 * and the fingerprint words; the name is only ever shown next to them.
 *
 * The format byte is there so a later build can add fields — a status line,
 * an avatar colour — and an older one can tell it is looking at a format it
 * does not know instead of reading those bytes as part of the name.
 */
class Profile private constructor(val displayName: String) {

    fun encode(): ByteArray = byteArrayOf(FORMAT.toByte()) + displayName.toByteArray(Charsets.UTF_8)

    override fun equals(other: Any?): Boolean = other is Profile && other.displayName == displayName

    override fun hashCode(): Int = displayName.hashCode()

    override fun toString(): String = "Profile(${displayName.length} chars)"

    companion object {
        const val FORMAT: Int = 1

        /**
         * Longest name on the wire, in UTF-8 bytes.
         *
         * Bytes rather than characters because the packet budget is bytes. 48
         * leaves 16 characters of Ge'ez script (three bytes each), which a
         * Latin-only limit like 16 bytes would have cut to five.
         */
        const val MAX_NAME_BYTES: Int = 48

        /** Format byte plus the longest name: well inside the payload budget. */
        const val MAX_PAYLOAD_SIZE: Int = 1 + MAX_NAME_BYTES

        /**
         * A profile for what the user typed, or null when nothing showable is left.
         *
         * Normalised the same way [parse] demands, so what this device sends is
         * exactly what every receiver will accept.
         */
        fun fromInput(input: String): Profile? = normalize(input).takeIf { it.isNotEmpty() }?.let(::Profile)

        /**
         * Parses a PROFILE payload, or null if it is not one this build can show.
         *
         * Strict on purpose: a name that is not already in normal form was not
         * produced by [fromInput], so it is either a different format or someone
         * hand-crafting bytes — for instance a right-to-left override that makes
         * a name render as somebody else's. Refusing it costs one peer's label;
         * accepting it lets a stranger draw text the user did not expect.
         */
        fun parse(payload: ByteArray): Profile? {
            if (payload.size < 2 || payload.size > MAX_PAYLOAD_SIZE) return null
            if (payload[0].toInt() and 0xFF != FORMAT) return null
            val name = MessagePacket.strictUtf8(payload.copyOfRange(1, payload.size)) ?: return null
            if (name.isEmpty() || normalize(name) != name) return null
            return Profile(name)
        }

        /**
         * The showable form of [input]: invisible and control characters removed,
         * whitespace collapsed to single spaces and trimmed, then cut to
         * [MAX_NAME_BYTES] without splitting a character.
         *
         * Format characters (Unicode category Cf) go too. That covers the
         * bidirectional overrides and zero-width joiners that make two names look
         * identical or make one render backwards; a display name has no
         * legitimate use for them.
         */
        fun normalize(input: String): String {
            val visible = buildString {
                var pendingSpace = false
                var i = 0
                while (i < input.length) {
                    val codePoint = input.codePointAt(i)
                    i += Character.charCount(codePoint)
                    when {
                        Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) ->
                            pendingSpace = isNotEmpty()

                        isHidden(codePoint) -> Unit

                        else -> {
                            if (pendingSpace) append(' ')
                            pendingSpace = false
                            appendCodePoint(codePoint)
                        }
                    }
                }
            }
            return truncateToBytes(visible, MAX_NAME_BYTES)
        }

        private fun isHidden(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
            Character.CONTROL.toInt(),
            Character.FORMAT.toInt(),
            Character.PRIVATE_USE.toInt(),
            Character.SURROGATE.toInt(),
            Character.UNASSIGNED.toInt(),
            -> true

            else -> false
        }

        /** Longest prefix of [text] that fits in [maxBytes] of UTF-8, on a character boundary. */
        private fun truncateToBytes(text: String, maxBytes: Int): String {
            var bytes = 0
            var end = 0
            while (end < text.length) {
                val codePoint = text.codePointAt(end)
                val width = utf8Width(codePoint)
                if (bytes + width > maxBytes) break
                bytes += width
                end += Character.charCount(codePoint)
            }
            // A cut can land right after a space; a trailing one would fail the
            // receiver's normal-form check.
            return text.substring(0, end).trimEnd()
        }

        private fun utf8Width(codePoint: Int): Int = when {
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < 0x10000 -> 3
            else -> 4
        }
    }
}

/**
 * Builds the PROFILE packet this device broadcasts after its ANNOUNCE.
 *
 * Broadcast, like the announcement it follows: the name is for whoever can see
 * this device on their radar, not for one contact.
 */
fun profilePacket(
    messageId: ByteArray,
    senderId: ByteArray,
    profile: Profile,
    timestamp: Long,
    ttl: Int = Packet.DEFAULT_TTL,
): Packet = Packet(
    type = PacketType.PROFILE,
    messageId = messageId,
    senderId = senderId,
    recipientId = Packet.BROADCAST_ID,
    ttl = ttl,
    timestamp = timestamp,
    payload = profile.encode(),
    signature = ByteArray(Packet.SIGNATURE_SIZE),
)
