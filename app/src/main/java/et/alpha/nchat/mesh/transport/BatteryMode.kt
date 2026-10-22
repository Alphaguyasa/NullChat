package et.alpha.nchat.mesh.transport

/**
 * What the user picked for the radio's battery use.
 *
 * Stored by [name], so reordering the constants cannot reinterpret a saved
 * choice.
 */
enum class BatteryMode {
    /** Saver while the phone's own battery saver is on, normal otherwise. */
    AUTOMATIC,

    /** The usual scan schedule, whatever the phone is doing. */
    NORMAL,

    /** The slow schedule all the time. */
    SAVER,
    ;

    /**
     * Whether the radio should be on the slow schedule now.
     *
     * Automatic follows the system switch because that is the one place people
     * already go when the battery is low; a second, separate switch in this app
     * would be found only after the phone had died.
     */
    fun saves(systemSaverOn: Boolean): Boolean = when (this) {
        AUTOMATIC -> systemSaverOn
        NORMAL -> false
        SAVER -> true
    }

    companion object {
        /** The stored [name], or [AUTOMATIC] for nothing stored or a value this build does not know. */
        fun fromStored(value: String?): BatteryMode = entries.firstOrNull { it.name == value } ?: AUTOMATIC
    }
}
