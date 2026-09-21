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
    /**
     * Safe in-place fallback transition when the current plan has selected zero.
     * With nothing selected there is nothing to clear, so pressing Cancel is
     * unnecessary even if a Cancel pill happens to be visible. Never use this
     * path with a live selection or an enabled GO; otherwise changing colour
     * could stack plans or race a commit.
     */
    public static boolean canSwitchFallbackInPlace(int selected,boolean goEnabled,boolean cancelVisible,boolean selectionPageVisible){
        return selected==0 && !goEnabled && selectionPageVisible;
    }

    /** Return the first enabled plan at/after the sticky floor, or -1. */
    public static int firstEnabledAtOrAfter(boolean[] enabled,int floor){
        if(enabled==null||enabled.length==0) return -1;
        int start=Math.max(0,Math.min(floor,enabled.length));
        for(int i=start;i<enabled.length;i++) if(enabled[i]) return i;
        return -1;
    }

    /** Return the next enabled plan strictly after current, or -1. */
    public static int nextEnabledAfter(boolean[] enabled,int current){
        return firstEnabledAtOrAfter(enabled,current+1);
    }

    /** Sticky fallback progression is monotonic within a START session. */
    public static int advanceStickyFloor(int currentFloor,int enteredPlanIndex){
        return Math.max(Math.max(0,currentFloor),Math.max(0,enteredPlanIndex));
    }

}
