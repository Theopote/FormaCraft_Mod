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
    void styleDefaultMayFillOnlyWhenAbsent() {
        // Phase 01.9 Task C: STYLE_DEFAULT only for ABSENT — never VALID / DISABLED / INVALID.
        assertTrue(DesignDecisionPriority.styleDefaultMayFill(null));
        assertFalse(DesignDecisionPriority.styleDefaultMayFill("warm_lantern"));
        assertFalse(DesignDecisionPriority.styleDefaultMayFill("none"));
        assertFalse(DesignDecisionPriority.styleDefaultMayFill("off"));
        assertFalse(DesignDecisionPriority.styleDefaultMayFill("false"));
        assertFalse(DesignDecisionPriority.styleDefaultMayFill(false));
        assertFalse(DesignDecisionPriority.styleDefaultMayFill(0));
        assertFalse(DesignDecisionPriority.styleDefaultMayFill("not_a_real_style"));
        assertFalse(DesignDecisionPriority.styleDefaultMayFill(""));
    }

    @Test
    void classifyAestheticParamCoversAbsentValidDisabledInvalid() {
        assertEquals(DesignDecisionPriority.AestheticParamClass.ABSENT,
                DesignDecisionPriority.classifyAestheticParam(null));
        assertEquals(DesignDecisionPriority.AestheticParamClass.DISABLED,
                DesignDecisionPriority.classifyAestheticParam(false));
        assertEquals(DesignDecisionPriority.AestheticParamClass.DISABLED,
                DesignDecisionPriority.classifyAestheticParam(0));
        assertEquals(DesignDecisionPriority.AestheticParamClass.DISABLED,
                DesignDecisionPriority.classifyAestheticParam("none"));
        assertEquals(DesignDecisionPriority.AestheticParamClass.DISABLED,
                DesignDecisionPriority.classifyAestheticParam(""));
        assertEquals(DesignDecisionPriority.AestheticParamClass.VALID,
                DesignDecisionPriority.classifyAestheticParam("warm_lantern"));
        assertEquals(DesignDecisionPriority.AestheticParamClass.VALID,
                DesignDecisionPriority.classifyAestheticParam("not_a_real_style"));
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
