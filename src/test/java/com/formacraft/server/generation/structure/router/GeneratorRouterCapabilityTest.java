package com.formacraft.server.generation.structure.router;

import com.formacraft.common.generation.routing.GenerationCapability;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratorRouterCapabilityTest {

    @Test
    void nullTypeUnsupportedWithoutLegacyFlag() {
        BuildingSpec spec = new BuildingSpec();
        var decision = GeneratorRouter.routeDecision(spec);
        assertEquals(GenerationCapability.UNSUPPORTED, decision.capability());
        assertTrue(decision.generatorRegistryKey() == null || decision.generatorRegistryKey().isBlank());
        assertNull(GeneratorRouter.route(spec));
    }

    @Test
    void legacyFallbackAllowsHouseWhenFlagSet() {
        BuildingSpec spec = new BuildingSpec();
        spec.setType(BuildingType.CUSTOM);
        Map<String, Object> extra = new HashMap<>();
        extra.put("allow_legacy_fallback", true);
        spec.setExtra(extra);
        var decision = GeneratorRouter.routeDecision(spec);
        assertEquals(GenerationCapability.SUPPORTED, decision.capability());
        assertNotNull(decision.generatorRegistryKey());
        assertNotNull(GeneratorRouter.route(spec));
    }

    @Test
    void catalogMappedCustomUsesExplicitHouseKeyNotSilentFallback() {
        BuildingSpec spec = new BuildingSpec();
        spec.setType(BuildingType.CUSTOM);
        var decision = GeneratorRouter.routeDecision(spec);
        assertEquals(GenerationCapability.SUPPORTED, decision.capability());
        assertEquals("building_type_catalog", decision.reasonCode());
        assertEquals("house", decision.generatorRegistryKey());
    }
}
