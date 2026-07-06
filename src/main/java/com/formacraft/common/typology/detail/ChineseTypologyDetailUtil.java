package com.formacraft.common.typology.detail;

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
 * Shared Tang / Chinese typology micro-details (octagon pagoda, puzuo, lattice, sub-eaves roof).
 */
public final class ChineseTypologyDetailUtil {

    public record OctagonFace(int x, int z, Direction outward) {}

    public enum PuzuoProfile {
        INTERIOR_EDGE,
        INTERIOR_CORNER,
        SUB_EAVES_EDGE,
        SUB_EAVES_CORNER
    }

    private ChineseTypologyDetailUtil() {}

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

    public static void carveArchedNiche(
            List<PlannedBlock> blocks,
            BlockPos origin,
            int x, int y, int z,
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
        int lx = x + face.rotateYClockwise().getOffsetX();
        int lz = z + face.rotateYClockwise().getOffsetZ();
        int rx = x + face.rotateYCounterclockwise().getOffsetX();
        int rz = z + face.rotateYCounterclockwise().getOffsetZ();
        blocks.add(new PlannedBlock(origin.add(lx, y + 2, lz), archTrim));
        blocks.add(new PlannedBlock(origin.add(rx, y + 2, rz), archTrim));
    }

    public static OctagonFace octagonFaceByIndex(int half, int faceIndex) {
        int d = Math.max(1, half - 1);
        return switch (Math.floorMod(faceIndex, 8)) {
            case 0 -> faceFromCell(0, farthestAlongZ(half, 1), Direction.SOUTH);
            case 1 -> faceFromCell(0, -farthestAlongZ(half, -1), Direction.NORTH);
            case 2 -> faceFromCell(farthestAlongX(half, 1), 0, Direction.EAST);
            case 3 -> faceFromCell(-farthestAlongX(half, -1), 0, Direction.WEST);
            case 4 -> faceFromCell(d, d, Direction.SOUTH);
            case 5 -> faceFromCell(-d, d, Direction.SOUTH);
            case 6 -> faceFromCell(d, -d, Direction.NORTH);
            default -> faceFromCell(-d, -d, Direction.NORTH);
        };
    }

    private static OctagonFace faceFromCell(int x, int z, Direction outward) {
        return new OctagonFace(x, z, outward);
    }

    public static void addPagodaTierNiche(
            List<PlannedBlock> blocks, BlockPos origin, int half,
            int y0, int levelH, int levelIndex, int totalLevels,
            boolean refined, BlockState trim, BlockState stair
    ) {
        if (levelIndex <= 0 || levelIndex >= totalLevels || levelH < 3 || half < 3) return;
        int y = y0 + Math.max(1, levelH / 2) - 1;
        OctagonFace primary = octagonFaceByIndex(half, levelIndex);
        carveArchedNiche(blocks, origin, primary.x(), y, primary.z(), primary.outward(), trim, stair);
        if (refined && levelIndex % 2 == 0) {
            OctagonFace opposite = octagonFaceByIndex(half, levelIndex + 4);
            carveArchedNiche(blocks, origin, opposite.x(), y, opposite.z(), opposite.outward(), trim, stair);
        }
    }

    public static void addStupaFinial(
            List<PlannedBlock> blocks, BlockPos origin, int baseY,
            BlockState accent, BlockState ring, BlockState spire
    ) {
        blocks.add(new PlannedBlock(origin.add(0, baseY, 0), accent));
        blocks.add(new PlannedBlock(origin.add(0, baseY + 1, 0), accent));
        blocks.add(new PlannedBlock(origin.add(0, baseY + 2, 0), Blocks.IRON_CHAIN.getDefaultState()));
        int y = baseY + 3;
        for (int tier = 0; tier < 3; tier++) {
            int rh = Math.max(1, 2 - tier / 2);
            ringRegularOctagon(blocks, origin, rh, y, ring);
            blocks.add(new PlannedBlock(origin.add(0, y + 1, 0), accent));
            y += 2;
        }
        blocks.add(new PlannedBlock(origin.add(0, y, 0), spire != null ? spire : Blocks.LIGHTNING_ROD.getDefaultState()));
    }

