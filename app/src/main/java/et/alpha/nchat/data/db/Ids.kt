package et.alpha.nchat.data.db

/**
 * Conversion between the protocol layer's raw byte ids and the hex strings the
 * database stores.
 *
 * The database keeps ids as text rather than BLOB so they are greppable in the
 * DB inspector, usable in `IN (...)` clauses, and safe as map keys. The mesh
 * layer converts at this boundary; nothing below it handles hex.
 */

private const val HEX_DIGITS = "0123456789abcdef"

/** Hex-encodes a raw id. An 8-byte peer id becomes 16 characters. */
fun ByteArray.toHexId(): String {
    val out = CharArray(size * 2)
    forEachIndexed { i, byte ->
        val v = byte.toInt() and 0xFF
        out[i * 2] = HEX_DIGITS[v ushr 4]
        out[i * 2 + 1] = HEX_DIGITS[v and 0x0F]
    }
    return String(out)
}

/**
 * Decodes a hex id produced by [toHexId].
 *
 * @throws IllegalArgumentException if the string is not valid lowercase hex of
 *   even length — this is a programming error, not untrusted input.
 */
fun String.hexIdToBytes(): ByteArray {
    require(length % 2 == 0) { "hex id must have even length, was $length" }
    return ByteArray(length / 2) { i ->
        val hi = HEX_DIGITS.indexOf(this[i * 2])
        val lo = HEX_DIGITS.indexOf(this[i * 2 + 1])
        require(hi >= 0 && lo >= 0) { "invalid hex digit at index ${i * 2}" }
        ((hi shl 4) or lo).toByte()
    }
}

/**
 * A fresh 16-byte message id, hex-encoded to 32 characters.
 *
 * Matches the `messageId` width in the wire format so the same value keys both
 * the database row and the packet the router dedups on.
 */
fun newMessageId(): String {
    val uuid = java.util.UUID.randomUUID()
    val bytes = ByteArray(16)
    var hi = uuid.mostSignificantBits
    var lo = uuid.leastSignificantBits
    for (i in 7 downTo 0) {
        bytes[i] = (hi and 0xFF).toByte()
        hi = hi ushr 8
        bytes[i + 8] = (lo and 0xFF).toByte()
        lo = lo ushr 8
    }
    return bytes.toHexId()
}

/** Conversation id for the one-to-one thread with [peerId]. */
fun directConversationId(peerId: String): String = "$DIRECT_PREFIX$peerId"

/**
 * The peer a one-to-one thread is with, or null for any other conversation id.
 *
 * The inverse of [directConversationId], for code that starts from a stored row
 * and has to address a packet — resending a failed message, for one.
 */
fun peerIdOfDirect(conversationId: String): String? =
    conversationId.takeIf { it.startsWith(DIRECT_PREFIX) }
        ?.removePrefix(DIRECT_PREFIX)
        ?.takeIf { it.isNotEmpty() }

private const val DIRECT_PREFIX = "direct:"

/** Id of the single mesh-wide broadcast thread. */
const val BROADCAST_CONVERSATION_ID: String = "broadcast"
