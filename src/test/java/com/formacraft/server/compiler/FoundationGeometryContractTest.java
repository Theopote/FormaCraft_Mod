package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.FoundationGenerator;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import com.formacraft.test.PatchTestSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Foundation declared height / material must match BlockPatch output (GEOMETRY).
 */
class FoundationGeometryContractTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @AfterEach
    void clearDiagnostics() {
        AssemblyCompileDiagnostics.clear();
    }

    @Test
    void alignerStretchesStructuralFoundationAndGeneratorEmitsFullHeight() {
        var mass = new Component("MASS_MAIN", "site", new Vec3i(0, 8, 0),
                new Dimensions(10, 10, 8), List.of(),
                Map.of("component_id", "hall", "anchor_mode", "min_corner"));
        var foundation = new Component("FOUNDATION", "site", new Vec3i(0, 0, 0),
                new Dimensions(10, 10, 1), List.of(),
                Map.of("component_id", "foundation_hall", "host_id", "hall",
                        "anchor_mode", "min_corner",
                        "foundation_block", "minecraft:stone_bricks"));
        var aligned = ComponentSatelliteAligner.alignFoundationToMassForTest(foundation, mass);
        assertEquals(8, aligned.dimensions().height(),
                "aligner stretches structural foundation up to the mass floor");
        assertEquals(0, aligned.relativePosition().y());

        Slot slot = new Slot("site", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, null, null, null);
        var blocks = PatchTestSnapshot.blocks(
                new FoundationGenerator().generate(new SemanticComponent("FOUNDATION", slot, aligned)));
        for (int y = 0; y < 8; y++) {
            assertTrue(blocks.containsKey(new Vec3i(5, y, 5)),
                    "structural foundation must emit y=" + y + " (no silent 5-cap void)");
        }
        assertFalse(blocks.containsKey(new Vec3i(5, 8, 5)), "must not overshoot mass floor");
    }

    @Test
    void visiblePlinthKeepsAuthoredThinHeightAndMaterial() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "plinth");
        params.put("host_id", "hall");
        params.put("plinth", true);
        params.put("plinth_height", 2);
        params.put("anchor_mode", "min_corner");
        params.put("foundation_block", "minecraft:polished_andesite");
        var mass = new Component("MASS_MAIN", "site", new Vec3i(0, 8, 0),
                new Dimensions(10, 10, 8), List.of(),
                Map.of("component_id", "hall", "anchor_mode", "min_corner"));
        var foundation = new Component("FOUNDATION", "site", new Vec3i(0, 0, 0),
                new Dimensions(12, 12, 2), List.of("plinth"), params);
        var aligned = ComponentSatelliteAligner.alignFoundationToMassForTest(foundation, mass);
        assertEquals(2, aligned.dimensions().height());
        assertEquals(6, aligned.relativePosition().y(), "plinth top meets mass floor");
        assertEquals(new Vec3i(-1, 6, -1), aligned.relativePosition(),
                "wider plinth must overhang evenly under the mass");

        Slot slot = new Slot("site", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, null, null, null);
        var blocks = PatchTestSnapshot.blocks(
                new FoundationGenerator().generate(new SemanticComponent("FOUNDATION", slot, aligned)));
        assertTrue(blocks.containsKey(new Vec3i(1, 6, 1)));
        assertTrue(blocks.containsKey(new Vec3i(1, 7, 1)));
        assertFalse(blocks.containsKey(new Vec3i(1, 5, 1)), "thin plinth must not fill to terrain");
        assertTrue(blocks.values().stream().allMatch(b -> b != null && b.contains("polished_andesite")),
                "component foundation_block must win over style palette");
    }

    @Test
    void narrowVisiblePlinthStaysCenteredAndIsNotExpanded() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "plinth");
        params.put("host_id", "hall");
        params.put("plinth", true);
        params.put("plinth_height", 2);
        params.put("anchor_mode", "min_corner");
        params.put("foundation_block", "minecraft:polished_andesite");
        var mass = new Component("MASS_MAIN", "site", new Vec3i(0, 8, 0),
                new Dimensions(10, 10, 8), List.of(),
                Map.of("component_id", "hall", "anchor_mode", "min_corner"));
        var foundation = new Component("FOUNDATION", "site", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 2), List.of("plinth"), params);
        var aligned = ComponentSatelliteAligner.alignFoundationToMassForTest(foundation, mass);
        assertEquals(8, aligned.dimensions().width());
        assertEquals(8, aligned.dimensions().depth());
        assertEquals(new Vec3i(1, 6, 1), aligned.relativePosition(),
                "narrower plinth must inset to true center under the mass");

        var components = new java.util.ArrayList<>(List.of(mass, aligned));
        assertEquals(0, com.formacraft.common.generation.component.util.ComponentFoundationEnforcer
                        .apply(components),
                "visible plinth must not be expanded to cover the full mass");
        assertEquals(8, components.get(1).dimensions().width());
        assertEquals(8, components.get(1).dimensions().depth());
        assertEquals(new Vec3i(1, 6, 1), components.get(1).relativePosition());
    }

    @Test
    void sandFoundationMaterialIsRejected() {
        AssemblyCompileDiagnostics.clear();
        var foundation = new Component("FOUNDATION", "site", new Vec3i(0, 0, 0),
                new Dimensions(4, 4, 2), List.of(),
                Map.of("foundation_block", "minecraft:sand", "anchor_mode", "min_corner"));
        Slot slot = new Slot("site", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, null, null, null);
        var patches = new FoundationGenerator().generate(new SemanticComponent("FOUNDATION", slot, foundation));
        assertTrue(patches.isEmpty());
        assertEquals("E_FOUNDATION_MATERIAL_INVALID", AssemblyCompileDiagnostics.get().code());
    }

    @Test
    void ordinaryFoundationHonorsFoundationBlockParam() {
        Map<String, Object> params = new HashMap<>();
        params.put("foundation_block", "minecraft:polished_andesite");
        params.put("anchor_mode", "min_corner");
        var foundation = new Component("FOUNDATION", "site", new Vec3i(0, 0, 0),
                new Dimensions(6, 6, 3), List.of(), params);
        Slot slot = new Slot("site", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, null, null, null);
        List<BlockPatch> patches = new FoundationGenerator()
                .generate(new SemanticComponent("FOUNDATION", slot, foundation, "GOTHIC"));
        assertFalse(patches.isEmpty());
        assertTrue(patches.stream().allMatch(p -> p.targetBlock() != null
                        && p.targetBlock().contains("polished_andesite")),
                "AI-authored foundation_block must reach BlockPatch");
        assertEquals(3 * 6 * 6, patches.size());
    }

    @Test
    void heightAtLimitEmitsFullPrism() {
        AssemblyCompileDiagnostics.clear();
        var foundation = new Component("FOUNDATION", "site", new Vec3i(0, 0, 0),
                new Dimensions(2, 2, FoundationGenerator.MAX_FOUNDATION_HEIGHT), List.of(),
                Map.of("foundation_block", "minecraft:stone_bricks", "anchor_mode", "min_corner"));
        Slot slot = new Slot("site", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, null, null, null);
        var patches = new FoundationGenerator().generate(new SemanticComponent("FOUNDATION", slot, foundation));
        assertEquals(FoundationGenerator.MAX_FOUNDATION_HEIGHT * 4, patches.size());
        assertNull(AssemblyCompileDiagnostics.get());
    }

    @Test
    void heightAboveLimitFailsWithoutSilentTruncation() {
        AssemblyCompileDiagnostics.clear();
        var foundation = new Component("FOUNDATION", "site", new Vec3i(0, 0, 0),
                new Dimensions(2, 2, FoundationGenerator.MAX_FOUNDATION_HEIGHT + 1), List.of(),
                Map.of("foundation_block", "minecraft:stone_bricks", "anchor_mode", "min_corner"));
        Slot slot = new Slot("site", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, null, null, null);
        var patches = new FoundationGenerator().generate(new SemanticComponent("FOUNDATION", slot, foundation));
        assertTrue(patches.isEmpty());
        assertNotNull(AssemblyCompileDiagnostics.get());
        assertEquals("E_FOUNDATION_HEIGHT_LIMIT", AssemblyCompileDiagnostics.get().code());
    }

    @Test
    void airFoundationMaterialIsRejected() {
        AssemblyCompileDiagnostics.clear();
        var foundation = new Component("FOUNDATION", "site", new Vec3i(0, 0, 0),
                new Dimensions(4, 4, 2), List.of(),
                Map.of("foundation_block", "minecraft:air", "anchor_mode", "min_corner"));
        Slot slot = new Slot("site", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, null, null, null);
        var patches = new FoundationGenerator().generate(new SemanticComponent("FOUNDATION", slot, foundation));
        assertTrue(patches.isEmpty());
        assertEquals("E_FOUNDATION_MATERIAL_INVALID", AssemblyCompileDiagnostics.get().code());
    }

    @Test
    void torchFoundationMaterialIsRejected() {
        AssemblyCompileDiagnostics.clear();
        var foundation = new Component("FOUNDATION", "site", new Vec3i(0, 0, 0),
                new Dimensions(4, 4, 2), List.of(),
                Map.of("foundation_block", "minecraft:torch", "anchor_mode", "min_corner"));
        Slot slot = new Slot("site", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, null, null, null);
        var patches = new FoundationGenerator().generate(new SemanticComponent("FOUNDATION", slot, foundation));
        assertTrue(patches.isEmpty());
        assertEquals("E_FOUNDATION_MATERIAL_INVALID", AssemblyCompileDiagnostics.get().code());
    }

    @Test
    void foundationBlockStatePropertiesArePreserved() {
        AssemblyCompileDiagnostics.clear();
        var foundation = new Component("FOUNDATION", "site", new Vec3i(0, 0, 0),
                new Dimensions(2, 2, 2), List.of(),
                Map.of("foundation_block", "minecraft:oak_log[axis=x]", "anchor_mode", "min_corner"));
        Slot slot = new Slot("site", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, null, null, null);
        var patches = new FoundationGenerator().generate(new SemanticComponent("FOUNDATION", slot, foundation));
        assertFalse(patches.isEmpty());
        assertTrue(patches.stream().allMatch(p -> p.targetBlock() != null
                        && p.targetBlock().contains("oak_log")
                        && p.targetBlock().contains("axis=x")),
                "authored BlockState properties must survive generation: " + patches.getFirst().targetBlock());
    }
}
