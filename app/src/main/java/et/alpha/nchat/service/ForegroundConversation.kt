package et.alpha.nchat.service

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The conversation the user is looking at right now, if any.
 *
 * Set by the chat screen only while it is resumed, so "open" means on screen
 * and in front — not merely the last thread visited before the phone was put
 * away. A notification for a thread the user is reading is noise; one for a
 * thread left open in a backgrounded app is the whole point of notifying.
 */
@Singleton
class ForegroundConversation @Inject constructor() {

    private val _current = MutableStateFlow<String?>(null)

    /** Conversation id on screen, or null when no thread is in front. */
    val current: StateFlow<String?> = _current.asStateFlow()

    fun enter(conversationId: String) {
        _current.value = conversationId
    }

    /**
     * Clears [conversationId] if it is still the one in front.
     *
     * Conditional because switching threads can run the new screen's enter
     * before the old one's leave; an unconditional clear would then mark the
     * new thread as closed while the user is reading it.
     */
    fun leave(conversationId: String) {
        _current.compareAndSet(conversationId, null)
    }
}
