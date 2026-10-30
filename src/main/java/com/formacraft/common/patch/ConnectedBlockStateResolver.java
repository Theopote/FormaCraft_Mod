package com.formacraft.common.patch;

import com.formacraft.common.build.PlannedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fills north/south/east/west (and wall shapes) from planned neighbors so panes/fences
 * preview and place already connected instead of thin unlinked posts.
 */
public final class ConnectedBlockStateResolver {
    private ConnectedBlockStateResolver() {}

    public static List<PlannedBlock> apply(List<PlannedBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) return blocks;
        Map<BlockPos, BlockState> map = new HashMap<>(blocks.size() * 2);
        for (PlannedBlock b : blocks) {
            if (b == null || b.getPos() == null || b.getTargetState() == null) continue;
            map.put(b.getPos(), b.getTargetState());
        }
        List<PlannedBlock> out = new ArrayList<>(blocks.size());
        boolean changed = false;
        for (PlannedBlock b : blocks) {
            if (b == null || b.getPos() == null || b.getTargetState() == null) {
                out.add(b);
                continue;
            }
            BlockState resolved = resolve(b.getPos(), b.getTargetState(), map);
            if (resolved != b.getTargetState()) {
                changed = true;
                out.add(new PlannedBlock(b.getPos(), resolved, b.getSource()));
            } else {
                out.add(b);
            }
        }
        return changed ? out : blocks;
    }

    static BlockState resolve(BlockPos pos, BlockState state, Map<BlockPos, BlockState> map) {
        if (state.getBlock() instanceof PaneBlock || state.getBlock() instanceof FenceBlock) {
            return connectBooleans(pos, state, map);
        }
        if (state.getBlock() instanceof WallBlock) {
            return connectWall(pos, state, map);
        }
        return state;
    }

    private static BlockState connectBooleans(BlockPos pos, BlockState state, Map<BlockPos, BlockState> map) {
        BlockState next = state;
        next = withBool(next, Properties.NORTH, connects(state, map.get(pos.north()), Direction.NORTH));
        next = withBool(next, Properties.SOUTH, connects(state, map.get(pos.south()), Direction.SOUTH));
        next = withBool(next, Properties.EAST, connects(state, map.get(pos.east()), Direction.EAST));
        next = withBool(next, Properties.WEST, connects(state, map.get(pos.west()), Direction.WEST));
        return next;
    }

    private static BlockState connectWall(BlockPos pos, BlockState state, Map<BlockPos, BlockState> map) {
        // Walls use enum shapes; boolean Properties are absent. Leave authored wall states alone
        // unless only default disconnected — then set LOW where a solid neighbor exists.
        BlockState next = state;
        next = withWall(next, Properties.NORTH_WALL_SHAPE, map.get(pos.north()));
        next = withWall(next, Properties.SOUTH_WALL_SHAPE, map.get(pos.south()));
        next = withWall(next, Properties.EAST_WALL_SHAPE, map.get(pos.east()));
        next = withWall(next, Properties.WEST_WALL_SHAPE, map.get(pos.west()));
        if (next.contains(Properties.UP)) {
            BlockState above = map.get(pos.up());
            boolean up = above != null && !above.isAir() && above.isOpaque();
            next = next.with(Properties.UP, up);
        }
        return next;
    }

    private static BlockState withBool(BlockState state, BooleanProperty prop, boolean value) {
        if (!state.contains(prop)) return state;
        return state.with(prop, value);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState withWall(BlockState state, net.minecraft.state.property.EnumProperty prop, BlockState neighbor) {
        if (!state.contains(prop)) return state;
        boolean link = neighbor != null && !neighbor.isAir()
                && (neighbor.isOpaque() || neighbor.getBlock() instanceof WallBlock
                || neighbor.getBlock() instanceof PaneBlock || neighbor.getBlock() instanceof FenceBlock);
        Object none = prop.parse("none").orElse(null);
        Object low = prop.parse("low").orElse(null);
        if (none == null || low == null) return state;
        return state.with(prop, (Comparable) (link ? low : none));
    }

    private static boolean connects(BlockState self, BlockState neighbor, Direction dir) {
        if (neighbor == null || neighbor.isAir()) return false;
        if (neighbor.getBlock() instanceof PaneBlock || neighbor.getBlock() instanceof FenceBlock) return true;
        if (neighbor.getBlock() instanceof WallBlock) return true;
        // Full opaque cubes (stone, glass blocks, logs, …) attach; stairs/slabs usually do not.
        return neighbor.isOpaque();
    }
}
