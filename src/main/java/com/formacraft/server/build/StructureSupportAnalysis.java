package com.formacraft.server.build;

import com.formacraft.common.build.PlannedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import java.util.*;
import java.util.function.Function;

/** Connectivity check, not a structural load solver. Planned AIR overrides terrain. */
public final class StructureSupportAnalysis {
    private StructureSupportAnalysis() {}

    public static boolean carriesSupport(BlockState state) {
        return state != null && !state.isAir() && !(state.getBlock() instanceof net.minecraft.block.FluidBlock)
                && !state.isReplaceable();
    }

    public static Map<BlockPos, BlockState> finalStates(List<PlannedBlock> blocks) {
        var states = new LinkedHashMap<BlockPos, BlockState>();
        for (var block : blocks) if (block != null && block.getPos() != null && block.getTargetState() != null)
            states.put(block.getPos().toImmutable(), block.getTargetState());
        return states;
    }

    public static BlockState effectiveState(Map<BlockPos, BlockState> planned,
                                            Function<BlockPos, BlockState> terrain, BlockPos pos) {
        return planned.containsKey(pos) ? planned.get(pos) : terrain.apply(pos);
    }

    /** Bottom columns of disconnected, ungrounded components; attached eaves are not pillars. */
    public static List<BlockPos> unsupportedBases(List<PlannedBlock> blocks, Function<BlockPos, BlockState> terrain) {
        var states = finalStates(blocks);
        var remaining = new HashSet<BlockPos>();
        states.forEach((pos, state) -> { if (carriesSupport(state)) remaining.add(pos); });
        var unsupported = new ArrayList<BlockPos>();
        var directions = Direction.values();
        for (var start : states.keySet()) {
            if (!remaining.remove(start)) continue;
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            var queue = new ArrayDeque<BlockPos>();
            var bottoms = new HashMap<Long, BlockPos>();
            queue.add(start);
            boolean grounded = false;
            int minY = Integer.MAX_VALUE;
            int visited = 0;
            while (!queue.isEmpty()) {
                if ((++visited & 1023) == 0 && Thread.currentThread().isInterrupted())
                    throw new java.util.concurrent.CancellationException();
                var pos = queue.removeFirst();
                minY = Math.min(minY, pos.getY());
                long column = ((long)pos.getX() << 32) ^ (pos.getZ() & 0xffffffffL);
                var previous = bottoms.get(column);
                if (previous == null || pos.getY() < previous.getY()) bottoms.put(column, pos);
                var below = pos.down();
                if (!grounded && !states.containsKey(below) && carriesSupport(terrain.apply(below))) grounded = true;
                for (var direction : directions) {
                    var neighbor = pos.offset(direction);
                    if (remaining.remove(neighbor)) queue.addLast(neighbor);
                }
            }
            if (!grounded) {
                int band = minY + 2;
                bottoms.values().stream().filter(p -> p.getY() <= band).forEach(unsupported::add);
            }
        }
        unsupported.sort(Comparator.comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        return List.copyOf(unsupported);
    }
}
