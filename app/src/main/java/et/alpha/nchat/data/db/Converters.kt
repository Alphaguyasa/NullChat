package et.alpha.nchat.data.db

import androidx.room.TypeConverter

/** Room type converters for the mesh schema. */
class Converters {

    /**
     * Persists [DeliveryState] by name.
     *
     * Deliberately not by ordinal: ordinals renumber when a constant is inserted
     * or reordered, which would silently reinterpret every stored row.
     */
    @TypeConverter
    fun fromDeliveryState(state: DeliveryState): String = state.name

    /**
     * Unknown names decode to [DeliveryState.FAILED] rather than throwing, so a
     * database written by a newer build cannot crash an older one on read.
     */
    @TypeConverter
    fun toDeliveryState(value: String): DeliveryState =
        runCatching { DeliveryState.valueOf(value) }.getOrDefault(DeliveryState.FAILED)
}
