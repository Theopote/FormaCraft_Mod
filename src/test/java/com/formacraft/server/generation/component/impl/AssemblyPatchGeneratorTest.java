package com.formacraft.server.generation.component.impl;

import com.formacraft.common.generation.component.ComponentGeneratorRegistry;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssemblyPatchGeneratorTest {

    @Test
    void resolvesNestedAssemblyPayload() {
        Map<String, Object> params = Map.of(
                "assembly", Map.of(
                        "entranceFacing", "SOUTH",
                        "graph", Map.of("components", java.util.List.of())
                )
        );
        assertNotNull(AssemblyPatchGenerator.resolveAssemblyPayload(params));
    }

    @Test
    void resolvesTopLevelGraphAsAssemblyRoot() {
        Map<String, Object> params = new HashMap<>();
        params.put("macro", Map.of("style", Map.of("styleId", "Gothic_Cathedral")));
        params.put("graph", Map.of("components", java.util.List.of()));

        Object payload = AssemblyPatchGenerator.resolveAssemblyPayload(params);
        assertNotNull(payload);
        assertTrue(payload instanceof Map);
    }

    @Test
    void returnsNullWhenNoAssemblyFields() {
        assertNull(AssemblyPatchGenerator.resolveAssemblyPayload(Map.of("roof_type", "gable")));
        assertNull(AssemblyPatchGenerator.resolveAssemblyPayload(null));
    }

    @Test
    void compilerAcceptsAssemblyComponentType() {
        assertTrue(ComponentGeneratorRegistry.hasGenerator("ASSEMBLY"));
    }

    @Test
    void generateWithoutWorldDoesNotSetGap() {
        com.formacraft.server.assembly.AssemblyCompileDiagnostics.clear();
        AssemblyPatchGenerator.GenerateResult result = AssemblyPatchGenerator.generate(null, null);
        assertFalse(result.hasGap());
        assertNull(com.formacraft.server.assembly.AssemblyCompileDiagnostics.get());
    }
}