    public static void addPuzuoZoning(
            BiConsumer<BlockPos, BlockState> place,
            int x, int y, int z,
            Direction outward, Direction along,
            PuzuoProfile profile,
            BlockState luDou, BlockState gong, BlockState fang, BlockState paint
    ) {
        boolean corner = profile == PuzuoProfile.INTERIOR_CORNER || profile == PuzuoProfile.SUB_EAVES_CORNER;
        boolean full = profile == PuzuoProfile.INTERIOR_CORNER || profile == PuzuoProfile.INTERIOR_EDGE;

        place.accept(new BlockPos(x, y, z), luDou);
        int ox = outward.getOffsetX();
        int oz = outward.getOffsetZ();
        int ax = along.getOffsetX();
        int az = along.getOffsetZ();

        place.accept(new BlockPos(x + ox, y + 1, z + oz), gong);
        if (corner) {
            place.accept(new BlockPos(x + ax, y + 1, z + az), gong);
            place.accept(new BlockPos(x - ax, y + 1, z - az), gong);
        }
        place.accept(new BlockPos(x, y + 2, z), fang);
        place.accept(new BlockPos(x + ox, y + 2, z + oz), withFacing(gong, outward));
        if (full) {
            if (corner) {
                place.accept(new BlockPos(x + ax, y + 2, z + az), withFacing(gong, along));
                place.accept(new BlockPos(x - ax, y + 2, z - az), withFacing(gong, along.getOpposite()));
            }
            for (int t = -1; t <= 1; t++) {
                place.accept(new BlockPos(x + ax * t, y + 3, z + az * t), fang);
            }
            if (paint != null) {
                place.accept(new BlockPos(x + ox, y + 3, z + oz), paint);
                if (corner) place.accept(new BlockPos(x + ax, y + 3, z + az), paint);
            }
        }
    }

    public static boolean isCornerColumn(int x, int z, int x0, int z0, int x1, int z1) {
        return (x == x0 || x == x1) && (z == z0 || z == z1);
    }

    public static Direction alongWallFromEdge(int x, int z, int x0, int z0, int x1, int z1) {
        if (z == z0 || z == z1) return Direction.EAST;
        if (x == x0 || x == x1) return Direction.SOUTH;
        return Direction.EAST;
    }

    public static PuzuoProfile resolvePuzuoProfile(boolean subEaves, boolean corner) {
        if (subEaves) return corner ? PuzuoProfile.SUB_EAVES_CORNER : PuzuoProfile.SUB_EAVES_EDGE;
        return corner ? PuzuoProfile.INTERIOR_CORNER : PuzuoProfile.INTERIOR_EDGE;
    }

    public static void addSubEavesInnerRoof(
            BiConsumer<BlockPos, BlockState> place,
            int innerX0, int innerZ0, int innerX1, int innerZ1,
            int outerX0, int outerZ0, int outerX1, int outerZ1,
            int y, BlockState slab, BlockState stair
    ) {
        for (int x = innerX0; x <= innerX1; x++) {
            for (int z = innerZ1 + 1; z <= outerZ1 - 1; z++) {
                place.accept(new BlockPos(x, y, z), slab);
                if (z == innerZ1 + 1) {
                    place.accept(new BlockPos(x, y, z), withFacing(stair, Direction.SOUTH));
                }
            }
        }
        for (int x = innerX0; x <= innerX1; x++) {
            for (int z = outerZ0; z < innerZ0; z++) {
                place.accept(new BlockPos(x, y, z), slab);
                if (z == innerZ0 - 1) {
                    place.accept(new BlockPos(x, y, z), withFacing(stair, Direction.NORTH));
                }
            }
        }
        for (int z = innerZ0; z <= innerZ1; z++) {
            for (int x = outerX0; x < innerX0; x++) {
                place.accept(new BlockPos(x, y, z), slab);
                if (x == innerX0 - 1) {
                    place.accept(new BlockPos(x, y, z), withFacing(stair, Direction.EAST));
                }
            }
            for (int x = innerX1 + 1; x <= outerX1 - 1; x++) {
                place.accept(new BlockPos(x, y, z), slab);
                if (x == innerX1 + 1) {
                    place.accept(new BlockPos(x, y, z), withFacing(stair, Direction.WEST));
                }
            }
        }
        ringRectInnerRoofCap(place, innerX0, innerZ0, innerX1, innerZ1, outerX0, outerZ0, outerX1, outerZ1, y + 1, slab);
    }

    private static void ringRectInnerRoofCap(
            BiConsumer<BlockPos, BlockState> place,
            int ix0, int iz0, int ix1, int iz1,
            int ox0, int oz0, int ox1, int oz1,
            int y, BlockState slab
    ) {
        for (int x = ox0; x <= ox1; x++) {
            place.accept(new BlockPos(x, y, oz0), slab);
            place.accept(new BlockPos(x, y, oz1), slab);
        }
        for (int z = oz0; z <= oz1; z++) {
            place.accept(new BlockPos(ox0, y, z), slab);
            place.accept(new BlockPos(ox1, y, z), slab);
        }
        for (int x = ix0; x <= ix1; x++) {
            place.accept(new BlockPos(x, y, iz0), slab);
            place.accept(new BlockPos(x, y, iz1), slab);
        }
        for (int z = iz0; z <= iz1; z++) {
            place.accept(new BlockPos(ix0, y, z), slab);
            place.accept(new BlockPos(ix1, y, z), slab);
        }
    }

    public static void addLatticeWindow(
            BiConsumer<BlockPos, BlockState> place,
            int x0, int x1, int y0, int y1, int z,
            BlockState frame, BlockState infill
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
