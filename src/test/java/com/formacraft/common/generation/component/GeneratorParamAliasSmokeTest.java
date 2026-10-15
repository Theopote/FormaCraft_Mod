package com.formacraft.common.generation.component;

import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.generation.component.util.RoofTypeCatalog;
import com.formacraft.common.generation.design.DesignParameterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratorParamAliasSmokeTest {

    @Test
    void keysForIncludesSnakeCaseAndRegistryAliases() {
        String[] keys = ComponentParamParsers.keysFor("overhang");
        assertTrue(keys.length >= 3);
        assertEquals("overhang", keys[0]);
        assertTrue(DesignParameterRegistry.aliases("overhang").contains(keys[1])
                || DesignParameterRegistry.aliases("overhang").contains(keys[keys.length - 1]));
    }

    @Test
    void intParamReadsSnakeCaseOnlyForRegisteredParams() {
        Map<String, Object> params = Map.of("eave_overhang", 3);
        assertEquals(3, ComponentParamParsers.intParam(params, ComponentParamParsers.keysFor("overhang")));
    }

    @Test
    void intParamReadsFloorCountSnakeCase() {
        Map<String, Object> params = Map.of("floor_count", 4);
        assertEquals(4, ComponentParamParsers.intParam(params, ComponentParamParsers.keysFor("floor_count")));
    }

    @Test
    void doubleOrNullReadsRoofPitchSnakeCase() {
        Map<String, Object> params = Map.of("roof_pitch_degrees", 58);
        assertEquals(58.0, ComponentParamParsers.doubleOrNull(
                params, ComponentParamParsers.keysFor("roof_pitch_degrees")));
    }

    @Test
    void intParamReadsWallThicknessSnakeCase() {
        Map<String, Object> params = Map.of("wall_thickness", 2);
        assertEquals(2, ComponentParamParsers.intParam(params, ComponentParamParsers.keysFor("wall_thickness")));
    }

    @Test
    void roofTypeCatalogReadsSnakeCaseParam() {
        assertTrue(RoofTypeCatalog.parseExplicit("gable").isPresent());
        assertEquals("gable", RoofTypeCatalog.extractExplicitParamType(Map.of("roof_type", "gable")).orElseThrow());
        assertFalse(RoofTypeCatalog.isSupportedExplicit("sawtooth"));
    }
}
