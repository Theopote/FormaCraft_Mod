package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.impl.FoundationGenerator;
import com.formacraft.common.generation.component.util.ComponentFoundationEnforcer;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.Layout;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import com.formacraft.test.PatchTestSnapshot;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full ComponentPlanCompiler → default postprocess contracts for foundations.
 * Generator-only checks live in {@link FoundationGeometryContractTest}.
 */
class FoundationFinalPatchContractTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @AfterEach
    void clearDiagnostics() {
        AssemblyCompileDiagnostics.clear();
    }

    @Test
    void explicitStoneBricksSurviveDefaultMaterialVariation() {
        var mass = new Component("MASS_MAIN", "site", new Vec3i(0, 3, 0),
                new Dimensions(8, 8, 6), List.of(),
                Map.of("component_id", "hall", "anchor_mode", "min_corner",
                        "hollow", true, "roof_type", "none",
                        "window_style", "none", "entrance_type", "none",
                        "wall_block", "minecraft:oak_planks",
                        "floor_block", "minecraft:oak_planks"));
        var foundation = new Component("FOUNDATION", "site", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 3), List.of(),
                Map.of("component_id", "hall_foundation", "host_id", "hall",
                        "anchor_mode", "min_corner",
                        "foundation_block", "minecraft:stone_bricks"));
        var blocks = PatchTestSnapshot.blocks(compile(List.of(mass, foundation)));
        assertFalse(blocks.isEmpty(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 8; x++) {
                for (int z = 0; z < 8; z++) {
                    var pos = new Vec3i(x, y, z);
                    String block = blocks.get(pos);
                    assertNotNull(block, "expected foundation cell at " + pos);
                    String lower = block.toLowerCase(Locale.ROOT);
                    assertTrue(lower.contains("stone_bricks")
                                    && !lower.contains("mossy")
                                    && !lower.contains("cracked"),
                            "foundation cell " + pos + " must stay explicit stone_bricks, got " + block);
                }
            }
        }
    }

    @Test
    void oakLogAxisXPreservedThroughFullCompile() {
        var foundation = new Component("FOUNDATION", "site", new Vec3i(0, 0, 0),
                new Dimensions(3, 3, 2), List.of(),
                Map.of("component_id", "log_pad", "anchor_mode", "min_corner",
                        "foundation_block", "minecraft:oak_log[axis=x]"));
        var patches = compile(List.of(foundation));
        assertFalse(patches.isEmpty(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertTrue(patches.stream()
                        .filter(p -> p != null && !BlockPatch.REMOVE.equals(p.action()))
                        .allMatch(p -> p.targetBlock() != null
                                && p.targetBlock().contains("oak_log")
                                && p.targetBlock().contains("axis=x")),
                "authored BlockState properties must survive full compile+postprocess");
    }

    @Test
    void torchStructuralFoundationFailsExplicitly() {
        AssemblyCompileDiagnostics.clear();
        var foundation = new Component("FOUNDATION", "site", new Vec3i(0, 0, 0),
                new Dimensions(4, 4, 2), List.of(),
                Map.of("component_id", "torch_pad", "anchor_mode", "min_corner",
                        "foundation_block", "minecraft:torch"));
        var patches = compile(List.of(foundation));
        assertTrue(patches.isEmpty(), "torch foundation must not emit structural fill voxels");
        assertNotNull(AssemblyCompileDiagnostics.get());
        String code = AssemblyCompileDiagnostics.get().code();
        assertTrue("E_FOUNDATION_MATERIAL_INVALID".equals(code) || "E_MATERIAL_INVALID".equals(code),
                "expected explicit material capability gap, got " + code);
    }

    @Test
    void hostlessFoundationDoesNotExpandOntoDistantSoleSecondary() {
        var bridge = new Component("MASS_SECONDARY", "site", new Vec3i(40, 4, 0),
                new Dimensions(12, 3, 1), List.of(),
                Map.of("component_id", "bridge_deck", "anchor_mode", "min_corner"));
        var foundation = new Component("FOUNDATION", "site", new Vec3i(0, 0, 0),
                new Dimensions(2, 2, 1), List.of(),
                Map.of("component_id", "pad", "anchor_mode", "min_corner",
                        "foundation_block", "minecraft:cobblestone"));
        var components = new ArrayList<>(List.of(bridge, foundation));
        assertEquals(0, ComponentFoundationEnforcer.apply(components),
                "sole MASS_SECONDARY must not become a hostless expand target");
        assertEquals(2, components.get(1).dimensions().width());
        assertEquals(2, components.get(1).dimensions().depth());
        assertEquals(new Vec3i(0, 0, 0), components.get(1).relativePosition());

        var blocks = PatchTestSnapshot.blocks(compile(List.of(bridge, foundation)));
        assertFalse(blocks.isEmpty(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertTrue(blocks.keySet().stream().noneMatch(p ->
                        p.x() >= 20 && p.x() < 40 && p.y() == 0
                                && blocks.get(p) != null
                                && blocks.get(p).contains("cobble")),
                "must not span empty ground between pad and distant secondary deck");
    }

    @Test
    void hostlessFoundationDoesNotExpandOntoDistantSolePrimary() {
        var mass = new Component("MASS_MAIN", "site", new Vec3i(40, 0, 0),
                new Dimensions(10, 10, 6), List.of(),
                Map.of("component_id", "distant_hall", "anchor_mode", "min_corner"));
        var foundation = new Component("FOUNDATION", "site", new Vec3i(0, 0, 0),
                new Dimensions(2, 2, 1), List.of(),
                Map.of("component_id", "pad", "anchor_mode", "min_corner",
                        "foundation_block", "minecraft:stone_bricks"));
        var components = new ArrayList<>(List.of(mass, foundation));
        assertEquals(0, ComponentFoundationEnforcer.apply(components),
                "sole MASS_MAIN without XZ overlap must not auto-expand");
        assertEquals(2, components.get(1).dimensions().width());
        assertEquals(new Vec3i(0, 0, 0), components.get(1).relativePosition());
    }

    @Test
    void stackedMassOrderIndependenceThroughCompiler() {
        var lower = new Component("MASS_MAIN", "site", new Vec3i(0, 0, 0),
                new Dimensions(9, 9, 5), List.of(),
                Map.of("component_id", "lower", "building_id", "stack",
                        "hollow", true, "roof_type", "none",
                        "window_style", "none", "entrance_type", "none",
                        "wall_block", "minecraft:stone_bricks",
                        "floor_block", "minecraft:oak_planks",
                        "anchor_mode", "min_corner"));
        var upper = new Component("MASS_MAIN", "site", new Vec3i(1, 8, 1),
                new Dimensions(7, 7, 5), List.of(),
                Map.of("component_id", "upper", "building_id", "stack",
                        "hollow", true, "roof_type", "none",
                        "window_style", "none", "entrance_type", "none",
                        "wall_block", "minecraft:stone_bricks",
                        "floor_block", "minecraft:oak_planks",
                        "anchor_mode", "min_corner"));
        var forward = PatchTestSnapshot.blocks(compile(List.of(lower, upper)));
        var reverse = PatchTestSnapshot.blocks(compile(List.of(upper, lower)));
        assertFalse(forward.isEmpty(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertEquals(forward, reverse,
                "stacked mass snap must keep identical positions and BlockState through full compile");
    }

    @Test
    void sandFoundationFailsThroughFullCompileWithoutSilentRemap() {
        AssemblyCompileDiagnostics.clear();
        var foundation = new Component("FOUNDATION", "site", new Vec3i(0, 0, 0),
                new Dimensions(4, 4, 2), List.of(),
                Map.of("component_id", "sand_pad", "anchor_mode", "min_corner",
                        "foundation_block", "minecraft:sand"));
        var patches = compile(List.of(foundation));
        assertTrue(patches.isEmpty(), "sand foundation must not emit remapped sandstone voxels");
        assertNotNull(AssemblyCompileDiagnostics.get());
        String code = AssemblyCompileDiagnostics.get().code();
        assertTrue("E_FOUNDATION_MATERIAL_INVALID".equals(code) || "E_MATERIAL_INVALID".equals(code),
                "expected explicit material capability gap, got " + code);
        assertTrue(patches.stream().noneMatch(p -> p != null && p.targetBlock() != null
                        && p.targetBlock().contains("sandstone")),
                "GravityBlockPolicy must not silently substitute sandstone for authored sand");
    }

    @Test
    void narrowVisiblePlinthStaysCenteredThroughFullCompile() {
        var mass = new Component("MASS_MAIN", "site", new Vec3i(0, 8, 0),
                new Dimensions(10, 10, 6), List.of(),
                Map.of("component_id", "hall", "anchor_mode", "min_corner",
                        "hollow", true, "roof_type", "none",
                        "window_style", "none", "entrance_type", "none",
                        "wall_block", "minecraft:oak_planks",
                        "floor_block", "minecraft:oak_planks"));
        var plinth = new Component("FOUNDATION", "site", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 2), List.of("plinth"),
                Map.of("component_id", "plinth", "host_id", "hall",
                        "plinth", true, "plinth_height", 2,
                        "anchor_mode", "min_corner",
                        "foundation_block", "minecraft:polished_andesite"));
        var blocks = PatchTestSnapshot.blocks(compile(List.of(mass, plinth)));
        assertFalse(blocks.isEmpty(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        // Centered 8×8 under 10×10 mass at y∈[6,8): origin (1,6,1).
        assertTrue(blocks.containsKey(new Vec3i(1, 6, 1)));
        assertTrue(blocks.containsKey(new Vec3i(8, 6, 8)));
        assertTrue(blocks.keySet().stream().noneMatch(p ->
                        p.y() >= 6 && p.y() < 8
                                && blocks.get(p) != null
                                && blocks.get(p).contains("polished_andesite")
                                && (p.x() < 1 || p.x() >= 9 || p.z() < 1 || p.z() >= 9)),
                "plinth must not expand beyond authored 8×8 centered footprint");
        assertTrue(blocks.keySet().stream()
                        .filter(p -> p.y() >= 6 && p.y() < 8
                                && blocks.get(p) != null
                                && blocks.get(p).contains("polished_andesite"))
                        .allMatch(p -> p.x() >= 1 && p.x() < 9 && p.z() >= 1 && p.z() < 9),
                "all andesite plinth cells must sit in [1,9)×[1,9)");
    }

    @Test
    void height64EmitsAndHeight65FailsThroughCompiler() {
        AssemblyCompileDiagnostics.clear();
        var ok = new Component("FOUNDATION", "site", new Vec3i(0, 0, 0),
                new Dimensions(2, 2, FoundationGenerator.MAX_FOUNDATION_HEIGHT), List.of(),
                Map.of("component_id", "ok_pad", "anchor_mode", "min_corner",
                        "foundation_block", "minecraft:stone_bricks"));
        var okPatches = compile(List.of(ok));
        assertFalse(okPatches.isEmpty(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertNull(AssemblyCompileDiagnostics.get());
        long heightSpan = okPatches.stream()
                .filter(p -> p != null && !BlockPatch.REMOVE.equals(p.action()))
                .filter(p -> p.targetBlock() != null && p.targetBlock().contains("stone_bricks"))
                .mapToInt(BlockPatch::dy)
                .distinct()
                .count();
        assertEquals(FoundationGenerator.MAX_FOUNDATION_HEIGHT, heightSpan);

        AssemblyCompileDiagnostics.clear();
        var bad = new Component("FOUNDATION", "site", new Vec3i(0, 0, 0),
                new Dimensions(2, 2, FoundationGenerator.MAX_FOUNDATION_HEIGHT + 1), List.of(),
                Map.of("component_id", "bad_pad", "anchor_mode", "min_corner",
                        "foundation_block", "minecraft:stone_bricks"));
        var badPatches = compile(List.of(bad));
        assertTrue(badPatches.isEmpty() || AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_FOUNDATION_HEIGHT_LIMIT", AssemblyCompileDiagnostics.get().code());
    }

    private static List<BlockPatch> compile(List<Component> components) {
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .mode(LlmPlan.Mode.build).styleProfile("DEFAULT").anchor(new Vec3i(0, 0, 0))
                .layout(new Layout(null, false, List.of(
                        new Slot("site", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, null, null, null))))
                .components(components).build();
        return ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
    }
}
