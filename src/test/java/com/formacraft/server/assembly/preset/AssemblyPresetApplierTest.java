package com.formacraft.server.assembly.preset;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssemblyPresetApplierTest {

    @Test
    void expandsSpiralWatchtowerPreset() {
        Map<String, Object> assembly = Map.of(
                "preset", "spiral_watchtower",
                "presetParams", Map.of(
                        "height", 32,
                        "footprint", 12,
                        "twistTurns", 1.0,
                        "styleId", "Gothic_Cathedral"
                )
        );

        AssemblyPresetApplier.ApplyResult result = AssemblyPresetApplier.apply(assembly);
        assertTrue(result.issues().stream().noneMatch(i -> i.severity() == com.formacraft.server.assembly.validation.AssemblyValidationIssue.Severity.ERROR));

        @SuppressWarnings("unchecked")
        Map<String, Object> expanded = (Map<String, Object>) result.applied();
        assertNotNull(expanded.get("graph"));

        @SuppressWarnings("unchecked")
        Map<String, Object> graph = (Map<String, Object>) expanded.get("graph");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> comps = (List<Map<String, Object>>) graph.get("components");
        assertEquals(1, comps.size());
        assertEquals(32, ((Number) comps.get(0).get("h")).intValue());
        assertEquals(12, ((Number) comps.get(0).get("w")).intValue());
        assertEquals(1.0, ((Number) comps.get(0).get("twistTurns")).doubleValue(), 0.001);
    }

    @Test
    void resolvesPresetByIntentKeywords() {
        var preset = AssemblyPresetRegistry.resolveForIntent("原创螺旋瞭望塔");
        assertTrue(preset.isPresent());
        assertEquals("spiral_watchtower", preset.get().id());
    }
}
