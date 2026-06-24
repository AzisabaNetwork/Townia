package net.azisaba.townia.data;

public enum PlotType {
    DEFAULT,
    SHOP,
    ARENA,
    EMBASSY,
    FARM,
    INN;

    public static PlotType fromString(String s) {
        try {
            return PlotType.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }
}
