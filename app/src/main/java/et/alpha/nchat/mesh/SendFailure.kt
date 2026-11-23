package et.alpha.nchat.mesh

/**
 * Why a send or resend did not go out, as a code the UI turns into words.
 *
 * The mesh layer used to return English sentences, which left them out of
 * strings.xml and made the UI's wording depend on the coordinator. A code
 * keeps the decision in the mesh and the words in resources; see
 * `ui/chat/SendFailureText.kt` for the mapping.
 */
enum class SendFailure {
    /** The body was blank after trimming. */
    EMPTY,

    /** The text is over `MessagePacket.MAX_TEXT_BYTES` once encoded. */
    TEXT_TOO_LONG,

    /** The image is over `ImagePolicy.MAX_ENCODED_BYTES` even after scaling. */
    IMAGE_TOO_LARGE,

    /** The image could not be written to app storage. */
    IMAGE_NOT_SAVED,

    /** Sealing or splitting the payload failed. */
    NOT_PREPARED,

    /**
     * A long message or image to a peer whose agreement key we do not hold.
     * Fragments cannot be held for later the way single packets are.
     */
    NO_KEY,

    /** Nobody stayed in range long enough to take every fragment. */
    NOBODY_IN_RANGE,

    /** A resend of the same message is already running. */
    ALREADY_RESENDING,

    /** The message to resend was deleted. */
    MESSAGE_GONE,

    /** Only a FAILED message can be resent. */
    NOT_FAILED,

    /** The image for a message to resend is no longer on disk. */
    IMAGE_GONE,
}
