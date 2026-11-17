package net.azisaba.townia.data

import java.util.*

enum class PlotType {
    DEFAULT,
    SHOP,
    ARENA,
    EMBASSY,
    FARM,
    INN;

    companion object {
        fun parseOrNull(s: String): PlotType? {
            return when (val upper = s.uppercase(Locale.getDefault())) {
                "COMMERCIAL" -> SHOP
                "RESIDENTIAL" -> DEFAULT
                else -> entries.firstOrNull { it.name == upper }
            }
        }

        fun fromString(s: String): PlotType = parseOrNull(s) ?: DEFAULT
    }
}