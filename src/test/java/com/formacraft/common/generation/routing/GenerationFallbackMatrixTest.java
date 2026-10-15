package com.formacraft.common.generation.routing;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationFallbackMatrixTest {

    @Test
    void componentsUsesExplicitFlagsWhenPolicyBlocksEmptyFallback() {
        GenerationFallbackPolicy policy = GenerationFallbackPolicy.forAuthority(GenerationAuthority.COMPONENTS);
        Component mass = mass(Map.of("allow_typology_empty_fallback", true));
        Map<String, Object> planMeta = Map.of("allow_structure_empty_fallback", true);

        assertFalse(policy.allowTypologyWhenBaseEmpty());
        assertFalse(policy.allowStructureWhenBaseEmpty());
        assertTrue(GenerationFallbackMatrix.effectiveTypologyEmpty(
                GenerationAuthority.COMPONENTS, policy, mass, planMeta));
        assertTrue(GenerationFallbackMatrix.effectiveStructureEmpty(
                GenerationAuthority.COMPONENTS, policy, mass, planMeta));
    }

    @Test
    void componentsWithoutFlagsBlocksEmptyFallback() {
        GenerationFallbackPolicy policy = GenerationFallbackPolicy.forAuthority(GenerationAuthority.COMPONENTS);
        Component mass = mass(Map.of());

        assertFalse(GenerationFallbackMatrix.effectiveTypologyEmpty(
                GenerationAuthority.COMPONENTS, policy, mass, Map.of()));
        assertFalse(GenerationFallbackMatrix.effectiveStructureEmpty(
                GenerationAuthority.COMPONENTS, policy, mass, Map.of()));
    }

    @Test
    void typologyPolicyAllowsTypologyEmptyIgnoresExplicitFlags() {
        GenerationFallbackPolicy policy = GenerationFallbackPolicy.forAuthority(GenerationAuthority.TYPOLOGY);
        Map<String, Object> planMeta = Map.of(
                "allow_typology_empty_fallback", true,
                "allow_structure_empty_fallback", true);

        assertTrue(GenerationFallbackMatrix.effectiveTypologyEmpty(
                GenerationAuthority.TYPOLOGY, policy, mass(Map.of()), planMeta));
        assertFalse(GenerationFallbackMatrix.effectiveStructureEmpty(
                GenerationAuthority.TYPOLOGY, policy, mass(Map.of()), planMeta));
    }

    @Test
    void typologyIgnoresStructureFlagEvenWhenSet() {
        GenerationFallbackPolicy policy = GenerationFallbackPolicy.forAuthority(GenerationAuthority.TYPOLOGY);
        Map<String, Object> planMeta = Map.of("allow_structure_empty_fallback", true);

        assertFalse(GenerationFallbackMatrix.effectiveStructureEmpty(
                GenerationAuthority.TYPOLOGY, policy, mass(Map.of()), planMeta));
    }

    @Test
    void dedicatedAuthoritiesIgnoreExplicitFlags() {
        for (GenerationAuthority authority : List.of(
                GenerationAuthority.CAPTURED_LIBRARY,
                GenerationAuthority.ASSEMBLY,
                GenerationAuthority.SKELETON)) {
            GenerationFallbackPolicy policy = GenerationFallbackPolicy.forAuthority(authority);
            Map<String, Object> planMeta = Map.of(
                    "allow_typology_empty_fallback", true,
                    "allow_structure_empty_fallback", true);
            Component mass = mass(Map.of(
                    "allow_typology_empty_fallback", true,
                    "allow_structure_empty_fallback", true));

            assertFalse(GenerationFallbackMatrix.effectiveTypologyEmpty(
                    authority, policy, mass, planMeta), authority.name());
            assertFalse(GenerationFallbackMatrix.effectiveStructureEmpty(
                    authority, policy, mass, planMeta), authority.name());
        }
    }

    private static Component mass(Map<String, Object> params) {
        return new Component("MASS_MAIN", "body", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 4), List.of(), params);
    }
}
