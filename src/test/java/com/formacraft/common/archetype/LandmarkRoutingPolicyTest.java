package com.formacraft.common.archetype;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void explicitBirdsNestWithVariation_isMandatory() {
        LandmarkRoutingPolicy.RoutingDecision d = LandmarkRoutingPolicy.resolveForUserIntent(
                "给我做个不一样的鸟巢体育馆");
        assertTrue(d != null && d.applies());
        assertEquals("birds_nest_stadium", d.moduleId());
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

    @Test
    void sagradaFamilia_doesNotForceGothicCathedralModule() {
        assertNull(LandmarkRoutingPolicy.resolveForUserIntent("生成圣家族大教堂"));
    }

    @Test
    void gothicCathedral_explicitName_isMandatory() {
        LandmarkRoutingPolicy.RoutingDecision d = LandmarkRoutingPolicy.resolveForUserIntent("生成一座哥特大教堂");
        assertNotNull(d);
        assertEquals("gothic_cathedral", d.moduleId());
        assertEquals(LandmarkRoutingPolicy.RoutingTier.MANDATORY, d.tier());
    }

    @Test
    void notreDameParis_doesNotForceGothicCathedralModule() {
        assertNull(LandmarkRoutingPolicy.resolveForUserIntent("复原巴黎圣母院"));
        assertNull(LandmarkModuleRegistry.resolveModuleId("巴黎圣母院"));
    }

    @Test
    void cologneCathedral_doesNotForceGothicCathedralModule() {
        assertNull(LandmarkRoutingPolicy.resolveForUserIntent("建造科隆大教堂"));
    }

    @Test
    void fushimiInari_doesNotForceJapaneseShrineModule() {
        assertNull(LandmarkRoutingPolicy.resolveForUserIntent("建造伏见稻荷神社"));
        assertNull(LandmarkModuleRegistry.resolveModuleId("伏见稻荷神社"));
    }

    @Test
    void wuzhen_doesNotForceJiangnanWaterTownModule() {
        assertNull(LandmarkRoutingPolicy.resolveForUserIntent("建造乌镇"));
        assertNull(LandmarkModuleRegistry.resolveModuleId("乌镇"));
    }

    @Test
    void jiangnanExplicit_isMandatory() {
        LandmarkRoutingPolicy.RoutingDecision d =
                LandmarkRoutingPolicy.resolveForUserIntent("生成江南水乡");
        assertNotNull(d);
        assertEquals("jiangnan_water_town", d.moduleId());
    }
}
