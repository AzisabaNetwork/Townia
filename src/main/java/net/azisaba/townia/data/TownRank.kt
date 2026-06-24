package net.azisaba.townia.data

import java.util.*

enum class TownRank(val level: Int) {
    RESIDENT(0),
    ASSISTANT(1),
    CO_MAYOR(2),
    MAYOR(3);

    fun isAtLeast(other: TownRank): Boolean {
        return this.level >= other.level
    }

    companion object {
        fun fromString(s: String): TownRank {
            try {
                return valueOf(s.uppercase(Locale.getDefault()))
            } catch (e: IllegalArgumentException) {
                return TownRank.RESIDENT
            }
        }
    }
}