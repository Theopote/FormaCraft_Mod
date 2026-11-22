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
        var patches = compile(List.of(mass, foundation));
        assertFalse(patches.isEmpty(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        var foundationCells = patches.stream()
                .filter(p -> p != null && !BlockPatch.REMOVE.equals(p.action()))
                .filter(p -> p.dy() >= 0 && p.dy() < 3)
                .filter(p -> p.dx() >= 0 && p.dx() < 8 && p.dz() >= 0 && p.dz() < 8)
                .filter(p -> p.targetBlock() != null
                        && p.targetBlock().toLowerCase(Locale.ROOT).contains("stone_brick"))
                .toList();
        assertFalse(foundationCells.isEmpty(), "expected stone_brick foundation voxels after full compile");
        assertTrue(foundationCells.stream().allMatch(p -> {
            String t = p.targetBlock().toLowerCase(Locale.ROOT);
            return t.contains("stone_bricks")
                    && !t.contains("mossy")
                    && !t.contains("cracked");
        }), "explicit foundation_block must be protected from MaterialVariationPostProcessor");
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
        assertEquals(forward.keySet(), reverse.keySet(),
                "stacked mass snap must be order-independent through full compile");
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
