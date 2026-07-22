package et.alpha.nchat.mesh.protocol

/**
 * What a reassembled message actually contains.
 *
 * A single-packet MESSAGE is raw UTF-8 text and always has been, so it carries no
 * type marker — adding one would change the wire format for every existing
 * device. A **fragmented** message is new, so it can afford one byte of envelope,
 * and it needs it: an image and a long paragraph are both just bytes once they
 * have been split up, and the receiver has to tell them apart before it decides
 * whether to write a file or render a bubble.
 *
 * ```
 * contentType  u8     1=TEXT_UTF8 2=IMAGE_JPEG
 * data         bytes
 * ```
 *
 * The envelope sits *inside* the encrypted payload, not beside it, so the content
 * type is covered by the same AES-GCM tag as the data. Putting it in the clear
 * would leak whether a given transfer is a photo to anyone in radio range.
 */
enum class ContentType(val wire: Int) {
    TEXT_UTF8(1),
    IMAGE_JPEG(2),
    ;

    companion object {
        private val BY_WIRE: Map<Int, ContentType> = entries.associateBy { it.wire }

        /** Returns null for a content type this build does not understand. */
        fun fromWire(value: Int): ContentType? = BY_WIRE[value]
    }
}

/** A typed blob, as carried by a fragmented message. */
class Attachment(val contentType: ContentType, val data: ByteArray) {

    fun encode(): ByteArray = ByteArray(HEADER_SIZE + data.size).also {
        it[0] = contentType.wire.toByte()
        data.copyInto(it, HEADER_SIZE)
    }

    /** Reads the payload as text. Only meaningful for [ContentType.TEXT_UTF8]. */
    fun asText(): String = data.toString(Charsets.UTF_8)

    override fun toString(): String = "Attachment($contentType, ${data.size} bytes)"

    companion object {
        const val HEADER_SIZE: Int = 1

        fun text(body: String): Attachment =
            Attachment(ContentType.TEXT_UTF8, body.toByteArray(Charsets.UTF_8))

        fun jpeg(bytes: ByteArray): Attachment = Attachment(ContentType.IMAGE_JPEG, bytes)

        /**
         * Parses a reassembled payload, or null if it is not a valid envelope.
         *
         * Null rather than an exception, like `Announcement.parse`: these bytes
         * were assembled from fragments a stranger sent, so a malformed envelope
         * is routine hostile input rather than a programming error. An unknown
         * content type is also null — a future version sending something this
         * build cannot render must not crash it.
         */
        fun parse(payload: ByteArray): Attachment? {
            if (payload.size < HEADER_SIZE) return null
            val type = ContentType.fromWire(payload[0].toInt() and 0xFF) ?: return null
            return Attachment(type, payload.copyOfRange(HEADER_SIZE, payload.size))
        }
    }
}
