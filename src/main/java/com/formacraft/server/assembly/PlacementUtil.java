package com.formacraft.server.assembly;

import net.minecraft.block.BlockState;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * PlacementUtil:
 * Local coordinate placement with optional rotation by entranceFacing.
 *
 * Convention:
 * - local SOUTH is the default "front" of the assembly.
 */
public final class PlacementUtil {
    private PlacementUtil() {}

    public static BlockPos local(BlockPos origin, Direction entranceFacing, int x, int y, int z) {
        BlockPos p = origin.add(x, y, z);
        return rotatePos(p, origin, entranceFacing);
    }

    public static BlockPos rotatePos(BlockPos p, BlockPos base, Direction entrance) {
        if (entrance == null || entrance == Direction.SOUTH) return p;
        int dx = p.getX() - base.getX();
        int dy = p.getY() - base.getY();
        int dz = p.getZ() - base.getZ();
        return switch (entrance) {
            case NORTH -> base.add(-dx, dy, -dz);
            case EAST -> base.add(dz, dy, -dx);
            case WEST -> base.add(-dz, dy, dx);
            default -> p;
        };
    }

    public static BlockState rotateState(BlockState s, Direction entranceFacing) {
        if (s == null) return null;
        if (entranceFacing == null || entranceFacing == Direction.SOUTH) return s;
        if (!s.getProperties().contains(Properties.HORIZONTAL_FACING)) return s;
        Direction f = s.get(Properties.HORIZONTAL_FACING);
        if (f == null || !f.getAxis().isHorizontal()) return s;
        return s.with(Properties.HORIZONTAL_FACING, rotateDir(f, entranceFacing));
    }

    private static Direction rotateDir(Direction local, Direction entrance) {
        if (entrance == null || entrance == Direction.SOUTH || local == null) return local;
        if (!local.getAxis().isHorizontal()) return local;
        return switch (entrance) {
            case NORTH -> (local == Direction.NORTH) ? Direction.SOUTH
                    : (local == Direction.SOUTH) ? Direction.NORTH
                    : (local == Direction.EAST) ? Direction.WEST
                    : Direction.EAST;
            case EAST -> (local == Direction.SOUTH) ? Direction.EAST
                    : (local == Direction.NORTH) ? Direction.WEST
                    : (local == Direction.EAST) ? Direction.NORTH
                    : Direction.SOUTH;
            case WEST -> (local == Direction.SOUTH) ? Direction.WEST
                    : (local == Direction.NORTH) ? Direction.EAST
                    : (local == Direction.EAST) ? Direction.SOUTH
                    : Direction.NORTH;
            default -> local;
        };
    }
}


