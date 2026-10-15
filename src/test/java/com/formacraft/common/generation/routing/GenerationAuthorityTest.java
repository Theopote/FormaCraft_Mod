package com.formacraft.common.generation.routing;

import com.formacraft.common.generation.routing.GenerationFallbackPolicy;
import com.formacraft.common.generation.routing.PlanMetaAccess;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationAuthorityTest {

    @Test
    void defaultsToComponentsWhenUnset() {
        Component mass = mass(Map.of());
        assertEquals(GenerationAuthority.COMPONENTS, GenerationAuthority.resolve(mass, Map.of()));
    }

    @Test
    void componentParamsOverridePlanMeta() {
        Component mass = mass(Map.of("generation_authority", "TYPOLOGY"));
        assertEquals(GenerationAuthority.TYPOLOGY,
                GenerationAuthority.resolve(mass, Map.of("generation_authority", "COMPONENTS")));
    }

    @Test
    void planMetaUsedWhenComponentUnset() {
        Component mass = mass(Map.of());
        assertEquals(GenerationAuthority.TYPOLOGY,
                GenerationAuthority.resolve(mass, Map.of("generation_authority", "TYPOLOGY")));
    }

    @Test
    void componentsDoesNotAllowTypologyReplaceUnlessOverride() {
        assertFalse(GenerationAuthority.COMPONENTS.allowsTypologyReplace(false));
        assertTrue(GenerationAuthority.COMPONENTS.allowsTypologyReplace(true));
        assertTrue(GenerationAuthority.TYPOLOGY.allowsTypologyReplace(false));
    }

    @Test
    void assemblyAndSkeletonForbidEmptyBaseFallback() {
        assertFalse(GenerationAuthority.ASSEMBLY.allowsEmptyBaseFallback());
        assertFalse(GenerationAuthority.SKELETON.allowsEmptyBaseFallback());
        assertFalse(GenerationAuthority.COMPONENTS.allowsEmptyBaseFallback());
        assertFalse(GenerationAuthority.CAPTURED_LIBRARY.allowsEmptyBaseFallback());
    }

    @Test
    void capturedLibraryFallbackPolicyBlocksTypologyAndStructure() {
        GenerationFallbackPolicy policy = GenerationAuthority.CAPTURED_LIBRARY.fallbackPolicy();
        assertFalse(policy.allowTypologyWhenBaseEmpty());
        assertFalse(policy.allowStructureWhenBaseEmpty());
    }

    @Test
    void componentsFallbackPolicyBlocksEmptyTypologyAndStructure() {
        GenerationFallbackPolicy policy = GenerationAuthority.COMPONENTS.fallbackPolicy();
        assertFalse(policy.allowTypologyWhenBaseEmpty());
        assertFalse(policy.allowStructureWhenBaseEmpty());
        assertTrue(policy.allowRegistryPriming());
    }

    @Test
    void parseStrictRejectsUnknownAuthority() {
        assertThrows(IllegalArgumentException.class, () -> GenerationAuthority.parseStrict("TYPOLOOGY"));
    }

    @Test
    void parseStrictAcceptsKnownAuthority() {
        assertEquals(GenerationAuthority.TYPOLOGY, GenerationAuthority.parseStrict("typology"));
    }

    @Test
    void parseStrictTreatsBlankAsUnset() {
        assertNull(GenerationAuthority.parseStrict(""));
        assertNull(GenerationAuthority.parseStrict(null));
    }

    @Test
    void validateRawIncludesPathInMessage() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> GenerationAuthority.validateRaw("BOGUS", "plan_meta"));
        assertTrue(ex.getMessage().contains("plan_meta"));
        assertTrue(ex.getMessage().contains("BOGUS"));
    }

    @Test
    void componentParamsOverridePlanMetaForEmptyFallbackFlags() {
        Component mass = mass(Map.of("allow_typology_empty_fallback", true));
        assertTrue(PlanMetaAccess.allowTypologyEmptyFallback(mass, Map.of()));
        assertFalse(PlanMetaAccess.allowStructureEmptyFallback(mass, Map.of()));
    }

    private static Component mass(Map<String, Object> params) {
        return new Component("MASS_MAIN", "body", new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 8), List.of(), params);
    }
}
