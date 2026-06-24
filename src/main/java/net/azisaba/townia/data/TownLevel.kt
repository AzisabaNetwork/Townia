package net.azisaba.townia.data

enum class TownLevel(val level: Int, val levelName: String, val requiredResidents: Int) {
    SETTLEMENT(1, "Settlement", 1),
    HAMLET(2, "Hamlet", 2),
    VILLAGE(3, "Village", 6),
    TOWN(4, "Town", 10),
    LARGE_TOWN(5, "Large Town", 14),
    CITY(6, "City", 20),
    LARGE_CITY(7, "Large City", 24),
    METROPOLIS(8, "MetroPolis", 28);

    companion object {
        fun getLevelForResidents(residents: Int): TownLevel {
            var current = TownLevel.SETTLEMENT
            for (level in entries) {
                if (residents >= level.requiredResidents) {
                    current = level
                } else {
                    break
                }
            }
            return current
        }
    }
}
