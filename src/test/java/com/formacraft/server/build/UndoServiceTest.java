package com.formacraft.server.build;

import com.formacraft.test.FakeBlockMutationAccess;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UndoServiceTest {
    @BeforeAll static void initialize() { MinecraftRegistryTestBootstrap.initialize(); }
    @Test void repeatedPositionRestoresEarliestStateOnce() {
        var service = new UndoService(); Object world = new Object(); var id = UUID.randomUUID();
        var access = new FakeBlockMutationAccess(); var pos = new BlockPos(0, 1, 0);
        var air = Blocks.AIR.getDefaultState(); var stone = Blocks.STONE.getDefaultState(); var oak = Blocks.OAK_PLANKS.getDefaultState();
        service.record(id, world, BlockPos.ORIGIN, List.of(new BlockChange(pos, air, stone), new BlockChange(pos, stone, oak)));
        access.states.put(pos, oak); var result = service.undo(id, world, access);
        assertTrue(result.complete()); assertEquals(1, result.changed()); assertEquals(1, access.writes);
        assertTrue(access.states.get(pos).isAir());
    }
    @Test void wrongWorldAndRejectedWritePreserveBuildUndoEntry() {
        var service = new UndoService(); Object world = new Object(); var id = UUID.randomUUID();
        var access = new FakeBlockMutationAccess(); var pos = new BlockPos(0, 1, 0);
        service.record(id, world, BlockPos.ORIGIN,
                List.of(new BlockChange(pos, Blocks.AIR.getDefaultState(), Blocks.STONE.getDefaultState())));
        access.states.put(pos, Blocks.STONE.getDefaultState());
        assertTrue(service.undo(id, new Object(), access).wrongWorld()); assertEquals(0, access.writes);
        access.rejected.add(pos); assertFalse(service.undo(id, world, access).complete());
        access.rejected.clear(); assertTrue(service.undo(id, world, access).complete());
        assertFalse(service.undo(id, world, access).available());
    }
}
