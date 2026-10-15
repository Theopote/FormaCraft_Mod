package com.formacraft.server.generation.component.adaptor;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.routing.GenerationAuthority;
import com.formacraft.common.generation.routing.GenerationFallbackPolicy;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationAuthorityRoutingTest {

    @Test
    void componentsAuthorityConflictsWithSkeletonHint() {
        Component c = new Component(
                "MASS_MAIN",
                "body",
                new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 4),
                List.of(),
                Map.of("generation_authority", "COMPONENTS"));
        SemanticComponent semantic = new SemanticComponent("MASS_MAIN", null, c);
        ComponentRouteDecision hint = ComponentRouteDecision.of(
                ComponentExecutorId.SKELETON, "embedded_skeleton_resolvable");

        var conflict = GenerationRouteContract.validate(
                GenerationAuthority.COMPONENTS, hint, semantic, null);
        assertTrue(conflict.isPresent());
        assertEquals("generation_authority_conflict", conflict.get().code());
    }

    @Test
    void capturedLibraryWithoutRequestFailsValidation() {
        Component c = new Component(
                "MASS_MAIN",
                "body",
                new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 4),
                List.of(),
                Map.of("generation_authority", "CAPTURED_LIBRARY"));
        SemanticComponent semantic = new SemanticComponent("MASS_MAIN", null, c);

        var conflict = GenerationRouteContract.validate(
                GenerationAuthority.CAPTURED_LIBRARY,
                ComponentRouteResolver.resolve(semantic, null),
                semantic,
                null);
        assertTrue(conflict.isPresent());
        assertEquals("captured_library_miss", conflict.get().code());
    }

    @Test
    void capturedLibraryPolicyDisallowsEmptyFallback() {
        GenerationFallbackPolicy policy = GenerationFallbackPolicy.forAuthority(GenerationAuthority.CAPTURED_LIBRARY);
        assertFalse(policy.allowTypologyWhenBaseEmpty());
        assertFalse(policy.allowStructureWhenBaseEmpty());
        assertFalse(policy.allowRegistryPriming());
    }

    @Test
    void typologyPolicyDisallowsStructureFallback() {
        GenerationFallbackPolicy policy = GenerationFallbackPolicy.forAuthority(GenerationAuthority.TYPOLOGY);
        assertTrue(policy.allowTypologyWhenBaseEmpty());
        assertFalse(policy.allowStructureWhenBaseEmpty());
    }

    @Test
    void strictEmptyFailureForDedicatedAuthorities() {
        assertTrue(GenerationRouteContract.strictEmptyFailure(GenerationAuthority.CAPTURED_LIBRARY));
        assertTrue(GenerationRouteContract.strictEmptyFailure(GenerationAuthority.ASSEMBLY));
        assertTrue(GenerationRouteContract.strictEmptyFailure(GenerationAuthority.SKELETON));
        assertFalse(GenerationRouteContract.strictEmptyFailure(GenerationAuthority.COMPONENTS));
    }
}
