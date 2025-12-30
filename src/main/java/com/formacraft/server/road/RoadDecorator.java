package com.formacraft.server.road;

import com.formacraft.server.build.BuildConstraintContext;
import com.formacraft.server.build.PlannedBlock;
import com.formacraft.server.material.PaletteResolver;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.StairsBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * RoadDecorator:
 * Converts a sampled path into PlannedBlocks:
 * - normal road surface
 * - stairs on +/-1 dy
 * - simple bridge deck on water/gap
 * - optional headroom clearing
 */
public final class RoadDecorator {
    private RoadDecorator() {}

    public static List<PlannedBlock> decorate(ServerWorld world,
                                              List<BlockPos> path,
                                              int width,
                                              int clearHeight,
                                              BlockState road,
                                              BlockState border,
                                              boolean useBorder,
                                              BlockState bridgeDeck,
                                              BlockState bridgeRail) {
        return decorate(world, path, width, clearHeight, road, border, useBorder, bridgeDeck, bridgeRail, null);
    }

    /**
     * Palette-aware decorate: border/rails can be varied by paletteId semantic parts.
     * - border: ROAD_BORDER
     * - bridge rail: DECOR_DETAIL
     */
    public static List<PlannedBlock> decorate(ServerWorld world,
                                              List<BlockPos> path,
                                              int width,
                                              int clearHeight,
                                              BlockState road,
                                              BlockState border,
                                              boolean useBorder,
                                              BlockState bridgeDeck,
                                              BlockState bridgeRail,
                                              String paletteId) {
        if (path == null || path.isEmpty()) return List.of();
        int w = Math.max(1, width);
        int half = w / 2;
        int ch = Math.max(0, clearHeight);
        String pid = (paletteId == null || paletteId.isBlank()) ? null : paletteId.trim();

        List<PlannedBlock> out = new ArrayList<>(Math.max(200, path.size() * w * 2));

        for (int i = 0; i < path.size(); i++) {
            BlockPos p = path.get(i);
            BlockPos prev = (i > 0) ? path.get(i - 1) : null;
            BlockPos next = (i + 1 < path.size()) ? path.get(i + 1) : null;

            // approximate tangent direction (cardinal)
            int dx = 0, dz = 0;
            if (next != null) {
                dx = Integer.compare(next.getX() - p.getX(), 0);
                dz = Integer.compare(next.getZ() - p.getZ(), 0);
            } else if (prev != null) {
                dx = Integer.compare(p.getX() - prev.getX(), 0);
                dz = Integer.compare(p.getZ() - prev.getZ(), 0);
            }
            if (dx == 0 && dz == 0) { dx = 1; dz = 0; }
            int rx = -dz;
            int rz = dx;

            int dyFromPrev = (prev != null) ? (p.getY() - prev.getY()) : 0;
            boolean step = Math.abs(dyFromPrev) == 1;
            boolean bridge = isBridgeUnder(world, p);

            Direction facing = dirFromDelta(dx, dz);
            // For downward movement, stairs should face towards the higher block.
            if (dyFromPrev < 0) facing = dirFromDelta(Integer.compare(prev.getX() - p.getX(), 0),
                    Integer.compare(prev.getZ() - p.getZ(), 0));

            for (int ww = -half; ww <= half; ww++) {
                int x2 = p.getX() + rx * ww;
                int z2 = p.getZ() + rz * ww;
                BlockPos bp = new BlockPos(x2, p.getY(), z2);

                BlockState base = road;
                if (bridge) {
                    base = bridgeDeck;
                    if (pid != null) {
                        long salt = ((long) x2 * 31L) ^ ((long) z2 * 17L) ^ ((long) ww * 13L) ^ (i * 7L) ^ 0xBDEC1L;
                        base = PaletteResolver.pick(world, pid, "BRIDGE_DECK", bp, salt, base);
                    }
                } else if (pid != null) {
                    // ROAD_SURFACE semantic for non-bridge segments
                    long salt = ((long) x2 * 31L) ^ ((long) z2 * 17L) ^ ((long) ww * 13L) ^ (i * 7L) ^ 0xB04DL;
                    base = PaletteResolver.pick(world, pid, "ROAD_SURFACE", bp, salt, base);
                }
                if (step) base = asStairsIfPossible(base, facing);

                if (BuildConstraintContext.allow(bp)) out.add(new PlannedBlock(bp, base));

                // clear headroom (walkable)
                for (int h = 1; h <= ch; h++) {
                    BlockPos up = bp.up(h);
                    if (BuildConstraintContext.allow(up)) out.add(new PlannedBlock(up, Blocks.AIR.getDefaultState()));
                }
            }

            if (useBorder) {
                BlockPos b1 = new BlockPos(p.getX() + rx * (half + 1), p.getY(), p.getZ() + rz * (half + 1));
                BlockPos b2 = new BlockPos(p.getX() - rx * (half + 1), p.getY(), p.getZ() - rz * (half + 1));
                BlockState b1s = border;
                BlockState b2s = border;
                if (pid != null) {
                    long salt1 = ((long) b1.getX() * 31L) ^ ((long) b1.getZ() * 17L) ^ (i * 11L) ^ 0xB01DL;
                    long salt2 = ((long) b2.getX() * 31L) ^ ((long) b2.getZ() * 17L) ^ (i * 11L) ^ 0xB02DL;
                    b1s = PaletteResolver.pick(world, pid, "ROAD_BORDER", b1, salt1, b1s);
                    b2s = PaletteResolver.pick(world, pid, "ROAD_BORDER", b2, salt2, b2s);
                }
                if (BuildConstraintContext.allow(b1)) out.add(new PlannedBlock(b1, b1s));
                if (BuildConstraintContext.allow(b2)) out.add(new PlannedBlock(b2, b2s));
            }

            // simple bridge rails: put fences at the edge if we're bridging
            if (bridge && w >= 2) {
                int ex = p.getX() + rx * (half + 1);
                int ez = p.getZ() + rz * (half + 1);
                int ex2 = p.getX() - rx * (half + 1);
                int ez2 = p.getZ() - rz * (half + 1);
                BlockPos r1 = new BlockPos(ex, p.getY() + 1, ez);
                BlockPos r2 = new BlockPos(ex2, p.getY() + 1, ez2);
                BlockState r1s = bridgeRail;
                BlockState r2s = bridgeRail;
                if (pid != null) {
                    long salt1 = ((long) r1.getX() * 31L) ^ ((long) r1.getZ() * 17L) ^ (i * 23L) ^ 0x7A11L;
                    long salt2 = ((long) r2.getX() * 31L) ^ ((long) r2.getZ() * 17L) ^ (i * 23L) ^ 0x7A12L;
                    r1s = PaletteResolver.pick(world, pid, "BRIDGE_RAIL", r1, salt1, r1s);
                    r2s = PaletteResolver.pick(world, pid, "BRIDGE_RAIL", r2, salt2, r2s);
                    // Back-compat: if palette doesn't define BRIDGE_RAIL, fall back to DECOR_DETAIL for variety.
                    r1s = PaletteResolver.pick(world, pid, "DECOR_DETAIL", r1, salt1 ^ 0xD3C0L, r1s);
                    r2s = PaletteResolver.pick(world, pid, "DECOR_DETAIL", r2, salt2 ^ 0xD3C0L, r2s);
                }
                if (BuildConstraintContext.allow(r1)) out.add(new PlannedBlock(r1, r1s));
                if (BuildConstraintContext.allow(r2)) out.add(new PlannedBlock(r2, r2s));
            }
        }

        return out;
    }

