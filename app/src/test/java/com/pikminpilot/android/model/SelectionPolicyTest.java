package com.pikminpilot.android.model;

import org.junit.Test;
import static org.junit.Assert.*;

public class SelectionPolicyTest {
    @Test public void configured2_live12_selected2_goEnabled_commits(){
        assertEquals(2,SelectionPolicy.effectiveRequired(2,12));
        assertEquals(SelectionPolicy.Decision.COMMIT_GO,SelectionPolicy.decision(2,2,12,true));
    }
    @Test public void configured6_live2_selected2_goEnabled_commits(){
        assertEquals(2,SelectionPolicy.effectiveRequired(6,2));
        assertEquals(SelectionPolicy.Decision.COMMIT_GO,SelectionPolicy.decision(2,6,2,true));
    }
    @Test public void configured12_live12_selected4_goEnabled_commits(){
        assertEquals(12,SelectionPolicy.effectiveRequired(12,12));
        assertEquals(SelectionPolicy.Decision.COMMIT_GO,SelectionPolicy.decision(4,12,12,true));
    }
    @Test public void configured6_live12_selected2_goEnabled_commits_evenBelowConfigured(){
        assertEquals(6,SelectionPolicy.effectiveRequired(6,12));
        assertEquals(SelectionPolicy.Decision.COMMIT_GO,SelectionPolicy.decision(2,6,12,true));
    }
    @Test public void selectedZero_neverCommitsEvenIfGoDetectorSaysEnabled(){
        assertEquals(SelectionPolicy.Decision.FALLBACK,SelectionPolicy.decision(0,6,12,true));
    }
    @Test public void configured6_live2_selected1_goDisabled_fallback(){
        assertEquals(SelectionPolicy.Decision.FALLBACK,SelectionPolicy.decision(1,6,2,false));
    }
    @Test public void configured6_live12_selected2_goDisabled_fallback(){
        assertEquals(SelectionPolicy.Decision.FALLBACK,SelectionPolicy.decision(2,6,12,false));
    }
    @Test public void countSatisfiedButGoMissing_isGoRecoveryNotFallback(){
        assertEquals(SelectionPolicy.Decision.GO_RECOVERY,SelectionPolicy.decision(2,6,2,false));
    }
}
