package et.alpha.nchat.mesh

/**
 * The router's drop counters, as Settings shows them.
 *
 * Counted since the app started, not stored: they are a reading of the mesh
 * around the phone right now, and a count carried over from last week's crowd
 * would say nothing about this one.
 */
data class MeshHealth(
    /** Signed by the wrong key: someone is forging sender ids. */
    val forged: Long = 0,
    /** Did not parse: a broken or hostile build nearby. */
    val malformed: Long = 0,
    /** Over a link's rate budget: a neighbour sending far more than any normal phone. */
    val rateLimited: Long = 0,
    /** Relays that found the neighbour gone. Normal as people walk away; nothing was lost. */
    val failedForwards: Long = 0,
) {
    /**
     * Whether a phone nearby is misbehaving. Failed forwards do not count:
     * they happen whenever a neighbour walks out of range mid-relay, and the
     * forward queue recovers them.
     */
    val suspicious: Boolean get() = forged > 0 || malformed > 0 || rateLimited > 0
}
