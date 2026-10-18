package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.GeneratedSurfaceCapture;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SurfaceOwnershipLedgerTest {
    @Test
    void decorCannotReplaceWindowSurface() {
        var ledger = new SurfaceOwnershipLedger();
        var mass = component("MASS_MAIN", "mass1", Map.of("component_id", "mass1", "host_id", "b1"));
        ledger.accept(mass,
                List.of(new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:glass")),
                List.of(new GeneratedSurfaceCapture.Cell(new BlockPos(0, 1, 0), GeneratedSurfaceCapture.Role.WINDOW)),
                BlockPos.ORIGIN);

        var decor = component("DECOR_DETAIL", "decor1", Map.of("component_id", "decor1", "host_id", "b1"));
        var conflict = ledger.check(decor, List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:stone_bricks")));
        assertTrue(conflict.isPresent());
        assertTrue(conflict.get().reason().contains("WINDOW"));
    }

    @Test
    void facadeWindowsCanInstallGlassOnWall() {
        var ledger = new SurfaceOwnershipLedger();
        var mass = component("MASS_MAIN", "mass1", Map.of("component_id", "mass1", "host_id", "b1"));
        ledger.accept(mass,
                List.of(new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:stone")),
                List.of(new GeneratedSurfaceCapture.Cell(new BlockPos(0, 1, 0), GeneratedSurfaceCapture.Role.WALL)),
                BlockPos.ORIGIN);

        var windows = component("FACADE_WINDOWS", "win1", Map.of("component_id", "win1", "host_id", "b1"));
        var conflict = ledger.check(windows, List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:glass")));
        assertTrue(conflict.isEmpty());
    }

    @Test
    void sameHostFlatRoofEdgeWindowCarveIsAllowed() {
        var ledger = acceptFlatRoof5x5();
        var windows = component("FACADE_WINDOWS", "win1", Map.of(
                "component_id", "win1", "host_id", "b1", "building_id", "b1"));
        BlockPos edge = new BlockPos(0, 6, 2);
        assertFalse(ledger.isProtectedFromWindows(windows, edge));
        assertTrue(ledger.check(windows, List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 6, 2, "minecraft:glass"))).isEmpty());
    }

    @Test
    void sameHostWindowCannotCarveFlatRoofCenterPlate() {
        var ledger = acceptFlatRoof5x5();
        var windows = component("FACADE_WINDOWS", "win1", Map.of(
                "component_id", "win1", "host_id", "b1", "building_id", "b1"));
        BlockPos center = new BlockPos(2, 6, 2);
        assertTrue(ledger.isProtectedFromWindows(windows, center));
        assertTrue(ledger.check(windows, List.of(
                new BlockPatch(BlockPatch.PLACE, 2, 6, 2, "minecraft:glass"))).isPresent());
        assertTrue(ledger.check(windows, List.of(
                new BlockPatch(BlockPatch.REMOVE, 2, 6, 2, "minecraft:air"))).isPresent());
    }

    @Test
    void foreignHostWindowCannotCarveAnotherFlatRoof() {
        var ledger = acceptFlatRoof5x5();
        var foreign = component("FACADE_WINDOWS", "win2", Map.of(
                "component_id", "win2", "host_id", "other", "building_id", "other"));
        BlockPos edge = new BlockPos(0, 6, 2);
        assertTrue(ledger.isProtectedFromWindows(foreign, edge));
        assertTrue(ledger.check(foreign, List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 6, 2, "minecraft:glass"))).isPresent());
    }

    @Test
    void decorCannotBypassRoofOwnershipViaRemoveThenPlace() {
        var ledger = acceptFlatRoof5x5();
        var decor = component("DECOR_DETAIL", "decor1", Map.of(
                "component_id", "decor1", "host_id", "b1", "building_id", "b1"));
        BlockPos edge = new BlockPos(0, 6, 2);
        assertTrue(ledger.isProtectedFromDecoration(edge));
        assertTrue(ledger.check(decor, List.of(
                new BlockPatch(BlockPatch.REMOVE, 0, 6, 2, "minecraft:air"))).isPresent());
        assertTrue(ledger.check(decor, List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 6, 2, "minecraft:oak_planks"))).isPresent());
    }

    @Test
    void pitchedRoofKeepsExistingProtection() {
        var pitched = new SurfaceOwnershipLedger();
        var gable = roof("gable1", "gable", new Dimensions(5, 5, 1));
        pitched.accept(gable,
                List.of(new BlockPatch(BlockPatch.PLACE, 1, 6, 0, "minecraft:oak_stairs")),
                List.of(new GeneratedSurfaceCapture.Cell(new BlockPos(1, 6, 0), GeneratedSurfaceCapture.Role.ROOF)),
                BlockPos.ORIGIN);
        var windows = component("FACADE_WINDOWS", "win1", Map.of(
                "component_id", "win1", "host_id", "b1", "building_id", "b1"));
        assertTrue(pitched.isProtectedFromWindows(windows, new BlockPos(1, 6, 0)));
        assertTrue(pitched.check(windows, List.of(
                new BlockPatch(BlockPatch.PLACE, 1, 6, 0, "minecraft:glass"))).isPresent());
    }

    @Test
    void decorCannotEraseOccupiedSurface() {
        var ledger = new SurfaceOwnershipLedger();
        var mass = component("MASS_MAIN", "mass1", Map.of("component_id", "mass1"));
        ledger.accept(mass,
                List.of(new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:stone")),
                List.of(new GeneratedSurfaceCapture.Cell(new BlockPos(0, 1, 0), GeneratedSurfaceCapture.Role.WALL)),
                BlockPos.ORIGIN);

        var decor = component("DECOR_DETAIL", "decor1", Map.of("component_id", "decor1"));
        var conflict = ledger.check(decor, List.of(
                new BlockPatch(BlockPatch.REMOVE, 0, 1, 0, "minecraft:air")));
        assertTrue(conflict.isPresent());
    }

    private static SurfaceOwnershipLedger acceptFlatRoof5x5() {
        var ledger = new SurfaceOwnershipLedger();
        var roof = roof("roof1", "flat", new Dimensions(5, 5, 1));
        List<BlockPatch> patches = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 6, 2, "minecraft:smooth_stone"),
                new BlockPatch(BlockPatch.PLACE, 2, 6, 2, "minecraft:smooth_stone"));
        List<GeneratedSurfaceCapture.Cell> cells = List.of(
                new GeneratedSurfaceCapture.Cell(new BlockPos(0, 6, 2), GeneratedSurfaceCapture.Role.ROOF),
                new GeneratedSurfaceCapture.Cell(new BlockPos(2, 6, 2), GeneratedSurfaceCapture.Role.ROOF));
        ledger.accept(roof, patches, cells, BlockPos.ORIGIN);
        return ledger;
    }

    private static Component roof(String id, String roofType, Dimensions dims) {
        return new Component(
                "ROOF",
                id,
                new Vec3i(0, 6, 0),
                dims,
                null,
                Map.of("component_id", id, "host_id", "b1", "building_id", "b1", "roof_type", roofType));
    }

    private static Component component(String type, String slot, Map<String, Object> params) {
        return new Component(type, slot, new Vec3i(0, 0, 0), null, null, params);
    }
}
