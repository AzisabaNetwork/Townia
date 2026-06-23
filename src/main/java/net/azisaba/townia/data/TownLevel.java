package net.azisaba.townia.data;

public enum TownLevel {
    SETTLEMENT(1, "Settlement", 1),
    HAMLET(2, "Hamlet", 2),
    VILLAGE(3, "Village", 6),
    TOWN(4, "Town", 10),
    LARGE_TOWN(5, "Large Town", 14),
    CITY(6, "City", 20),
    LARGE_CITY(7, "Large City", 24),
    METROPOLIS(8, "MetroPolis", 28);

    private final int level;
    private final String name;
    private final int requiredResidents;

    TownLevel(int level, String name, int requiredResidents) {
        this.level = level;
        this.name = name;
        this.requiredResidents = requiredResidents;
    }

    public int getLevel() {
        return level;
    }

    public String getName() {
        return name;
    }

    public int getRequiredResidents() {
        return requiredResidents;
    }

    public static TownLevel getLevelForResidents(int residents) {
        TownLevel current = SETTLEMENT;
        for (TownLevel level : values()) {
            if (residents >= level.getRequiredResidents()) {
                current = level;
            } else {
                break;
            }
        }
        return current;
    }
}
