package com.formacraft.server.build;

import com.formacraft.common.world.BlockSnapshot;
import com.formacraft.test.FakeBlockMutationAccess;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UndoServiceRedoTest {
    @BeforeAll
    static void init() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @AfterEach
    void clear() {
        PlayerMutationLedger.get().clearAll();
    }

    @Test
    void buildUndoThenRedoRestoresBlocks() {
        UUID player = UUID.randomUUID();
        Object world = new Object();
        var access = new FakeBlockMutationAccess();
        BlockPos pos = new BlockPos(0, 1, 0);
        access.states.put(pos, Blocks.STONE.getDefaultState());
        var undo = new UndoService();
        undo.record(player, world, BlockPos.ORIGIN, List.of(
                new BlockChange(pos,
                        BlockSnapshot.of(Blocks.AIR.getDefaultState()),
                        BlockSnapshot.of(Blocks.STONE.getDefaultState()))));
        assertTrue(undo.undo(player, world, access).complete());
        assertTrue(access.states.get(pos).isAir());
        assertTrue(undo.canRedo(player));
        assertTrue(PlayerMutationLedger.get().canRedo(player));
        assertTrue(undo.redo(player, world, access).complete());
        assertEquals(Blocks.STONE.getDefaultState(), access.states.get(pos));
        assertFalse(undo.canRedo(player));
        assertTrue(PlayerMutationLedger.get().canUndo(player));
    }

    @Test
    void directUndoMovesBuildRecordToRedoStack() {
        UUID player = UUID.randomUUID();
        Object world = new Object();
        var access = new FakeBlockMutationAccess();
        BlockPos pos = new BlockPos(1, 1, 0);
        access.states.put(pos, Blocks.OAK_PLANKS.getDefaultState());
        var undo = new UndoService();
        undo.record(player, world, BlockPos.ORIGIN, List.of(
                new BlockChange(pos,
                        BlockSnapshot.of(Blocks.AIR.getDefaultState()),
                        BlockSnapshot.of(Blocks.OAK_PLANKS.getDefaultState()))));
        assertEquals(1, undo.getUndoStackSize(player));
        assertTrue(undo.undo(player, world, access).complete());
        assertEquals(0, undo.getUndoStackSize(player));
        assertTrue(undo.canRedo(player));
        assertTrue(undo.redo(player, world, access).complete());
        assertEquals(1, undo.getUndoStackSize(player));
    }
}
