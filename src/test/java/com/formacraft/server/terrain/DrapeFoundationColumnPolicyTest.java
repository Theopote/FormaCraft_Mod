package com.formacraft.server.terrain;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DrapeFoundationColumnPolicyTest {

    @Test
    void smallFootprintKeepsEveryColumn() {
        var b = rect(0, 0, 10, 10); // 11*11 < 80*80
        assertTrue(TerrainAdaptationEngine.includeDrapeColumn(b, 5, 5));
        assertTrue(TerrainAdaptationEngine.includeDrapeColumn(b, 0, 0));
        assertTrue(TerrainAdaptationEngine.includeDrapeColumn(b, 1, 1));
    }

    @Test
    void largeFootprintSealsRimAndSparsifiesInterior() {
        // 100x100 cells >= 80*80
        var b = rect(0, 0, 99, 99);
        assertTrue(TerrainAdaptationEngine.includeDrapeColumn(b, 0, 50), "west rim");
        assertTrue(TerrainAdaptationEngine.includeDrapeColumn(b, 99, 50), "east rim");
        assertTrue(TerrainAdaptationEngine.includeDrapeColumn(b, 50, 0), "north rim");
        assertTrue(TerrainAdaptationEngine.includeDrapeColumn(b, 50, 99), "south rim");
        assertTrue(TerrainAdaptationEngine.includeDrapeColumn(b, 0, 0), "corner");

        // Interior odd/odd skipped; even/even kept
        assertFalse(TerrainAdaptationEngine.includeDrapeColumn(b, 1, 1));
        assertFalse(TerrainAdaptationEngine.includeDrapeColumn(b, 1, 2));
        assertFalse(TerrainAdaptationEngine.includeDrapeColumn(b, 2, 1));
        assertTrue(TerrainAdaptationEngine.includeDrapeColumn(b, 2, 2));
        assertTrue(TerrainAdaptationEngine.includeDrapeColumn(b, 50, 50));
    }

    @Test
    void circleRimIsContinuousUnderSparsePolicy() {
        var b = new TerrainAdaptationEngine.Bounds(
                new BlockPos(0, 0, 0), new BlockPos(99, 0, 99), true);
        int rim = 0;
        int missedOddOnRim = 0;
        for (int x = 0; x <= 99; x++) {
            for (int z = 0; z <= 99; z++) {
                if (!TerrainAdaptationEngine.isFootprintRim(b, x, z)) continue;
                rim++;
                assertTrue(TerrainAdaptationEngine.includeDrapeColumn(b, x, z),
                        "rim hole at " + x + "," + z);
                if (((x & 1) == 1) || ((z & 1) == 1)) missedOddOnRim++;
            }
        }
        assertTrue(rim > 100);
        assertTrue(missedOddOnRim > 0, "rim includes odd cells that step=2 would have skipped");
    }

    private static TerrainAdaptationEngine.Bounds rect(int x0, int z0, int x1, int z1) {
        return new TerrainAdaptationEngine.Bounds(
                new BlockPos(x0, 0, z0), new BlockPos(x1, 0, z1), false);
    }
}
