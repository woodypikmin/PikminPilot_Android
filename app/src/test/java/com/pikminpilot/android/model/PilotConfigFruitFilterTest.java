package com.pikminpilot.android.model;

import org.junit.Test;
import java.util.Collections;
import java.util.EnumSet;
import static org.junit.Assert.*;

public class PilotConfigFruitFilterTest {
    @Test public void allFruitAcceptsEveryGroupAndUnknown(){
        PilotConfig c=new PilotConfig(PilotConfig.PikminType.PINK,PilotConfig.CargoMode.FRUIT,6,5,false,
                Collections.emptyList(),true,EnumSet.noneOf(PilotConfig.FruitGroup.class));
        assertTrue(c.acceptsFruitGroup(PilotConfig.FruitGroup.GREEN));
        assertTrue(c.acceptsFruitGroup(PilotConfig.FruitGroup.YELLOW));
        assertTrue(c.acceptsFruitGroup(null));
    }

    @Test public void selectiveFruitRejectsUnselectedAndUnknown(){
        PilotConfig c=new PilotConfig(PilotConfig.PikminType.PINK,PilotConfig.CargoMode.FRUIT,6,5,false,
                Collections.emptyList(),false,EnumSet.of(PilotConfig.FruitGroup.GREEN,PilotConfig.FruitGroup.BLUE));
        assertTrue(c.acceptsFruitGroup(PilotConfig.FruitGroup.GREEN));
        assertTrue(c.acceptsFruitGroup(PilotConfig.FruitGroup.BLUE));
        assertFalse(c.acceptsFruitGroup(PilotConfig.FruitGroup.YELLOW));
        assertFalse(c.acceptsFruitGroup(PilotConfig.FruitGroup.RED));
        assertFalse(c.acceptsFruitGroup(null));
    }
}
