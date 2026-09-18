package com.pikminpilot.android.model;

public final class PilotConfig {
    public enum PikminType { PURPLE, WHITE, PINK, ROCK }

    public final PikminType type;
    public final int pikminCount;
    public final int dispatchTarget;
    public final boolean fast;

    public PilotConfig(PikminType type, int pikminCount, int dispatchTarget, boolean fast) {
        this.type = type;
        this.pikminCount = Math.max(2, Math.min(12, pikminCount));
        this.dispatchTarget = Math.max(0, dispatchTarget);
        this.fast = fast;
    }
}
