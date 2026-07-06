package com.formacraft.server.generation.structure.util;

import com.formacraft.common.build.PlannedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Shared Tang / Chinese landmark micro-details for structure generators (P2).
 */
public final class ChineseLandmarkDetailUtil {

    private ChineseLandmarkDetailUtil() {}

    /** Regular octagon on voxel grid (half = distance to flat face along axis). */
    public static boolean inRegularOctagon(int x, int z, int half) {
        if (half <= 0) return x == 0 && z == 0;
        int ax = Math.abs(x);
        int az = Math.abs(z);
        if (ax > half || az > half) return false;
        int cornerAllowance = Math.max(1, (int) Math.round(half * (Math.sqrt(2.0) - 1.0)));
        return ax + az <= half + cornerAllowance;
    }

    public static boolean onRegularOctagonEdge(int x, int z, int half) {
        if (!inRegularOctagon(x, z, half)) return false;
        return !inRegularOctagon(x - 1, z, half)
                || !inRegularOctagon(x + 1, z, half)
                || !inRegularOctagon(x, z - 1, half)
                || !inRegularOctagon(x, z + 1, half);
    }

    public static void ringRegularOctagon(List<PlannedBlock> blocks, BlockPos origin, int half, int y, BlockState state) {
        for (int x = -half; x <= half; x++) {
            for (int z = -half; z <= half; z++) {
                if (inRegularOctagon(x, z, half) && !inRegularOctagon(x, z, half - 1)) {
                    blocks.add(new PlannedBlock(origin.add(x, y, z), state));
                }
            }
        }
    }

    /** Outermost in-octagon cell along a horizontal axis (for façade niches). */
    public static BlockPos octagonFaceCell(int half, Direction face) {
        return switch (face) {
            case SOUTH -> new BlockPos(0, 0, farthestAlongZ(half, 1));
            case NORTH -> new BlockPos(0, 0, -farthestAlongZ(half, -1));
            case EAST -> new BlockPos(farthestAlongX(half, 1), 0, 0);
            case WEST -> new BlockPos(-farthestAlongX(half, -1), 0, 0);
            default -> new BlockPos(0, 0, half);
        };
    }

    private static int farthestAlongZ(int half, int sign) {
        for (int d = half; d >= 0; d--) {
            if (inRegularOctagon(0, sign * d, half)) return sign * d;
        }
        return sign * half;
    }

    private static int farthestAlongX(int half, int sign) {
        for (int d = half; d >= 0; d--) {
            if (inRegularOctagon(sign * d, 0, half)) return sign * d;
        }
        return sign * half;
    }

    /**
     * Tang brick pagoda arched niche (券形假窗): 1×2 void + stair/slab arch head.
     */
    public static void carveArchedNiche(
            List<PlannedBlock> blocks,
            BlockPos origin,
            int x,
            int y,
            int z,
            Direction face,
            BlockState archTrim,
            BlockState archStair
    ) {
        blocks.add(new PlannedBlock(origin.add(x, y, z), Blocks.AIR.getDefaultState()));
        blocks.add(new PlannedBlock(origin.add(x, y + 1, z), Blocks.AIR.getDefaultState()));
        BlockState stair = withFacing(archStair, face.getOpposite());
        if (stair.contains(Properties.BLOCK_HALF)) {
            stair = stair.with(Properties.BLOCK_HALF, BlockHalf.TOP);
        }
        blocks.add(new PlannedBlock(origin.add(x, y + 2, z), stair));
        int lx = x + face.rotateYCW().getOffsetX();
        int lz = z + face.rotateYCW().getOffsetZ();
        int rx = x + face.rotateYCCW().getOffsetX();
        int rz = z + face.rotateYCCW().getOffsetZ();
        blocks.add(new PlannedBlock(origin.add(lx, y + 2, lz), archTrim));
        blocks.add(new PlannedBlock(origin.add(rx, y + 2, rz), archTrim));
    }

    /** Place arched niches on cardinal faces (optional diagonals when refined). */
    public static void addPagodaArchedNiches(
            List<PlannedBlock> blocks,
            BlockPos origin,
            int half,
            int y0,
            int levelH,
            boolean refined,
            BlockState trim,
            BlockState stair
    ) {
        if (levelH < 3 || half < 3) return;
        int y = y0 + Math.max(1, levelH / 2) - 1;
        Direction[] faces = refined
                ? new Direction[]{Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST}
                : new Direction[]{Direction.SOUTH, Direction.NORTH};
        for (Direction face : faces) {
            BlockPos cell = octagonFaceCell(half, face);
            carveArchedNiche(blocks, origin, cell.getX(), y, cell.getZ(), face, trim, stair);
        }
        if (refined && half >= 4) {
            int d = Math.max(1, half - 1);
            carveArchedNiche(blocks, origin, d, y, d, Direction.SOUTH, trim, stair);
            carveArchedNiche(blocks, origin, -d, y, d, Direction.SOUTH, trim, stair);
            carveArchedNiche(blocks, origin, d, y, -d, Direction.NORTH, trim, stair);
            carveArchedNiche(blocks, origin, -d, y, -d, Direction.NORTH, trim, stair);
        }
    }

