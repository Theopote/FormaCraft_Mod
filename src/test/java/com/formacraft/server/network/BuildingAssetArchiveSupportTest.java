package com.formacraft.server.network;

import com.formacraft.common.build.*;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildingAssetArchiveSupportTest {
    @Test void archivesFinalQualityCheckedCellsRelativeToTheirActualOriginIncludingAir() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
        var origin = new BlockPos(100,70,-200);
        var structure = new GeneratedStructure(UUID.randomUUID(),origin,"test",List.of(
                new PlannedBlock(origin.add(2,3,4),Blocks.STONE.getDefaultState()),new PlannedBlock(origin.add(-1,0,0),Blocks.AIR.getDefaultState())));
        var patches = BuildingAssetArchiveSupport.relativePatches(structure);
        assertEquals(2,patches.size()); assertEquals(2,patches.getFirst().dx()); assertEquals(3,patches.getFirst().dy());
        assertEquals(4,patches.getFirst().dz()); assertEquals("minecraft:air",patches.getLast().targetBlock()); assertEquals(-1,patches.getLast().dx());
    }
}
