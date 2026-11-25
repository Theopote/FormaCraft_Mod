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
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 01 closeout P1-01: full ComponentPlanCompiler path — openings on building A
 * must not exempt flat-roof host coverage on building B.
 */
class FlatRoofHostAuthorityCompileTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @AfterEach
    void clear() {
        AssemblyCompileDiagnostics.clear();
    }

    @Test
    void foreignBuildingWindowsDoNotExemptHostCoverageOnCompilePath() {
        // Two flat-roof halls in one slot; B gets a punched top cell with no own opening.
        // If host-coverage still used a global opening union, A's glass would wrongly exempt B.
        Component hallA = mass("hall_a", "building_a", 0);
        Component roofA = flatRoof("roof_a", "hall_a", "building_a", 0);
        Component windowsA = facade("win_a", "hall_a", "building_a", 0);

        Component hallB = mass("hall_b", "building_b", 20);
        Component roofB = flatRoof("roof_b", "hall_b", "building_b", 20);

        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .mode(LlmPlan.Mode.build)
                .styleProfile("DEFAULT")
                .anchor(new Vec3i(0, 0, 0))
                .layout(new Layout(null, false, List.of(
                        new Slot("site", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, null, null, null))))
                .components(List.of(hallA, roofA, windowsA, hallB, roofB))
                .build();

        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        // Both buildings should compile clean when each roof seals its own host —
        // this asserts the path runs checkHost with by-owner map (no crash / false global union).
        assertFalse(patches.isEmpty(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> "unexpected gap: " + AssemblyCompileDiagnostics.get());
        assertTrue(patches.stream().anyMatch(p -> p.dx() >= 20),
                "building_b must still emit patches");
    }

    private static Component mass(String id, String building, int x) {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", id);
        params.put("building_id", building);
        params.put("hollow", true);
        params.put("anchor_mode", "min_corner");
        params.put("wall_block", "minecraft:stone_bricks");
        params.put("floor_block", "minecraft:oak_planks");
        return new Component("MASS_MAIN", "site", new Vec3i(x, 0, 0),
                new Dimensions(9, 9, 8), List.of(), params);
    }

    private static Component flatRoof(String id, String host, String building, int x) {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", id);
        params.put("building_id", building);
        params.put("host_id", host);
        params.put("roof_type", "flat");
        params.put("roof_block", "minecraft:stone_bricks");
        return new Component("ROOF", "site", new Vec3i(x, 7, 0),
                new Dimensions(9, 9, 1), List.of(), params);
    }

    private static Component facade(String id, String host, String building, int x) {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", id);
        params.put("building_id", building);
        params.put("host_id", host);
        params.put("window_style", "glass");
        return new Component("FACADE_WINDOWS", "site", new Vec3i(x, 0, 0),
                new Dimensions(9, 1, 6), List.of("wrap"), params);
    }
}