    /**
     * Foundation columns under road surface to prevent "露底" on steep slopes.
     *
     * Fills from (surfaceY-1) downward for each road cell within width, up to foundationDepth.
     * Only fills Air/Water/Lava (water/lava are optional).
     */
    public static List<PlannedBlock> foundationColumns(ServerWorld world,
                                                       List<BlockPos> center,
                                                       int width,
                                                       int foundationDepth,
                                                       BlockState fillMaterial,
                                                       boolean allowWater,
                                                       boolean allowLava) {
        if (world == null || center == null || center.isEmpty()) return List.of();
        int w = Math.max(1, width);
        int half = w / 2;
        int fd = Math.max(0, Math.min(32, foundationDepth));
        if (fd <= 0) return List.of();
        BlockState fill = fillMaterial != null ? fillMaterial : Blocks.COBBLESTONE.getDefaultState();

        List<PlannedBlock> out = new ArrayList<>(Math.max(256, center.size() * w * Math.min(4, fd)));

        for (int i = 0; i < center.size(); i++) {
            BlockPos p = center.get(i);
            BlockPos prev = (i > 0) ? center.get(i - 1) : null;
            BlockPos next = (i + 1 < center.size()) ? center.get(i + 1) : null;

            int dx = 0, dz = 0;
            if (next != null) {
                dx = Integer.compare(next.getX() - p.getX(), 0);
                dz = Integer.compare(next.getZ() - p.getZ(), 0);
            } else if (prev != null) {
                dx = Integer.compare(p.getX() - prev.getX(), 0);
                dz = Integer.compare(p.getZ() - prev.getZ(), 0);
            }
            if (dx == 0 && dz == 0) { dx = 1; dz = 0; }
            int rx = -dz;
            int rz = dx;

            for (int ww = -half; ww <= half; ww++) {
                int x2 = p.getX() + rx * ww;
                int z2 = p.getZ() + rz * ww;
                int y0 = p.getY() - 1;
                for (int k = 0; k < fd; k++) {
                    int y = y0 - k;
                    if (y <= world.getBottomY()) break;
                    BlockPos fp = new BlockPos(x2, y, z2);
                    if (!BuildConstraintContext.allow(fp)) continue;
                    BlockState cur = world.getBlockState(fp);
                    boolean isAir = cur.isAir();
                    boolean isWater = cur.getBlock() == Blocks.WATER;
                    boolean isLava = cur.getBlock() == Blocks.LAVA;
                    if (isAir || (allowWater && isWater) || (allowLava && isLava)) {
                        out.add(new PlannedBlock(fp, fill));
                    }
                }
            }
        }
        return out;
    }

    private static boolean isBridgeUnder(ServerWorld world, BlockPos p) {
        if (world == null || p == null) return false;
        BlockState below = world.getBlockState(p.down());
        return !below.getFluidState().isEmpty() || below.isAir();
    }

    private static BlockState asStairsIfPossible(BlockState base, Direction facing) {
        if (base == null) return Blocks.STONE_BRICK_STAIRS.getDefaultState().with(Properties.HORIZONTAL_FACING, facing);
        if (base.getBlock() instanceof StairsBlock) {
            if (base.contains(Properties.HORIZONTAL_FACING)) return base.with(Properties.HORIZONTAL_FACING, facing);
            return base;
        }
        // If the selected road material isn't stairs, use stone brick stairs as a safe default.
        return Blocks.STONE_BRICK_STAIRS.getDefaultState().with(Properties.HORIZONTAL_FACING, facing);
    }

    private static Direction dirFromDelta(int dx, int dz) {
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}


