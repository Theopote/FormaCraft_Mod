package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CirculationAnalysisContextTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @AfterEach
    void tearDown() {
        CirculationAnalysisContext.end();
    }

    @Test
    void scanStoreyCachesSameInstanceUntilEnd() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0), new Dimensions(8, 8, 6), List.of(),
                Map.of("component_id", "hall", "wall_thickness", 1, "floor_count", 1, "floor_height", 4,
                        "anchor_mode", "min_corner"));
        List<BlockPatch> patches = List.of(
                new BlockPatch(BlockPatch.PLACE, 1, 0, 4, "minecraft:oak_planks"),
                new BlockPatch(BlockPatch.PLACE, 2, 0, 4, "minecraft:oak_planks"));
        CirculationAnalysisContext.begin(patches);
        assertNotNull(CirculationAnalysisContext.occupancy());
        Vec3i zero = new Vec3i(0, 0, 0);
        var first = CirculationAnalysisContext.scanStorey(
                CirculationAnalysisContext.occupancy(), hall, 0, zero);
        var second = CirculationAnalysisContext.scanStorey(
                CirculationAnalysisContext.occupancy(), hall, 0, zero);
        assertSame(first, second);
        assertEquals(1, CirculationAnalysisContext.cachedScanCount());
        CirculationAnalysisContext.end();
        assertFalse(CirculationAnalysisContext.isActive());
        assertNull(CirculationAnalysisContext.occupancy());
        assertEquals(0, CirculationAnalysisContext.cachedScanCount());
    }

    @Test
    void missingComponentIdDoesNotCrossHitAcrossInstances() {
        // No component_id — old string key would both become "unknown|0|0,0,0"
        Component a = new Component(
                "MASS_MAIN", "a", new Vec3i(0, 0, 0), new Dimensions(8, 8, 6), List.of(),
                Map.of("wall_thickness", 1, "floor_count", 1, "floor_height", 4, "anchor_mode", "min_corner"));
        Component b = new Component(
                "MASS_MAIN", "b", new Vec3i(30, 0, 0), new Dimensions(8, 8, 6), List.of(),
                Map.of("wall_thickness", 1, "floor_count", 1, "floor_height", 4, "anchor_mode", "min_corner"));
        List<BlockPatch> patches = new ArrayList<>();
        patches.add(new BlockPatch(BlockPatch.PLACE, 1, 0, 4, "minecraft:oak_planks"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 31, 0, 4, "minecraft:oak_planks"));
        CirculationAnalysisContext.begin(patches);
        Vec3i zero = new Vec3i(0, 0, 0);
        var scanA = CirculationAnalysisContext.scanStorey(
                CirculationAnalysisContext.occupancy(), a, 0, zero);
        var scanB = CirculationAnalysisContext.scanStorey(
                CirculationAnalysisContext.occupancy(), b, 0, zero);
        assertNotSame(scanA, scanB);
        assertEquals(2, CirculationAnalysisContext.cachedScanCount());
    }

    @Test
    void activeContextIgnoresCallerOccupancy() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0), new Dimensions(8, 8, 6), List.of(),
                Map.of("component_id", "hall", "wall_thickness", 1, "floor_count", 1, "floor_height", 4,
                        "anchor_mode", "min_corner"));
        List<BlockPatch> patches = List.of(
                new BlockPatch(BlockPatch.PLACE, 1, 0, 4, "minecraft:oak_planks"),
                new BlockPatch(BlockPatch.PLACE, 2, 0, 4, "minecraft:oak_planks"));
        CirculationAnalysisContext.begin(patches);
        Vec3i zero = new Vec3i(0, 0, 0);
        var fromContext = CirculationAnalysisContext.scanStorey(
                CirculationAnalysisContext.occupancy(), hall, 0, zero);
        // Empty occupancy would yield no standable reps if used — must still hit context snapshot
        var emptyOcc = BridgeWalkability.occupancyFromPatches(List.of());
        var withEmptyArg = CirculationAnalysisContext.scanStorey(emptyOcc, hall, 0, zero);
        assertSame(fromContext, withEmptyArg);
        assertFalse(fromContext.representatives().isEmpty(),
                "context occupancy must drive the scan, not the empty caller snapshot");
    }

    @Test
    void endClearsCacheBeforeNextBegin() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0), new Dimensions(8, 8, 6), List.of(),
                Map.of("component_id", "hall", "wall_thickness", 1, "floor_count", 1, "floor_height", 4,
                        "anchor_mode", "min_corner"));
        List<BlockPatch> patches = List.of(
                new BlockPatch(BlockPatch.PLACE, 1, 0, 4, "minecraft:oak_planks"));
        CirculationAnalysisContext.begin(patches);
        CirculationAnalysisContext.scanStorey(
                CirculationAnalysisContext.occupancy(), hall, 0, new Vec3i(0, 0, 0));
        assertEquals(1, CirculationAnalysisContext.cachedScanCount());
        CirculationAnalysisContext.end();
        assertEquals(0, CirculationAnalysisContext.cachedScanCount());
        assertNull(CirculationAnalysisContext.occupancy());
        CirculationAnalysisContext.begin(patches);
        assertEquals(0, CirculationAnalysisContext.cachedScanCount());
        assertNotNull(CirculationAnalysisContext.occupancy());
    }

    @Test
    void inactiveScanStoreyDoesNotCache() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0), new Dimensions(8, 8, 6), List.of(),
                Map.of("component_id", "hall", "wall_thickness", 1, "floor_count", 1, "floor_height", 4,
                        "anchor_mode", "min_corner"));
        var occ = BridgeWalkability.occupancyFromPatches(List.of(
                new BlockPatch(BlockPatch.PLACE, 1, 0, 4, "minecraft:oak_planks")));
        var a = CirculationAnalysisContext.scanStorey(occ, hall, 0, new Vec3i(0, 0, 0));
        var b = CirculationAnalysisContext.scanStorey(occ, hall, 0, new Vec3i(0, 0, 0));
        assertNotNull(a);
        assertNotNull(b);
        assertEquals(0, CirculationAnalysisContext.cachedScanCount());
    }

    @Test
    void beginThrowsLeavesContextInactive() {
        Iterable<BlockPatch> throwing = () -> new Iterator<>() {
            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public BlockPatch next() {
                throw new IllegalStateException("boom");
            }
        };
        assertThrows(IllegalStateException.class, () -> CirculationAnalysisContext.begin(throwing));
        assertFalse(CirculationAnalysisContext.isActive());
        assertNull(CirculationAnalysisContext.occupancy());
        assertEquals(0, CirculationAnalysisContext.cachedScanCount());
    }
}
