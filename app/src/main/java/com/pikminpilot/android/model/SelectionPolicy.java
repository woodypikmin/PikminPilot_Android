package com.pikminpilot.android.model;

/** Pure truth-table logic for selection fallback / GO decisions. */
public final class SelectionPolicy {
    private SelectionPolicy() {}

    public enum Decision { COMMIT_GO, FALLBACK, GO_RECOVERY }

    public static int effectiveRequired(int configuredCount,int liveMaximum){
        if(liveMaximum<1) throw new IllegalArgumentException("liveMaximum must be >= 1");
        return Math.min(Math.max(1,configuredCount),liveMaximum);
    }

    /**
     * The game's enabled GO is authoritative evidence that the selected team is
     * dispatchable. configuredCount is a preference, not a requirement once GO
     * is visibly enabled. Require selected>0 to protect against a false-positive
     * GO detector.
     */
    public static Decision decision(int selected,int configuredCount,int liveMaximum,boolean goEnabled){
        if(goEnabled && selected>0) return Decision.COMMIT_GO;
        return selected<effectiveRequired(configuredCount,liveMaximum)
                ?Decision.FALLBACK:Decision.GO_RECOVERY;
    }
}
