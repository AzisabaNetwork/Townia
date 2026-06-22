package net.azisaba.townia.data;

public enum TownRank {
    RESIDENT(0),
    ASSISTANT(1),
    CO_MAYOR(2),
    MAYOR(3);

    private final int level;

    TownRank(int level) {
        this.level = level;
    }

    public int getLevel() {
        return level;
    }

    public boolean isAtLeast(TownRank other) {
        return this.level >= other.level;
    }

    public static TownRank fromString(String s) {
        try {
            return TownRank.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return RESIDENT;
        }
    }
}
