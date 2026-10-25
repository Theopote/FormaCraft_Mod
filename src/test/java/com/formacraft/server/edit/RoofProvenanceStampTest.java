package com.formacraft.server.edit;

import com.formacraft.common.build.GeometrySource;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoofProvenanceStampTest {
    @Test
    void roofPatchesWithSourceFeedPartIndex() {
        var origin = new BlockPos(10, 64, 10);
        var roof = new BlockPatch(BlockPatch.PLACE, 1, 5, 1, "minecraft:oak_stairs",
                new GeometrySource("ROOF", "main", null, "roof-1", null, "ROOF_COMPONENT"));
        var wall = new BlockPatch(BlockPatch.PLACE, 1, 1, 1, "minecraft:oak_planks",
                GeometrySource.generated("WALL"));
        var index = BuildingPartIndex.fromRoofPatches(origin, List.of(roof, wall));
        assertEquals(1, index.roof().cells().size());
        assertTrue(index.roof().cells().contains(new BlockPos(11, 69, 11)));
    }

    @Test
    void withSourcePreservesCoordinates() {
        var base = new BlockPatch(BlockPatch.REPLACE, 2, 3, 4, "minecraft:oak_planks");
        var stamped = base.withSource(GeometrySource.generated("ROOF_COMPONENT"));
        assertEquals(2, stamped.dx());
        assertEquals(3, stamped.dy());
        assertEquals(4, stamped.dz());
        assertTrue(BuildingPartIndex.isRoofProvenance(stamped.source(), null));
    }
}