    /** Multi-tier stupa finial (相轮) with shrinking oct rings. */
    public static void addStupaFinial(
            List<PlannedBlock> blocks,
            BlockPos origin,
            int baseY,
            BlockState accent,
            BlockState ring,
            BlockState spire
    ) {
        blocks.add(new PlannedBlock(origin.add(0, baseY, 0), accent));
        blocks.add(new PlannedBlock(origin.add(0, baseY + 1, 0), accent));
        blocks.add(new PlannedBlock(origin.add(0, baseY + 2, 0), Blocks.CHAIN.getDefaultState()));
        int y = baseY + 3;
        for (int tier = 0; tier < 3; tier++) {
            int rh = Math.max(1, 2 - tier / 2);
            ringRegularOctagon(blocks, origin, rh, y, ring);
            blocks.add(new PlannedBlock(origin.add(0, y + 1, 0), accent));
            y += 2;
        }
        blocks.add(new PlannedBlock(origin.add(0, y, 0), spire != null ? spire : Blocks.LIGHTNING_ROD.getDefaultState()));
    }

    /**
     * Column-top dougong stack (铺作): capital + outward bracket arms + second tier.
     */
    public static void addDougongStack(
            BiConsumer<BlockPos, BlockState> place,
            int x,
            int y,
            int z,
            Direction outward,
            BlockState bracket,
            BlockState arm,
            BlockState paint
    ) {
        place.accept(new BlockPos(x, y, z), bracket);
        place.accept(new BlockPos(x, y + 1, z), bracket);
        int ox = outward.getOffsetX();
        int oz = outward.getOffsetZ();
        place.accept(new BlockPos(x + ox, y, z + oz), arm);
        place.accept(new BlockPos(x + 2 * ox, y, z + 2 * oz), arm);
        place.accept(new BlockPos(x + ox, y + 1, z + oz), withFacing(arm, outward));
        if (paint != null) {
            place.accept(new BlockPos(x - oz, y, z + ox), paint);
            place.accept(new BlockPos(x + oz, y, z - ox), paint);
        }
    }

    /** Horizontal lattice strip (直棂/格子窗意象) between two columns. */
    public static void addLatticeWindow(
            BiConsumer<BlockPos, BlockState> place,
            int x0,
            int x1,
            int y0,
            int y1,
            int z,
            BlockState frame,
            BlockState infill
    ) {
        int left = Math.min(x0, x1);
        int right = Math.max(x0, x1);
        if (right - left < 2) return;
        for (int y = y0; y <= y1; y++) {
            place.accept(new BlockPos(left, y, z), frame);
            place.accept(new BlockPos(right, y, z), frame);
        }
        for (int x = left + 1; x < right; x++) {
            for (int y = y0 + 1; y < y1; y += 2) {
                place.accept(new BlockPos(x, y, z), infill);
            }
        }
    }

    /** Collect column positions on a rectangle perimeter (inclusive corners). */
    public static List<int[]> perimeterColumnPositions(int x0, int z0, int x1, int z1, int spacing) {
        List<int[]> out = new ArrayList<>();
        for (int x = x0; x <= x1; x += spacing) {
            out.add(new int[]{x, z0});
            out.add(new int[]{x, z1});
        }
        for (int z = z0 + spacing; z < z1; z += spacing) {
            out.add(new int[]{x0, z});
            out.add(new int[]{x1, z});
        }
        return out;
    }

    public static Direction outwardFromRectEdge(int x, int z, int x0, int z0, int x1, int z1) {
        if (z == z0) return Direction.NORTH;
        if (z == z1) return Direction.SOUTH;
        if (x == x0) return Direction.WEST;
        if (x == x1) return Direction.EAST;
        int cx = (x0 + x1) / 2;
        int cz = (z0 + z1) / 2;
        int dx = x - cx;
        int dz = z - cz;
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx < 0 ? Direction.WEST : Direction.EAST;
        }
        return dz < 0 ? Direction.NORTH : Direction.SOUTH;
    }

    private static BlockState withFacing(BlockState state, Direction facing) {
        if (state != null && state.contains(Properties.HORIZONTAL_FACING)) {
            return state.with(Properties.HORIZONTAL_FACING, facing);
        }
        return state;
    }
}
