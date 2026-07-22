package et.alpha.nchat.mesh.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The fingerprint word list is part of the protocol.
 *
 * Two devices that disagree about it produce different words for the same key,
 * and users comparing them verbally would conclude they are being attacked when
 * they are not. These tests exist to make an accidental edit fail loudly.
 */
class FingerprintTest {

    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun `the word list has exactly one word per byte value`() {
        assertEquals(256, FINGERPRINT_WORDS.size, "a byte has 256 values and each needs a word")
    }

    @Test
    fun `every word is distinct`() {
        val duplicates = FINGERPRINT_WORDS.groupBy { it }.filterValues { it.size > 1 }.keys

        // A repeated word makes two different keys read aloud identically for
        // that position, quietly weakening the check.
        assertEquals(emptySet<String>(), duplicates, "duplicate fingerprint words")
    }

    @Test
    fun `every word is short enough to read aloud`() {
        val awkward = FINGERPRINT_WORDS.filter { it.length > 8 || it.length < 2 }

        assertEquals(emptyList<String>(), awkward)
    }

    @Test
    fun `a fingerprint is six words`() {
        assertEquals(FINGERPRINT_WORD_COUNT, fingerprintWords(key).size)
        assertEquals(6, fingerprintWords(key).size)
    }

    @Test
    fun `the same key always gives the same words`() {
        assertEquals(fingerprintWords(key), fingerprintWords(key.copyOf()))
    }

    @Test
    fun `a different key gives different words`() {
        val other = key.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }

        assertNotEquals(fingerprintWords(key), fingerprintWords(other))
    }

    @Test
    fun `the fingerprint line is the words separated by spaces`() {
        val line = fingerprintLine(key)

        assertEquals(fingerprintWords(key).joinToString(" "), line)
        assertTrue(line.split(" ").size == 6)
    }

    @Test
    fun `every word comes from the list`() {
        assertTrue(fingerprintWords(key).all { it in FINGERPRINT_WORDS })
    }
}
