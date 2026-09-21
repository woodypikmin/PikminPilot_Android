package com.pikminpilot.android.model;

import org.junit.Test;
import static org.junit.Assert.*;

public class SelectionPolicyTest {
    @Test public void configured2_live12_selected2_satisfied(){
        assertEquals(2,SelectionPolicy.effectiveRequired(2,12));
        assertEquals(SelectionPolicy.Decision.SATISFIED,SelectionPolicy.decision(2,2,12));
    }
    @Test public void configured6_live2_selected2_satisfied(){
        assertEquals(2,SelectionPolicy.effectiveRequired(6,2));
        assertEquals(SelectionPolicy.Decision.SATISFIED,SelectionPolicy.decision(2,6,2));
    }
    @Test public void configured6_live2_selected1_insufficient(){
        assertEquals(SelectionPolicy.Decision.INSUFFICIENT,SelectionPolicy.decision(1,6,2));
    }
    @Test public void configured6_live12_selected2_insufficient(){
        assertEquals(6,SelectionPolicy.effectiveRequired(6,12));
        assertEquals(SelectionPolicy.Decision.INSUFFICIENT,SelectionPolicy.decision(2,6,12));
    }
}
