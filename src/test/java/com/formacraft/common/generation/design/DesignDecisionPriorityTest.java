package com.formacraft.common.generation.design;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DesignDecisionPriorityTest {

    @Test
    void ladderOrderMatchesAuthorityContract() {
        assertTrue(DesignDecisionPriority.HARD_CONSTRAINT.outranks(DesignDecisionPriority.USER_EXPLICIT));
        assertTrue(DesignDecisionPriority.USER_EXPLICIT.outranks(DesignDecisionPriority.AI_PLANNED));
        assertTrue(DesignDecisionPriority.AI_PLANNED.outranks(DesignDecisionPriority.GEOMETRY_EXECUTION));
        assertTrue(DesignDecisionPriority.GEOMETRY_EXECUTION.outranks(DesignDecisionPriority.STYLE_DEFAULT));
        assertFalse(DesignDecisionPriority.STYLE_DEFAULT.outranks(DesignDecisionPriority.USER_EXPLICIT));
    }

    @Test
    void styleDefaultMayFillRespectsExplicitDisableMarkers() {
        assertTrue(DesignDecisionPriority.styleDefaultMayFill(null));
        assertTrue(DesignDecisionPriority.styleDefaultMayFill("warm_lantern"));
        assertFalse(DesignDecisionPriority.styleDefaultMayFill("none"));
        assertFalse(DesignDecisionPriority.styleDefaultMayFill("off"));
        assertFalse(DesignDecisionPriority.styleDefaultMayFill("false"));
        assertFalse(DesignDecisionPriority.styleDefaultMayFill(false));
        assertFalse(DesignDecisionPriority.styleDefaultMayFill(0));
    }

    @Test
    void lockedDesignVersusHardConstraintRequiresCapabilityGap() {
        assertTrue(DesignDecisionPriority.requiresCapabilityGap(
                DesignParamSource.USER_EXPLICIT, true));
        assertTrue(DesignDecisionPriority.requiresCapabilityGap(
                DesignParamSource.AI_PLANNED, true));
        assertFalse(DesignDecisionPriority.requiresCapabilityGap(
                DesignParamSource.TECHNICAL_DEFAULT, true));
        assertFalse(DesignDecisionPriority.requiresCapabilityGap(
                DesignParamSource.USER_EXPLICIT, false));
    }

    @Test
    void fromSourceMapsProvenanceOntoLadder() {
        assertEquals(DesignDecisionPriority.USER_EXPLICIT,
                DesignDecisionPriority.fromSource(DesignParamSource.USER_EXPLICIT));
        assertEquals(DesignDecisionPriority.AI_PLANNED,
                DesignDecisionPriority.fromSource(DesignParamSource.AI_PLANNED));
        assertEquals(DesignDecisionPriority.STYLE_DEFAULT,
                DesignDecisionPriority.fromSource(DesignParamSource.TECHNICAL_DEFAULT));
        assertEquals(DesignDecisionPriority.STYLE_DEFAULT,
                DesignDecisionPriority.fromSource(null));
    }
}
