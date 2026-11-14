package com.formacraft.common.llm.compiler;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlanCompileContextTest {
    @Test
    void terrainContextSnapshotsOriginAndKeepsCompileDefaults() {
        var origin = new BlockPos.Mutable(100, 64, -200);
        var context = PlanCompileContext.createWithTerrain(origin);
        origin.set(0, 0, 0);
        assertEquals(new BlockPos(100, 64, -200), context.terrain.origin);
        assertEquals(PlanCompileContext.HeightStrategy.ADAPTIVE, context.heightStrategy);
        assertEquals(PlanCompileContext.RoofStrategy.UNIFIED, context.roofStrategy);
        assertEquals(5.0, context.defaultWallHeight);
        assertEquals(1.0, context.defaultFloorThickness);
        assertSame(context.terrain, context.withOutline(null).terrain);
    }

    @Test
    void defaultContextStillHasNoTerrainAndFlatHeight() {
        var context = PlanCompileContext.createDefault();
        assertNull(context.terrain);
        assertEquals(PlanCompileContext.HeightStrategy.FLAT, context.heightStrategy);
    }
}
