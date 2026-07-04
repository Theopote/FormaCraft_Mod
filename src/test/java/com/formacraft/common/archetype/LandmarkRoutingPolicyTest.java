package com.formacraft.common.archetype;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LandmarkRoutingPolicyTest {

    @Test
    void typologicalEllipticalStadium_isSuggested() {
        LandmarkRoutingPolicy.RoutingDecision d = LandmarkRoutingPolicy.resolveForUserIntent(
                "在锚点位置生成现代风格的椭圆形体育场建筑");
        assertTrue(d != null && d.applies());
        assertEquals("birds_nest_stadium", d.moduleId());
        assertEquals(LandmarkRoutingPolicy.RoutingTier.SUGGESTED, d.tier());
    }

    @Test
    void explicitBirdsNest_isMandatory() {
        LandmarkRoutingPolicy.RoutingDecision d = LandmarkRoutingPolicy.resolveForUserIntent(
                "在锚点位置生成鸟巢体育馆");
        assertTrue(d != null && d.applies());
        assertEquals(LandmarkRoutingPolicy.RoutingTier.MANDATORY, d.tier());
    }

    @Test
    void creativeIntent_returnsNull() {
        assertTrue(LandmarkRoutingPolicy.isCreativeOrOriginalIntent(
                "在锚点位置原创设计一座独特的现代椭圆体育场，不要地标"));
        assertNull(LandmarkRoutingPolicy.resolveForUserIntent(
                "在锚点位置原创设计一座独特的现代椭圆体育场，不要地标"));
    }

    @Test
    void variationPrinciples_nonEmpty() {
        assertTrue(LandmarkRoutingPolicy.promptVariationPrinciples().contains("BUILDING VARIATION"));
    }
}
