package com.pikminpilot.android.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PilotConfig {
    public enum PikminType { PINK, WHITE, PURPLE, ROCK, RED, YELLOW, BLUE }
    public enum CargoMode { FRUIT, SEEDLING, BOTH }

    public static final class SelectionPlan {
        public final String name;
        public final PikminType type;
        public final int configuredCount;
        public final boolean enabled;

        public SelectionPlan(String name, PikminType type, int configuredCount, boolean enabled) {
            this.name=name;
            this.type=type;
            this.configuredCount=Math.max(1,Math.min(12,configuredCount));
            this.enabled=enabled;
        }

        @Override public String toString(){
            return name+"="+pikminName(type)+"×"+configuredCount+(enabled?"":"(off)");
        }
    }

    public final PikminType type;
    public final CargoMode cargoMode;
    public final int pikminCount;
    public final int dispatchTarget; // 0 = infinite
    public final boolean fast;
    public final List<SelectionPlan> selectionPlans;

    public PilotConfig(PikminType type, CargoMode cargoMode, int pikminCount, int dispatchTarget, boolean fast) {
        this(type,cargoMode,pikminCount,dispatchTarget,fast,Collections.emptyList());
    }

    public PilotConfig(PikminType type, CargoMode cargoMode, int pikminCount, int dispatchTarget, boolean fast,
                       List<SelectionPlan> fallbacks) {
        this.type=type;
        this.cargoMode=cargoMode;
        int minimum=minimumCount(type);
        this.pikminCount=Math.max(minimum,Math.min(12,pikminCount));
        this.dispatchTarget=Math.max(0,dispatchTarget);
        this.fast=fast;
        List<SelectionPlan> plans=new ArrayList<>();
        plans.add(new SelectionPlan("PRIMARY",type,this.pikminCount,true));
        if(fallbacks!=null){
            int idx=1;
            for(SelectionPlan p:fallbacks){
                if(p==null) continue;
                plans.add(new SelectionPlan("FALLBACK-"+idx,p.type,p.configuredCount,p.enabled));
                idx++;
                if(idx>3) break;
            }
        }
        this.selectionPlans=Collections.unmodifiableList(plans);
    }

    public static int minimumCount(PikminType type) {
        return (type==PikminType.PINK||type==PikminType.WHITE)?6:2;
    }

    public static String pikminName(PikminType type) {
        switch(type) {
            case PINK: return "粉紅";
            case WHITE: return "白";
            case PURPLE: return "紫";
            case ROCK: return "岩";
            case RED: return "紅";
            case YELLOW: return "黃";
            case BLUE: return "藍";
            default: return type.name();
        }
    }

    public static String cargoName(CargoMode mode) {
        switch(mode) {
            case FRUIT: return "水果";
            case SEEDLING: return "花苗";
            case BOTH: return "水果＋花苗";
            default: return mode.name();
        }
    }
}
