package com.formacraft.common.archetype;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LandmarkModuleRegistryTest {

    @Test
    void resolveModuleId_matchesNewLandmarks() {
        assertEquals("gothic_cathedral", LandmarkModuleRegistry.resolveModuleId("哥特大教堂"));
        assertEquals("jiangnan_water_town", LandmarkModuleRegistry.resolveModuleId("江南水乡"));
        assertEquals("modern_skyscraper", LandmarkModuleRegistry.resolveModuleId("skyscraper"));
        assertEquals("birds_nest_stadium", LandmarkModuleRegistry.resolveModuleId("椭圆形体育场"));
    }

    @Test
    void promptRoutingHintForIntent_stadiumPrompt() {
        String hint = LandmarkModuleRegistry.promptRoutingHintForIntent(
                "在锚点位置生成现代风格的椭圆形体育场建筑");
        assertTrue(hint.contains("birds_nest_stadium"));
        assertTrue(hint.contains("MODULE"));
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
