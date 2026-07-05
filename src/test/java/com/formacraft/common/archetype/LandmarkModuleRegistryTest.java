package com.formacraft.common.archetype;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LandmarkModuleRegistryTest {

    @Test
    void resolveModuleId_rejectsSagradaFamiliaBroadMatch() {
        assertNull(LandmarkModuleRegistry.resolveModuleId("圣家族大教堂"));
    }

    @Test
    void resolveModuleId_rejectsResearchOnlyCathedrals() {
        assertNull(LandmarkModuleRegistry.resolveModuleId("巴黎圣母院"));
        assertNull(LandmarkModuleRegistry.resolveModuleId("notre dame de paris"));
        assertNull(LandmarkModuleRegistry.resolveModuleId("科隆大教堂"));
        assertNull(LandmarkModuleRegistry.resolveModuleId("chartres cathedral"));
        assertNull(LandmarkModuleRegistry.resolveModuleId("sagrada familia"));
        assertNull(LandmarkModuleRegistry.resolveModuleId("悉尼歌剧院"));
        assertNull(LandmarkModuleRegistry.resolveModuleId("卢浮宫"));
        assertNull(LandmarkModuleRegistry.resolveModuleId("苏州博物馆"));
        assertNull(LandmarkModuleRegistry.resolveModuleId("伏见稻荷神社"));
        assertNull(LandmarkModuleRegistry.resolveModuleId("乌镇"));
        assertNull(LandmarkModuleRegistry.resolveModuleId("姬路城"));
    }

    @Test
    void resolveModuleId_matchesJiangnanExplicit() {
        assertEquals("jiangnan_water_town", LandmarkModuleRegistry.resolveModuleId("江南水乡"));
    }

    @Test
    void resolveModuleId_matchesNewLandmarks() {
        assertEquals("gothic_cathedral", LandmarkModuleRegistry.resolveModuleId("哥特大教堂"));
        assertEquals("jiangnan_water_town", LandmarkModuleRegistry.resolveModuleId("江南水乡"));
        assertEquals("modern_skyscraper", LandmarkModuleRegistry.resolveModuleId("skyscraper"));
        assertEquals("birds_nest_stadium", LandmarkModuleRegistry.resolveModuleId("椭圆形体育场"));
    }

    @Test
    void promptRoutingHintForIntent_stadiumPrompt_isSuggestedNotMandatory() {
        String hint = LandmarkModuleRegistry.promptRoutingHintForIntent(
                "在锚点位置生成现代风格的椭圆形体育场建筑");
        assertTrue(hint.contains("birds_nest_stadium"));
        assertTrue(hint.contains("RECOMMENDED"));
        assertTrue(!hint.contains("MANDATORY FOR THIS REQUEST"));
    }

    @Test
    void promptRoutingHintForIntent_explicitBirdsNest_isMandatory() {
        String hint = LandmarkModuleRegistry.promptRoutingHintForIntent("在锚点位置生成鸟巢体育馆");
        assertTrue(hint.contains("birds_nest_stadium"));
        assertTrue(hint.contains("MANDATORY"));
    }

    @Test
    void promptRoutingHintForIntent_creativeIntent_empty() {
        String hint = LandmarkModuleRegistry.promptRoutingHintForIntent(
                "在锚点位置原创设计一座独特的现代椭圆体育场，不要地标");
        assertTrue(hint == null || hint.isBlank());
    }

    @Test
    void resolveModuleIdFromIntent_delegatesToResolveModuleId() {
        assertEquals(
                LandmarkModuleRegistry.resolveModuleId("埃菲尔铁塔"),
                LandmarkModuleRegistry.resolveModuleIdFromIntent("埃菲尔铁塔")
        );
    }

    @Test
    void listModules_includesRegisteredLandmarks() {
        assertTrue(LandmarkModuleRegistry.listModules().size() >= 25);
        assertNotNull(LandmarkModuleRegistry.resolveModuleId("pantheon"));
        assertNull(LandmarkModuleRegistry.resolveModuleId("generic house"));
    }
}
