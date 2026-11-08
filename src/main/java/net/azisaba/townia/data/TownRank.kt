package net.azisaba.townia.data

import java.util.*

enum class TownRank(val level: Int) {
    RESIDENT(0),
    ASSISTANT(1),
    SUB_MAYOR(2),
    MAYOR(3);

    fun isAtLeast(other: TownRank): Boolean {
        return this.level >= other.level
    }

    companion object {
        fun fromString(s: String): TownRank {
            // Ranks stored before CO_MAYOR was renamed to SUB_MAYOR must keep their meaning,
            // otherwise those residents silently drop to RESIDENT.
            val normalized = s.trim().uppercase(Locale.ROOT).replace('-', '_').replace(' ', '_')
            return when (normalized) {
                "CO_MAYOR", "COMAYOR", "SUBMAYOR" -> SUB_MAYOR
                else -> try {
                    valueOf(normalized)
                } catch (e: IllegalArgumentException) {
                    RESIDENT
                }
            }
        }
    }
}