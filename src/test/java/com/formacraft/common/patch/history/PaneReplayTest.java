package com.formacraft.common.patch.history;

import com.formacraft.common.world.*;
import com.formacraft.test.*;
import net.minecraft.block.*;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PaneReplayTest {
    @BeforeAll static void bootstrap() { MinecraftRegistryTestBootstrap.initialize(); }

    private BlockState connected(BlockState state, int mask) {
        return state.with(Properties.NORTH,(mask&1)!=0).with(Properties.EAST,(mask&2)!=0)
                .with(Properties.SOUTH,(mask&4)!=0).with(Properties.WEST,(mask&8)!=0);
    }

    /** Model synchronous neighbor updates when a pane or its adjacent wall changes. */
    private BlockMutationAccess neighboring(FakeBlockMutationAccess delegate) {
        return new BlockMutationAccess() {
            public boolean isInsideHeight(BlockPos p) { return delegate.isInsideHeight(p); }
            public boolean isChunkReady(BlockPos p) { return delegate.isChunkReady(p); }
            public BlockState getState(BlockPos p) { return delegate.getState(p); }
            public boolean setState(BlockPos p, BlockState state) {
                boolean accepted = delegate.setState(p,state);
                if (!accepted) return false;
                for (var entry : new ArrayList<>(delegate.states.entrySet())) {
                    if (!(entry.getValue().getBlock() instanceof PaneBlock)) continue;
                    var pos=entry.getKey(); int mask=0;
                    if (!delegate.getState(pos.north()).isAir()) mask|=1;
                    if (!delegate.getState(pos.east()).isAir()) mask|=2;
                    if (!delegate.getState(pos.south()).isAir()) mask|=4;
                    if (!delegate.getState(pos.west()).isAir()) mask|=8;
                    delegate.states.put(pos,connected(entry.getValue(),mask));
                }
                return true;
            }
        };
    }

    @Test void connectedGlassRowsAndCornersUndoCompletelyAcrossNeighborUpdatesAndRedo() {
        for (var block : List.of(Blocks.GLASS_PANE,Blocks.WHITE_STAINED_GLASS_PANE,Blocks.IRON_BARS)) {
            var delegate=new FakeBlockMutationAccess(); var access=neighboring(delegate);
            var before=new LinkedHashMap<BlockPos,BlockSnapshot>(); var after=new LinkedHashMap<BlockPos,BlockSnapshot>();
            var positions=List.of(new BlockPos(2,2,2),new BlockPos(3,2,2),new BlockPos(3,2,3),new BlockPos(4,2,3));
            for (var pos : positions) {
                before.put(pos,BlockSnapshot.of(Blocks.AIR.getDefaultState()));
                access.setState(pos,block.getDefaultState());
                after.put(pos,access.getSnapshot(pos)); // Snapshot BEFORE the later neighbors are placed.
            }
            var history=new PatchReplayHistory(10); var world=new Object();
            history.record(world,PatchTransaction.fromSnapshots(BlockPos.ORIGIN,before,after));
            for (int cycle=0;cycle<2;cycle++) {
                var undo=history.undo(world,access);
                assertTrue(undo.complete(),undo::toString); assertEquals(0,undo.conflicts());
                assertTrue(positions.stream().allMatch(p -> delegate.getState(p).isAir()));
                var redo=history.redo(world,access);
                assertTrue(redo.complete(),redo::toString);
                assertTrue(positions.stream().allMatch(p -> delegate.getState(p).isOf(block)));
            }
        }
    }

    @Test void restoringAnOldPaneAcceptsDerivedConnectionsButKeepsAnUnrelatedNeighbor() {
        var delegate=new FakeBlockMutationAccess(); var access=neighboring(delegate); var world=new Object();
        var pos=new BlockPos(3,2,3); var neighbor=pos.east();
        delegate.states.put(neighbor,Blocks.STONE.getDefaultState());
        delegate.states.put(pos,Blocks.OAK_PLANKS.getDefaultState());
        var history=new PatchReplayHistory(10);
        history.record(world,PatchTransaction.fromStateSnapshots(BlockPos.ORIGIN,
                Map.of(pos,Blocks.GLASS_PANE.getDefaultState()),Map.of(pos,Blocks.OAK_PLANKS.getDefaultState())));
        var result=history.undo(world,access);
        assertTrue(result.complete(),result::toString); assertEquals(0,result.failedWrites());
        assertTrue(delegate.getState(pos).get(Properties.EAST));
        assertTrue(delegate.getState(neighbor).isOf(Blocks.STONE));
        assertTrue(history.redo(world,access).complete());
    }

    @Test void realEditsToPaneMaterialWaterloggingOrNbtRemainConflicts() {
        var pos=new BlockPos(3,2,3); var pane=Blocks.GLASS_PANE.getDefaultState();
        for (var edited : List.of(Blocks.BLUE_STAINED_GLASS_PANE.getDefaultState(),Blocks.STONE.getDefaultState(),
                pane.with(Properties.WATERLOGGED,true))) {
            var access=new FakeBlockMutationAccess(); access.states.put(pos,edited);
            var history=new PatchReplayHistory(10); var world=new Object();
            history.record(world,PatchTransaction.fromStateSnapshots(BlockPos.ORIGIN,Map.of(pos,Blocks.AIR.getDefaultState()),Map.of(pos,pane)));
            var result=history.undo(world,access);
            assertEquals(1,result.conflicts()); assertEquals(0,access.writes); assertFalse(result.complete());
        }
        var nbt=new net.minecraft.nbt.NbtCompound(); nbt.putString("marker","changed");
        assertFalse(ReplaySnapshotMatcher.matches(BlockSnapshot.of(pane,nbt),BlockSnapshot.of(pane)));
        assertNotEquals(BlockSnapshot.of(pane),BlockSnapshot.of(connected(pane,15))); // Global snapshot equality stays strict.
    }

    @Test void rejectedPaneWriteRemainsRetryable() {
        var access=new FakeBlockMutationAccess(); var pos=new BlockPos(3,2,3); var world=new Object();
        var pane=Blocks.GLASS_PANE.getDefaultState(); access.states.put(pos,connected(pane,15)); access.rejected.add(pos);
        var history=new PatchReplayHistory(10);
        history.record(world,PatchTransaction.fromStateSnapshots(BlockPos.ORIGIN,Map.of(pos,Blocks.AIR.getDefaultState()),Map.of(pos,pane)));
        var result=history.undo(world,access);
        assertFalse(result.complete()); assertEquals(1,result.failedWrites()); assertEquals(1,result.remaining());
        access.rejected.clear(); assertTrue(history.undo(world,access).complete());
    }
}
