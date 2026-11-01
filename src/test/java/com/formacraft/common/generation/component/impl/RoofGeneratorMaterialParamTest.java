package com.formacraft.common.generation.component.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.semantic.SemanticPart;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RoofGeneratorMaterialParamTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void materialStairsPreferredForPitchedRoofSlope() {
        assertEquals("minecraft:dark_oak_stairs",
                RoofGenerator.resolveRoofMaterial(
                        Map.of("material", "dark_oak_stairs"), SemanticPart.ROOF));
        assertEquals("minecraft:dark_oak_slab",
                RoofGenerator.resolveRoofMaterial(
                        Map.of("material", "dark_oak_stairs"), SemanticPart.ROOF_SURFACE));
        assertEquals("minecraft:spruce_stairs",
                RoofGenerator.resolveRoofMaterial(
                        Map.of("roof_material", "spruce_planks"), SemanticPart.ROOF));
    }

    @Test
    void hipRoofEmitsStairsWhenMaterialIsStairsFamily() {
        Component roof = new Component(
                "ROOF",
                "hall",
                new Vec3i(0, 5, 0),
                new Dimensions(11, 11, 4),
                List.of("flying_eaves"),
                Map.of(
                        "roof_type", "hip",
                        "material", "dark_oak_stairs",
                        "host_id", "hall",
                        "overhang", 1
                )
        );
        List<BlockPatch> patches = new RoofGenerator().generate(new SemanticComponent("ROOF", null, roof));
        assertFalse(patches.isEmpty());
        long stairs = patches.stream()
                .filter(p -> p.targetBlock() != null && p.targetBlock().contains("stairs"))
                .count();
        assertTrue(stairs > 0, "expected stair-shaped roof cells, patches=" + patches.size());
    }

    @Test
    void asianHipDefaultsOverhangWithoutExplicitParam() {
        Component roof = new Component(
                "ROOF",
                "hall",
                new Vec3i(0, 5, 0),
                new Dimensions(9, 9, 3),
                List.of(),
                Map.of("roof_type", "hip", "material", "dark_oak_planks")
        );
        // style profile ASIAN via SemanticComponent
        List<BlockPatch> patches = new RoofGenerator().generate(
                new SemanticComponent("ROOF", null, roof, "ASIAN"));
        assertFalse(patches.isEmpty());
        int minX = patches.stream().mapToInt(BlockPatch::dx).min().orElse(0);
        int maxX = patches.stream().mapToInt(BlockPatch::dx).max().orElse(0);
        // core width 9 at x=0..8; with overhang 1 → expands to -1..9 (span 11)
        assertTrue(maxX - minX + 1 >= 11,
                "ASIAN hip should expand by overhang, span=" + (maxX - minX + 1));
    }
}
