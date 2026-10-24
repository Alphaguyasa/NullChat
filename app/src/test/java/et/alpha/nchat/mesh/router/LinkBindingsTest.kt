package et.alpha.nchat.mesh.router

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
}
