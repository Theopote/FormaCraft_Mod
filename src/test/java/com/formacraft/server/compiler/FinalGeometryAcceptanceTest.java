package com.formacraft.server.compiler;

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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Final geometry acceptance: user/plan constraints must survive into measured BlockPatch
 * voxels — not only JSON-level formulas.
 */
class FinalGeometryAcceptanceTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @AfterEach
    void clearDiagnostics() {
        AssemblyCompileDiagnostics.clear();
    }

    @Test
    void closedTopMassClearHeightMatchesFloorPlusCeilingBand() {
        assertEquals(2, measuredInteriorClear(4),
                "height 4 closed shell → floor + ceiling leave 2 clear cells; column="
                        + columnDump(4));
        assertEquals(3, measuredInteriorClear(5),
                "height 5 closed shell → 3 clear cells for clear-height 3; column="
                        + columnDump(5));
    }

    @Test
    void compiledClosedShellPreservesClearHeightThreeWhenBodyIsFive() {
        var mass = closedMass("house", 0, 0, 5);
        var blocks = PatchTestSnapshot.blocks(compile(List.of(mass)));
        assertFalse(blocks.isEmpty(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertEquals(3, clearAt(blocks, 4, 4, 5));
        assertTrue(blocks.containsKey(new Vec3i(4, 0, 4)), "floor present");
        assertTrue(blocks.containsKey(new Vec3i(4, 4, 4)), "ceiling band occupies y=height-1");
        assertFalse(blocks.containsKey(new Vec3i(4, 1, 4)));
        assertFalse(blocks.containsKey(new Vec3i(4, 2, 4)));
        assertFalse(blocks.containsKey(new Vec3i(4, 3, 4)));
    }

    @Test
    void roofTypeNoneDoesNotEmitRoofAboveMassEnvelope() {
        var mass = closedMass("bare", 0, 0, 6);
        var blocks = PatchTestSnapshot.blocks(compile(List.of(mass)));
        assertFalse(blocks.isEmpty(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        int maxY = blocks.keySet().stream().mapToInt(Vec3i::y).max().orElse(-1);
        assertTrue(maxY < 6, "roof_type=none must stay inside the mass envelope, maxY=" + maxY);
        assertTrue(blocks.values().stream().noneMatch(b -> b != null && b.contains("stairs")),
                "no roof stair blocks when roof is opted out");
    }

    @Test
    void twoFloatingHousesLandAndKeepIndependentFootprints() {
        var left = floatingHouse("house_left", 0, 8);
        var right = floatingHouse("house_right", 20, 8);
        var blocks = PatchTestSnapshot.blocks(compile(List.of(left, right)));
        assertFalse(blocks.isEmpty(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        // After FloatingMassSnapRepair both bodies sit on y=0 — floors must exist there.
        assertTrue(blocks.containsKey(new Vec3i(4, 0, 4)), "left house floor after snap");
        assertTrue(blocks.containsKey(new Vec3i(24, 0, 4)), "right house floor after snap");
        assertTrue(blocks.keySet().stream().noneMatch(p -> p.y() >= 8 && p.x() >= 0 && p.x() < 10),
                "left house must not remain floating at authored y=8");
        assertTrue(blocks.keySet().stream().noneMatch(p -> p.y() >= 8 && p.x() >= 20 && p.x() < 30),
                "right house must not remain floating at authored y=8");
        assertEquals(3, clearAt(blocks, 4, 4, 5));
        assertEquals(3, clearAt(blocks, 24, 4, 5));
    }

    @Test
    void floatingTowerWithOwnRoofSnapsAndRoofFollowsInCompiledPatch() {
        var tower = new Component("TOWER", "keep", new Vec3i(0, 10, 0),
                new Dimensions(7, 7, 10), List.of(),
                Map.of("component_id", "curtain_tower", "wall_block", "minecraft:stone_bricks",
                        "floor_block", "minecraft:oak_planks", "hollow", true,
                        "roof_type", "none", "window_style", "none", "entrance_type", "none"));
        var roof = new Component("ROOF", "keep", new Vec3i(0, 20, 0),
                new Dimensions(9, 9, 3), List.of(),
                Map.of("component_id", "tower_roof", "host_id", "curtain_tower",
                        "roof_type", "gable", "roof_block", "minecraft:dark_oak_stairs"));
        var blocks = PatchTestSnapshot.blocks(compile(List.of(tower, roof)));
        assertFalse(blocks.isEmpty(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertTrue(blocks.containsKey(new Vec3i(3, 0, 3))
                        || blocks.keySet().stream().anyMatch(p -> p.y() == 0 && p.x() >= 0 && p.x() < 7),
                "tower must snap onto y=0 support, not hang under its own roof");
        assertTrue(blocks.keySet().stream().noneMatch(p -> p.y() >= 20),
                "hosted roof must follow the snapped tower (authored roof y=20 gone)");
        assertTrue(blocks.keySet().stream().anyMatch(p -> p.y() >= 10 && p.y() < 14),
                "roof should sit above the snapped tower body");
    }

    @Test
    void ambiguousHostlessFoundationKeepsAuthoredPlacementThroughAligner() {
        var left = new Component("MASS_MAIN", "site", new Vec3i(0, 0, 0),
                new Dimensions(11, 11, 6), List.of(),
                Map.of("component_id", "house_left", "building_id", "house_left"));
        var right = new Component("MASS_MAIN", "site", new Vec3i(30, 0, 0),
                new Dimensions(11, 11, 6), List.of(),
                Map.of("component_id", "house_right", "building_id", "house_right"));
        var foundationPos = new Vec3i(20, -1, 5);
        var foundation = new Component("FOUNDATION", "site", foundationPos,
                new Dimensions(1, 1, 1), List.of(),
                Map.of("component_id", "mystery_foundation",
                        "foundation_block", "minecraft:cobblestone"));
        var components = new ArrayList<>(List.of(left, right, foundation));
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .mode(LlmPlan.Mode.build).styleProfile("DEFAULT").anchor(new Vec3i(0, 0, 0))
                .layout(new Layout(null, false, List.of(
                        new Slot("site", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, null, null, null))))
                .components(List.copyOf(components)).build();
        ComponentSatelliteAligner.realignSatellitesToMass(components, plan, Map.of(), java.util.Set.of());
        var fixed = components.stream()
                .filter(c -> "mystery_foundation".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals(foundationPos, fixed.relativePosition());
        assertNull(fixed.params().get("host_id"));

        // Hostless multi-mass foundations must keep authored footprint — enforcer must not
        // expand them onto the slot-wide mass union under house_left.
        var componentsForEnforce = new ArrayList<>(List.of(left, right, foundation));
        assertEquals(0, com.formacraft.common.generation.component.util.ComponentFoundationEnforcer
                        .apply(componentsForEnforce),
                "ambiguous hostless foundation must not auto-expand onto multiple masses");
        var kept = componentsForEnforce.stream()
                .filter(c -> "mystery_foundation".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals(foundationPos, kept.relativePosition());
        assertEquals(1, kept.dimensions().width());
        assertEquals(1, kept.dimensions().depth());

        var blocks = PatchTestSnapshot.blocks(compile(List.of(left, right, foundation)));
        assertFalse(blocks.isEmpty(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertTrue(blocks.containsKey(foundationPos)
                        || blocks.keySet().stream().anyMatch(p -> p.x() == 20 && p.z() == 5),
                "authored midpoint must remain covered after compile");
        assertTrue(blocks.keySet().stream().noneMatch(p -> p.x() == 5 && p.y() == -1 && p.z() == 5
                        && blocks.get(p) != null && blocks.get(p).contains("cobble")),
                "must not spill foundation material under house_left center");
    }

    private static int measuredInteriorClear(int height) {
        // Measure through the compiler path so defaults match production landing.
        var blocks = PatchTestSnapshot.blocks(compile(List.of(closedMass("probe_" + height, 0, 0, height))));
        return clearAt(blocks, 4, 4, height);
    }

    private static String columnDump(int height) {
        var blocks = PatchTestSnapshot.blocks(compile(List.of(closedMass("probe_" + height, 0, 0, height))));
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < height; y++) {
            if (y > 0) sb.append(',');
            sb.append(y).append('=').append(blocks.get(new Vec3i(4, y, 4)));
        }
        return sb.toString();
    }

    private static int clearAt(Map<Vec3i, String> blocks, int x, int z, int height) {
        assertTrue(blocks.containsKey(new Vec3i(x, 0, z)), "missing floor at " + x + ",0," + z);
        int clear = 0;
        for (int y = 1; y < height; y++) {
            if (blocks.containsKey(new Vec3i(x, y, z))) break;
            clear++;
        }
        return clear;
    }

    private static Component closedMass(String id, int x, int y, int height) {
        return new Component("MASS_MAIN", "site", new Vec3i(x, y, 0),
                new Dimensions(9, 9, height), List.of(),
                Map.of("component_id", id, "building_id", id,
                        "hollow", true, "roof_type", "none",
                        "window_style", "none", "entrance_type", "none",
                        "wall_block", "minecraft:stone_bricks",
                        "floor_block", "minecraft:oak_planks",
                        "anchor_mode", "min_corner", "plan_type", "rectangle"));
    }

    private static Component floatingHouse(String id, int x, int y) {
        return closedMass(id, x, y, 5);
    }

    private static List<BlockPatch> compile(List<Component> components) {
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .mode(LlmPlan.Mode.build).styleProfile("DEFAULT").anchor(new Vec3i(0, 0, 0))
                .layout(new Layout(null, false, List.of(
                        new Slot("site", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, null, null, null),
                        new Slot("keep", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, null, null, null),
                        new Slot("probe", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, null, null, null))))
                .components(components).build();
        return ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
    }
}
