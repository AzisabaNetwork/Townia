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
        fun fromString(s: String): PlotType {
            try {
                return valueOf(s.uppercase(Locale.getDefault()))
            } catch (e: IllegalArgumentException) {
                return PlotType.DEFAULT
            }
        }
    }
}