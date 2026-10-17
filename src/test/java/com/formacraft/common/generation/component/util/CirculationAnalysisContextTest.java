package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class CirculationAnalysisContextTest {

    @AfterEach
    void tearDown() {
        CirculationAnalysisContext.end();
    }

    @Test
    void scanStoreyCachesSameKeyUntilEnd() {
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
}
