package com.pikminpilot.android.model;

/** Pure truth-table logic for selection fallback decisions. */
public final class SelectionPolicy {
    private SelectionPolicy() {}

    public enum Decision { SATISFIED, INSUFFICIENT }

    public static int effectiveRequired(int configuredCount,int liveMaximum){
        if(liveMaximum<1) throw new IllegalArgumentException("liveMaximum must be >= 1");
        return Math.min(Math.max(1,configuredCount),liveMaximum);
    }

    public static Decision decision(int selected,int configuredCount,int liveMaximum){
        return selected>=effectiveRequired(configuredCount,liveMaximum)?Decision.SATISFIED:Decision.INSUFFICIENT;
    }
}
