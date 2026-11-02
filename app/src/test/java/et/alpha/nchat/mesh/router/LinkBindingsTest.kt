package et.alpha.nchat.mesh.router

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A link belongs to a peer only once that peer's signed announcement arrives on
 * it. Before roadmap #30 the advertised name alone was enough, so a stranger
 * naming itself after a contact was handed the contact's queued messages.
 */
class LinkBindingsTest {

    private val alice = peerId("alice")
    private val mallory = peerId("mallory")

    @Test
    fun `the bound set is published by every change, with no caller copying it`() {
        val links = LinkBindings()
        val hex = alice.hex()
        links.claim("e1", alice)
        assertEquals(emptySet<String>(), links.boundPeerIds.value)

        links.prove(LinkProof("e1", alice))
        assertEquals(setOf(hex), links.boundPeerIds.value)

        links.retain(emptySet())
        assertEquals(emptySet<String>(), links.boundPeerIds.value)
    }

    @Test
    fun `binds from two threads at once all end up published`() {
        val links = LinkBindings()
        val peers = (0 until 200).map { peerId("peer$it") }
        val threads = listOf(0, 1).map { half ->
            Thread {
                peers.forEachIndexed { i, id ->
                    if (i % 2 != half) return@forEachIndexed
                    // One thread claims first, the other proves first, as the
                    // coordinator's two collectors do.
                    if (half == 0) {
                        links.claim("e$i", id)
                        links.prove(LinkProof("e$i", id))
                    } else {
                        links.prove(LinkProof("e$i", id))
                        links.claim("e$i", id)
                    }
                }
            }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        assertEquals(peers.size, links.boundPeerIds.value.size)
        assertEquals(links.boundPeers(), links.boundPeerIds.value)
    }

    @Test
    fun `a claimed name alone does not bind the link`() {
        val links = LinkBindings()

        assertFalse(links.claim("e1", alice), "an advertised name is not proof")
        assertFalse(links.isBound("e1"))
    }

    @Test
    fun `a proof for the claimed id on that link binds it`() {
        val links = LinkBindings()
        links.claim("e1", alice)

        assertTrue(links.prove(LinkProof("e1", alice)))
        assertTrue(links.isBound("e1"))
    }

    @Test
    fun `a proof that arrives before the claim binds when the claim comes`() {
        val links = LinkBindings()

        assertFalse(links.prove(LinkProof("e1", alice)))
        assertTrue(links.claim("e1", alice))
    }

    @Test
    fun `an impostor relaying someone else's announcement stays unbound`() {
        val links = LinkBindings()
        // Mallory advertises Alice's id but can only sign as herself.
        links.claim("e1", alice)

        assertFalse(links.prove(LinkProof("e1", mallory)))
        assertFalse(links.isBound("e1"))
    }

    @Test
    fun `a proof on a different link does not bind this one`() {
        val links = LinkBindings()
        links.claim("impostor", alice)

        // The real Alice announcing over her own link says nothing about this one.
        assertFalse(links.prove(LinkProof("real", alice)))
        assertFalse(links.isBound("impostor"))
    }

    @Test
    fun `an early proof for another id does not bind a later claim`() {
        val links = LinkBindings()
        links.prove(LinkProof("e1", mallory))

        assertFalse(links.claim("e1", alice))
    }

    @Test
    fun `binding fires once per connection`() {
        val links = LinkBindings()
        links.claim("e1", alice)
        links.prove(LinkProof("e1", alice))

        assertFalse(links.prove(LinkProof("e1", alice)), "a second announcement must not re-offer the queue")
        assertFalse(links.claim("e1", alice))
    }

    @Test
    fun `a peer that reconnects must prove itself again`() {
        val links = LinkBindings()
        links.claim("e1", alice)
        links.prove(LinkProof("e1", alice))

        links.retain(emptySet())
        assertFalse(links.isBound("e1"))
        assertFalse(links.claim("e1", alice))
        assertTrue(links.prove(LinkProof("e1", alice)))
    }

    @Test
    fun `only proven links count as peers next to us`() {
        val links = LinkBindings()
        // A stranger advertising a contact's id, and the contact itself.
        links.claim("stranger", alice)
        links.claim("e1", mallory)
        links.prove(LinkProof("e1", mallory))

        assertEquals(setOf(mallory.hex()), links.boundPeers())

        links.retain(emptySet())
        assertEquals(emptySet<String>(), links.boundPeers(), "a link that left is nobody's")
    }

    // Roadmap #42: a stranger M sits between Alice and us. Alice's link to M has
    // token T1, M's link to us has T2, and Alice can only sign T1's digest.
    private val t1 = ByteArray(32) { 1 }
    private val t2 = ByteArray(32) { 2 }

    @Test
    fun `a token proof for this link binds it`() {
        val proved = mutableListOf<String>()
        val links = LinkBindings(onTokenProof = { proved.add(it) })
        links.claim("e1", alice, tokenDigest = t2)

        assertTrue(links.prove(LinkProof("e1", alice, tokenDigest = t2)))
        assertEquals(listOf(alice.hex()), proved)
    }

    @Test
    fun `a relayed token proof made for another link does not bind`() {
        val proved = mutableListOf<String>()
        val links = LinkBindings(onTokenProof = { proved.add(it) })
        links.claim("relay", alice, tokenDigest = t2)

        assertFalse(links.prove(LinkProof("relay", alice, tokenDigest = t1)))
        assertFalse(links.isBound("relay"))
        assertTrue(proved.isEmpty(), "a mismatched proof must not mark the peer as a prover")
    }

    @Test
    fun `a relayed announcement no longer binds a peer known to send token proofs`() {
        val links = LinkBindings(requiresTokenProof = { it == alice.hex() })
        links.claim("relay", alice, tokenDigest = t2)

        assertFalse(links.prove(LinkProof("relay", alice)), "a live relay of the announcement is not proof")
        assertFalse(links.isBound("relay"))
        assertTrue(links.prove(LinkProof("relay", alice, tokenDigest = t2)))
    }

    @Test
    fun `an announcement still binds a peer that never sent a token proof`() {
        // Older builds send no LINK_PROOF; they must keep working.
        val links = LinkBindings(requiresTokenProof = { false })
        links.claim("e1", alice, tokenDigest = t2)

        assertTrue(links.prove(LinkProof("e1", alice)))
    }

    @Test
    fun `a token proof that beats the claim binds when the claim comes`() {
        val proved = mutableListOf<String>()
        val links = LinkBindings(requiresTokenProof = { true }, onTokenProof = { proved.add(it) })

        assertFalse(links.prove(LinkProof("e1", alice)))
        assertFalse(links.prove(LinkProof("e1", alice, tokenDigest = t1)))
        assertFalse(links.claim("e2", alice, tokenDigest = t2))
        assertFalse(links.prove(LinkProof("e3", alice, tokenDigest = t2)))
        assertTrue(links.claim("e3", alice, tokenDigest = t2))
        assertEquals(listOf(alice.hex()), proved)
    }

    @Test
    fun `an early announcement does not bind a peer known to send token proofs`() {
        val links = LinkBindings(requiresTokenProof = { true })
        links.prove(LinkProof("e1", alice))

        assertFalse(links.claim("e1", alice, tokenDigest = t2))
    }

    @Test
    fun `a token proof after an announcement binding still marks the peer`() {
        // First meeting with an updated contact: the announcement binds, and the
        // proof right behind it is what makes the next meeting strict.
        val proved = mutableListOf<String>()
        val links = LinkBindings(onTokenProof = { proved.add(it) })
        links.claim("e1", alice, tokenDigest = t2)
        assertTrue(links.prove(LinkProof("e1", alice)))

        assertFalse(links.prove(LinkProof("e1", alice, tokenDigest = t2)), "already bound")
        assertEquals(listOf(alice.hex()), proved)
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
