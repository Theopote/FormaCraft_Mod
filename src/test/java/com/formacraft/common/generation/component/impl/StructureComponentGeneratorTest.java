package com.formacraft.common.generation.component.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.ComponentGeneratorRegistry;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureComponentGeneratorTest {

    @Test
    void registryOwnsStructureType() {
        assertTrue(ComponentGeneratorRegistry.hasGenerator("STRUCTURE"));
    }

    @Test
    void radialColonnadeEmitsColumns() {
        Component c = new Component(
                "STRUCTURE", "apse", new Vec3i(0, 0, 0),
                new Dimensions(16, 16, 10),
                List.of("typology:radial_colonnade", "columns:8"),
                new HashMap<>(Map.of(
                        "component_id", "apse_colonnade",
                        "typology_id", "radial_colonnade",
                        "count", 8,
                        "radius", 7,
                        "column_block", "minecraft:light_gray_concrete")));
        List<BlockPatch> patches = new StructureComponentGenerator()
                .generate(new SemanticComponent("STRUCTURE", null, c));
        assertFalse(patches.isEmpty(), "parametric colonnade must emit patches");
        assertTrue(patches.stream().anyMatch(p ->
                "minecraft:light_gray_concrete".equals(p.targetBlock())));
    }

    @Test
    void gearStructureTypeEmitsRing() {
        Component c = new Component(
                "STRUCTURE", "mill", new Vec3i(0, 0, 0),
                new Dimensions(12, 12, 4),
                List.of(),
                Map.of(
                        "component_id", "windmill_gear",
                        "structure_type", "industrial_gear",
                        "material", "minecraft:iron_block"));
        List<BlockPatch> patches = new StructureComponentGenerator()
                .generate(new SemanticComponent("STRUCTURE", null, c));
        assertFalse(patches.isEmpty());
    }

    @Test
    void nativeTrussTypologyDefersToInterpreter() {
        Component c = new Component(
                "STRUCTURE", "tower", new Vec3i(0, 0, 0),
                new Dimensions(18, 18, 45),
                List.of("typology:truss_tower"),
                Map.of(
                        "component_id", "truss_tower",
                        "typology_id", "truss_tower"));
        List<BlockPatch> patches = new StructureComponentGenerator()
                .generate(new SemanticComponent("STRUCTURE", null, c));
        assertTrue(patches.isEmpty(), "native truss_tower must defer to typology interpreter");
    }
}
