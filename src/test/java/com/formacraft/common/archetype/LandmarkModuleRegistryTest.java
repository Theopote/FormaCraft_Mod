package com.formacraft.common.archetype;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
        assertEquals("jiangnan_water_town", LandmarkModuleRegistry.resolveModuleId("江南水乡"));
        assertNull(LandmarkModuleRegistry.resolveModuleId("哥特大教堂"));
        assertNull(LandmarkModuleRegistry.resolveModuleId("skyscraper"));
        assertNull(LandmarkModuleRegistry.resolveModuleId("椭圆形体育场"));
    }

    @Test
    void promptRoutingHintForIntent_stadiumPrompt_recommendsTypologyWithoutNamedReference() {
        String hint = LandmarkModuleRegistry.promptRoutingHintForIntent(
                "在锚点位置生成现代风格的椭圆形体育场建筑");
        assertTrue(hint.contains("typology:stadium_bowl"));
        assertTrue(hint.contains("RECOMMENDED"));
        assertFalse(hint.contains("reference_landmark=birds_nest_stadium"));
        assertFalse(hint.contains("\"component_type\": \"MODULE\""));
        assertFalse(hint.contains("MANDATORY FOR THIS REQUEST"));
    }

    @Test
    void promptRoutingHintForIntent_explicitBirdsNest_usesTypologyReference() {
        String hint = LandmarkModuleRegistry.promptRoutingHintForIntent("在锚点位置生成鸟巢体育馆");
        assertTrue(hint.contains("typology:stadium_bowl"));
        assertTrue(hint.contains("reference_landmark=birds_nest_stadium"));
        assertTrue(hint.contains("follow that result"));
        assertFalse(hint.contains("\"component_type\": \"MODULE\""));
    }

    @Test
    void promptRoutingHintForIntent_creativeIntent_empty() {
        String hint = LandmarkModuleRegistry.promptRoutingHintForIntent(
                "在锚点位置原创设计一座独特的现代椭圆体育场，不要地标");
        assertTrue(hint.isBlank());
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
        var modules = LandmarkModuleRegistry.listModules();
        assertFalse(modules.isEmpty());
        assertTrue(modules.stream().anyMatch(m -> m.moduleId().equals("pantheon")));
        assertTrue(modules.stream().anyMatch(m -> m.moduleId().equals("jiangnan_water_town")));
        assertTrue(modules.stream().noneMatch(m -> m.moduleId().equals("birds_nest_stadium")
                || m.moduleId().equals("gothic_cathedral") || m.moduleId().equals("notre_dame")));
        assertEquals(modules.size(), modules.stream().map(LandmarkModuleRegistry.LandmarkModule::moduleId)
                .distinct().count());
        assertNotNull(LandmarkModuleRegistry.resolveModuleId("pantheon"));
        assertNull(LandmarkModuleRegistry.resolveModuleId("generic house"));
    }
}
