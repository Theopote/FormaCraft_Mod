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
