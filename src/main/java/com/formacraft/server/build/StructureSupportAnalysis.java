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

    /**
     * Bottom columns of disconnected, ungrounded components.
     * <p>
     * Near-ground stilts are always reported. Elevated components are reported only when they
     * look like solid floating platforms (e.g. a detached FBD flat roof). Sparse mid-height
     * cantilever rings (pagoda eaves) stay ignored so auto-repair does not pour columns through
     * hollow interiors.
     */
    public static List<BlockPos> unsupportedBases(List<PlannedBlock> blocks, Function<BlockPos, BlockState> terrain) {
        var states = finalStates(blocks);
        int globalMinY = Integer.MAX_VALUE;
        for (var pos : states.keySet()) {
            BlockState s = states.get(pos);
            if (carriesSupport(s)) globalMinY = Math.min(globalMinY, pos.getY());
        }
        if (globalMinY == Integer.MAX_VALUE) return List.of();
        final int baseBandTop = globalMinY + 3;

        var remaining = new HashSet<BlockPos>();
        states.forEach((pos, state) -> { if (carriesSupport(state)) remaining.add(pos); });
        var unsupported = new ArrayList<BlockPos>();
        var directions = Direction.values();
        for (var start : states.keySet()) {
            if (!remaining.remove(start)) continue;
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            var queue = new ArrayDeque<BlockPos>();
            var bottoms = new HashMap<Long, BlockPos>();
            var component = new ArrayList<BlockPos>();
            queue.add(start);
            boolean grounded = false;
            int minY = Integer.MAX_VALUE;
            int visited = 0;
            while (!queue.isEmpty()) {
                if ((++visited & 1023) == 0 && Thread.currentThread().isInterrupted())
                    throw new java.util.concurrent.CancellationException();
                var pos = queue.removeFirst();
                component.add(pos);
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
            if (grounded) continue;
            boolean nearBase = minY <= baseBandTop;
            boolean solidFloat = !nearBase && looksSolidFloatingPlatform(component);
            if (!nearBase && !solidFloat) continue;
            int band = minY + 2;
            bottoms.values().stream()
                    .filter(p -> p.getY() <= band)
                    .filter(p -> nearBase ? p.getY() <= baseBandTop : true)
                    .forEach(unsupported::add);
        }
        unsupported.sort(Comparator.comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        return List.copyOf(unsupported);
    }

    /**
     * Solid elevated slabs (floating roofs) have high XZ fill; eave rings are sparse perimeters.
     */
    static boolean looksSolidFloatingPlatform(List<BlockPos> component) {
        if (component == null || component.size() < 9) return false;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        var columns = new HashSet<Long>();
        for (BlockPos p : component) {
            minX = Math.min(minX, p.getX());
            maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ());
            maxZ = Math.max(maxZ, p.getZ());
            minY = Math.min(minY, p.getY());
            maxY = Math.max(maxY, p.getY());
            columns.add(((long) p.getX() << 32) ^ (p.getZ() & 0xffffffffL));
        }
        int width = maxX - minX + 1;
        int depth = maxZ - minZ + 1;
        int height = maxY - minY + 1;
        if (width < 3 || depth < 3 || height > 3) return false;
        int area = width * depth;
        // Require a mostly filled rectangle — rings leave the interior empty.
        return columns.size() * 4 >= area * 3;
    }
}
