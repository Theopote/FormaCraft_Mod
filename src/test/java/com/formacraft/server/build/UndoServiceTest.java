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
    private List<BlockChange> changes(BlockPos... positions) {
        return Arrays.stream(positions).map(pos -> new BlockChange(pos,
            Blocks.AIR.getDefaultState(), Blocks.STONE.getDefaultState())).toList();
    }
    @Test void linkedMemoryUsesAbsoluteProgressAcrossPartialUndoAndRetry() {
        var updates = new ArrayList<com.formacraft.server.memory.BuildUndoMemoryUpdate>();
        var service = new UndoService(update -> { updates.add(update); return true; });
        Object world = new Object(); var player = UUID.randomUUID(); var memory = UUID.randomUUID().toString();
        var dimension = net.minecraft.util.Identifier.of("minecraft:the_nether");
        var a = new BlockPos(0, 1, 0); var b = new BlockPos(1, 1, 0); var access = new FakeBlockMutationAccess();
        access.states.put(a, Blocks.STONE.getDefaultState()); access.states.put(b, Blocks.STONE.getDefaultState());
        service.record(player, world, BlockPos.ORIGIN, changes(a, b), memory, dimension);
        assertTrue(service.undo(player, new Object(), access).wrongWorld()); assertTrue(updates.isEmpty());
        access.rejected.add(b); assertFalse(service.undo(player, world, access).complete());
        assertEquals(1, updates.getLast().restoredPositions()); assertFalse(updates.getLast().complete());
        access.rejected.clear(); assertTrue(service.undo(player, world, access).complete());
        assertEquals(2, updates.getLast().restoredPositions()); assertTrue(updates.getLast().complete());
        assertEquals(updates.getFirst().transactionId(), updates.getLast().transactionId());
        assertEquals(memory, updates.getLast().memoryUuid()); assertEquals(dimension, updates.getLast().dimension());
    }
    @Test void memorySaveFailureRetriesEvenAfterBlockHistoryCompletes() {
        var attempts = new ArrayList<com.formacraft.server.memory.BuildUndoMemoryUpdate>();
        boolean[] accepts = {false};
        var service = new UndoService(update -> { attempts.add(update); return accepts[0]; });
        Object world = new Object(); var player = UUID.randomUUID(); var pos = new BlockPos(0, 1, 0);
        var access = new FakeBlockMutationAccess(); access.states.put(pos, Blocks.STONE.getDefaultState());
        service.record(player, world, BlockPos.ORIGIN, changes(pos), UUID.randomUUID().toString(),
            net.minecraft.util.Identifier.of("minecraft:overworld"));
        assertTrue(service.undo(player, world, access).complete()); assertTrue(service.hasPendingMemory(player));
        accepts[0] = true;
        assertFalse(service.undo(player, world, access).available());
        assertFalse(service.hasPendingMemory(player)); assertEquals(1, access.writes);
        assertEquals(attempts.getFirst(), attempts.getLast());
    }
    @Test void unregisteredBuildNeverCreatesMemoryDuringUndo() {
        var service = new UndoService(update -> { fail("Unregistered build must not update memory"); return false; });
        Object world = new Object(); var player = UUID.randomUUID(); var pos = new BlockPos(0, 1, 0);
        var access = new FakeBlockMutationAccess(); access.states.put(pos, Blocks.STONE.getDefaultState());
        service.record(player, world, BlockPos.ORIGIN, changes(pos));
        assertTrue(service.undo(player, world, access).complete()); assertFalse(service.hasPendingMemory(player));
    }
    @Test void newerBuildDoesNotStealOlderBuildMemoryAssociation() {
        var updates = new ArrayList<com.formacraft.server.memory.BuildUndoMemoryUpdate>();
        var service = new UndoService(update -> { updates.add(update); return true; });
        Object world = new Object(); var player = UUID.randomUUID(); var dimension = net.minecraft.util.Identifier.of("minecraft:overworld");
        var older = UUID.randomUUID().toString(); var newer = UUID.randomUUID().toString();
        var a = new BlockPos(0, 1, 0); var b = new BlockPos(1, 1, 0); var access = new FakeBlockMutationAccess();
        access.states.put(a, Blocks.STONE.getDefaultState()); access.states.put(b, Blocks.STONE.getDefaultState());
        service.record(player, world, BlockPos.ORIGIN, changes(a), older, dimension);
        service.record(player, world, BlockPos.ORIGIN, changes(b), newer, dimension);
        assertTrue(service.undo(player, world, access).complete()); assertEquals(newer, updates.getLast().memoryUuid());
        assertTrue(service.undo(player, world, access).complete()); assertEquals(older, updates.getLast().memoryUuid());
    }
    @Test void memoryCallbackExceptionRetainsUpdateAndServerClearDropsSessionState() {
        var service = new UndoService(update -> { throw new IllegalStateException("storage unavailable"); });
        Object world = new Object(); var player = UUID.randomUUID(); var pos = new BlockPos(0, 1, 0);
        var access = new FakeBlockMutationAccess(); access.states.put(pos, Blocks.STONE.getDefaultState());
        service.record(player, world, BlockPos.ORIGIN, changes(pos), UUID.randomUUID().toString(),
            net.minecraft.util.Identifier.of("minecraft:overworld"));
        assertTrue(service.undo(player, world, access).complete()); assertTrue(service.hasPendingMemory(player));
        service.clear(); assertFalse(service.hasPendingMemory(player));
        assertFalse(service.undo(player, world, access).available());
    }

    @Test void emptyNetChangeDoesNotShiftMemoryAssociation() {
        var updates = new ArrayList<com.formacraft.server.memory.BuildUndoMemoryUpdate>();
        var service = new UndoService(update -> { updates.add(update); return true; });
        Object world = new Object(); var player = UUID.randomUUID(); var pos = new BlockPos(0, 1, 0);
        var dimension = net.minecraft.util.Identifier.of("minecraft:overworld"); var memory = UUID.randomUUID().toString();
        var access = new FakeBlockMutationAccess(); access.states.put(pos, Blocks.STONE.getDefaultState());
        service.record(player, world, BlockPos.ORIGIN, changes(pos), memory, dimension);
        var air = Blocks.AIR.getDefaultState(); var stone = Blocks.STONE.getDefaultState();
        service.record(player, world, BlockPos.ORIGIN,
            List.of(new BlockChange(pos, air, stone), new BlockChange(pos, stone, air)), UUID.randomUUID().toString(), dimension);
        assertTrue(service.undo(player, world, access).complete()); assertEquals(memory, updates.getLast().memoryUuid());
        assertFalse(service.undo(player, world, access).available());
    }
    @Test void boundedHistoryKeepsMemoryBindingsAlignedAndIsolatesPlayers() {
        var updates = new ArrayList<com.formacraft.server.memory.BuildUndoMemoryUpdate>();
        var service = new UndoService(update -> { updates.add(update); return true; });
        Object world = new Object(); var player = UUID.randomUUID(); var other = UUID.randomUUID();
        var dimension = net.minecraft.util.Identifier.of("minecraft:overworld"); var access = new FakeBlockMutationAccess();
        var memories = new ArrayList<String>();
        for (int i = 0; i < 11; i++) {
            var pos = new BlockPos(i, 1, 0); var memory = UUID.randomUUID().toString(); memories.add(memory);
            access.states.put(pos, Blocks.STONE.getDefaultState());
            service.record(player, world, BlockPos.ORIGIN, changes(pos), memory, dimension);
        }
        assertFalse(service.undo(other, world, access).available()); assertTrue(updates.isEmpty());
        for (int i = 10; i >= 1; i--) {
            assertTrue(service.undo(player, world, access).complete());
            assertEquals(memories.get(i), updates.getLast().memoryUuid());
        }
        assertFalse(service.undo(player, world, access).available());
        assertEquals(Blocks.STONE.getDefaultState(), access.states.get(new BlockPos(0, 1, 0)));
    }

}
