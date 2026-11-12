package et.alpha.nchat.ui.peers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PulseAttributionTest {

    private val advertised = mapOf("real" to "1ac3f00d9e8b7a65", "stranger" to "1ac3f00d9e8b7a65")

    @Test
    fun `a relay over a proved link pulses that contact`() {
        assertEquals(
            "1ac3f00d9e8b7a65",
            pulsePeerId("real", isBound = { it == "real" }, peerIdFor = advertised::get),
        )
    }

    @Test
    fun `a stranger advertising a contact's id does not make the contact pulse`() {
        // Same advertised id as the real link, but this endpoint never proved it.
        assertNull(pulsePeerId("stranger", isBound = { it == "real" }, peerIdFor = advertised::get))
    }

    @Test
    fun `a bound link whose id is not known yet is dropped`() {
        assertNull(pulsePeerId("gone", isBound = { true }, peerIdFor = advertised::get))
    }
}
