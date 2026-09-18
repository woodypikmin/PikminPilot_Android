package com.pikminpilot.android.model;

public final class PilotConfig {
    public enum PikminType { PINK, WHITE, PURPLE, ROCK }
    public enum CargoMode { FRUIT, SEEDLING, BOTH }

    public final PikminType type;
    public final CargoMode cargoMode;
    public final int pikminCount;
    public final int dispatchTarget; // 0 = infinite
    public final boolean fast;

    public PilotConfig(PikminType type, CargoMode cargoMode, int pikminCount, int dispatchTarget, boolean fast) {
        this.type = type;
        this.cargoMode = cargoMode;
        int minimum = (type == PikminType.PINK || type == PikminType.WHITE) ? 6 : 2;
        this.pikminCount = Math.max(minimum, Math.min(12, pikminCount));
        this.dispatchTarget = Math.max(0, dispatchTarget);
        this.fast = fast;
    }

    public static int minimumCount(PikminType type) {
        return (type == PikminType.PINK || type == PikminType.WHITE) ? 6 : 2;
    }

    public static String pikminName(PikminType type) {
        switch (type) {
            case PINK: return "粉紅";
            case WHITE: return "白";
            case PURPLE: return "紫";
            case ROCK: return "岩";
            default: return type.name();
        }
    }

    public static String cargoName(CargoMode mode) {
        switch (mode) {
            case FRUIT: return "水果";
            case SEEDLING: return "花苗";
            case BOTH: return "水果＋花苗";
            default: return mode.name();
        }
    }
}
