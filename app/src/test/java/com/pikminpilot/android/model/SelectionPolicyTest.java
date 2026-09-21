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
    @Test public void zeroSelected_noGo_noCancel_selectionVisible_canSwitchFallbackInPlace(){
        assertTrue(SelectionPolicy.canSwitchFallbackInPlace(0,false,false,true));
    }
    @Test public void selectedPikmin_neverSwitchesFallbackInPlaceWithoutReset(){
        assertFalse(SelectionPolicy.canSwitchFallbackInPlace(1,false,false,true));
    }
    @Test public void enabledGo_neverSwitchesFallbackInPlace(){
        assertFalse(SelectionPolicy.canSwitchFallbackInPlace(0,true,false,true));
    }
    @Test public void zeroSelected_withVisibleCancel_stillSwitchesFallbackInPlace(){
        assertTrue(SelectionPolicy.canSwitchFallbackInPlace(0,false,true,true));
    }
    @Test public void missingSelectionPage_neverSwitchesFallbackInPlace(){
        assertFalse(SelectionPolicy.canSwitchFallbackInPlace(0,false,false,false));
    }

    @Test public void stickyCursor_primaryToFallback1_nextRunStartsFallback1(){
        boolean[] enabled={true,true,true,true};
        int floor=SelectionPolicy.advanceStickyFloor(0,1);
        assertEquals(1,floor);
        assertEquals(1,SelectionPolicy.firstEnabledAtOrAfter(enabled,floor));
    }
    @Test public void stickyCursor_fallback1ToFallback2_usesSameMonotonicRule(){
        boolean[] enabled={true,true,true,true};
        int floor=SelectionPolicy.advanceStickyFloor(1,2);
        assertEquals(2,floor);
        assertEquals(2,SelectionPolicy.firstEnabledAtOrAfter(enabled,floor));
    }
    @Test public void stickyCursor_neverMovesBackwardAfterLaterPlanSucceeds(){
        assertEquals(2,SelectionPolicy.advanceStickyFloor(2,1));
        assertEquals(3,SelectionPolicy.advanceStickyFloor(2,3));
    }
    @Test public void stickyCursor_skipsDisabledFallbacks(){
        boolean[] enabled={true,false,true,true};
        assertEquals(2,SelectionPolicy.nextEnabledAfter(enabled,0));
        assertEquals(2,SelectionPolicy.firstEnabledAtOrAfter(enabled,1));
    }

}
