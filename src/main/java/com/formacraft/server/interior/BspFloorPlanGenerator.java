package com.formacraft.server.interior;

import com.formacraft.server.build.PlannedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.StairsBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * BspFloorPlanGenerator (v1):
 * Core -> Corridor ring -> BSP Rooms -> Doors -> Core stairs.
 *
 * This is a "meta-assembly primitive": it doesn't generate the building shell,
 * only interior partitions/circulation elements.
 */
public final class BspFloorPlanGenerator {
    private BspFloorPlanGenerator() {}

    private enum DoorSide { NORTH, SOUTH, EAST, WEST }

    public record Rect(int x0, int z0, int x1, int z1) {
        public int w() { return x1 - x0 + 1; }
        public int d() { return z1 - z0 + 1; }
    }

    public record Materials(BlockState coreWall,
                            BlockState roomWall,
                            BlockState stairs) {
        public static Materials of(BlockState coreWall, BlockState roomWall, BlockState stairs) {
            return new Materials(coreWall, roomWall, stairs);
        }
    }

    public static void apply(List<PlannedBlock> blocks,
                             BlockPos origin,
                             ServerWorld world,
                             int w,
                             int d,
                             int h,
                             FloorPlanConfig cfg,
                             Materials mats) {
        if (blocks == null || origin == null || world == null || cfg == null || mats == null) return;
        if (w < 11 || d < 11 || h < 8) return;

        int halfW = w / 2;
        int halfD = d / 2;
        int xMin = -halfW + 1, xMax = halfW - 1;
        int zMin = -halfD + 1, zMax = halfD - 1;

        int corridorWidth = cfg.corridorWidth;
        int minRoom = cfg.roomMinSize;
        String style = cfg.partitionStyle != null ? cfg.partitionStyle : "OPEN_PLAN";
        double splitChance = style.contains("DENSE") ? 0.95 : 0.72;
        int maxDepth = style.contains("DENSE") ? 7 : 5;

        int coreW = cfg.coreW;
        int coreD = cfg.coreD;
        Rect core = new Rect(-coreW / 2, -coreD / 2, -coreW / 2 + coreW - 1, -coreD / 2 + coreD - 1);
        Rect ring = new Rect(core.x0 - corridorWidth, core.z0 - corridorWidth, core.x1 + corridorWidth, core.z1 + corridorWidth);
        ring = clampRect(ring, xMin + 1, zMin + 1, xMax - 1, zMax - 1);

        long seed = 0xC0DEB5F1L ^ ((long) origin.getX() * 31L) ^ ((long) origin.getZ() * 17L) ^ ((long) origin.getY() * 13L);
        seed ^= ((long) w * 131L) ^ ((long) d * 71L) ^ ((long) h * 19L);
        Random baseRng = new Random(seed);

        for (int y0 = 0; y0 <= h; y0 += 4) {
            int yTop = Math.min(h, y0 + 3);
            int wallY0 = y0 + 1;
            int wallY1 = Math.min(yTop, y0 + 3);
            if (wallY0 > wallY1) continue;

            Random rng = new Random(baseRng.nextLong() ^ (y0 * 1315423911L));

            // core perimeter walls
            placeRectRing(blocks, origin, core, wallY0, wallY1, mats.coreWall);

            // core stairs + shaft
            placeCoreStairs(blocks, origin, core, y0, h, mats.stairs, rng);

            // BSP rooms
            ArrayList<Rect> rooms = new ArrayList<>();
            if (ring.x0 - 1 >= xMin) splitRooms(new Rect(xMin, zMin, ring.x0 - 1, zMax), minRoom, rng, 0, maxDepth, splitChance, rooms);
            if (ring.x1 + 1 <= xMax) splitRooms(new Rect(ring.x1 + 1, zMin, xMax, zMax), minRoom, rng, 0, maxDepth, splitChance, rooms);
            if (ring.z0 - 1 >= zMin) splitRooms(new Rect(ring.x0, zMin, ring.x1, ring.z0 - 1), minRoom, rng, 0, maxDepth, splitChance, rooms);
            if (ring.z1 + 1 <= zMax) splitRooms(new Rect(ring.x0, ring.z1 + 1, ring.x1, zMax), minRoom, rng, 0, maxDepth, splitChance, rooms);

            for (Rect r : rooms) {
                placeRectRing(blocks, origin, r, wallY0, wallY1, mats.roomWall);
                DoorSide ds = chooseDoorSideTowardRing(r, ring);
                carveDoorToRing(blocks, origin, r, ring, ds, wallY0, Math.min(wallY1, wallY0 + 1), rng);
            }
        }
    }

    private static void splitRooms(Rect r, int minSize, Random rng, int depth, int maxDepth, double splitChance, List<Rect> out) {
        if (r.w() <= 0 || r.d() <= 0) return;
        if (depth >= maxDepth || r.w() < minSize * 2 || r.d() < minSize * 2 || (depth > 0 && rng.nextDouble() > splitChance)) {
            out.add(r);
            return;
        }

        boolean splitVertical;
        if (r.w() >= r.d() * 12 / 10) splitVertical = true;
        else if (r.d() >= r.w() * 12 / 10) splitVertical = false;
        else splitVertical = rng.nextBoolean();

        if (splitVertical) {
            int span = r.w();
            int cut = minSize + rng.nextInt(Math.max(1, span - (minSize * 2) + 1));
            int xLine = r.x0 + cut;
            splitRooms(new Rect(r.x0, r.z0, xLine - 1, r.z1), minSize, rng, depth + 1, maxDepth, splitChance, out);
            splitRooms(new Rect(xLine + 1, r.z0, r.x1, r.z1), minSize, rng, depth + 1, maxDepth, splitChance, out);
        } else {
            int span = r.d();
            int cut = minSize + rng.nextInt(Math.max(1, span - (minSize * 2) + 1));
            int zLine = r.z0 + cut;
            splitRooms(new Rect(r.x0, r.z0, r.x1, zLine - 1), minSize, rng, depth + 1, maxDepth, splitChance, out);
            splitRooms(new Rect(r.x0, zLine + 1, r.x1, r.z1), minSize, rng, depth + 1, maxDepth, splitChance, out);
        }
    }

    private static void placeRectRing(List<PlannedBlock> blocks, BlockPos origin, Rect r, int y0, int y1, BlockState wall) {
        if (r.w() <= 0 || r.d() <= 0) return;
        BlockState s = wall != null ? wall : Blocks.STONE_BRICKS.getDefaultState();
        for (int y = y0; y <= y1; y++) {
            for (int x = r.x0; x <= r.x1; x++) {
                blocks.add(new PlannedBlock(origin.add(x, y, r.z0), s));
                blocks.add(new PlannedBlock(origin.add(x, y, r.z1), s));
            }
            for (int z = r.z0; z <= r.z1; z++) {
                blocks.add(new PlannedBlock(origin.add(r.x0, y, z), s));
                blocks.add(new PlannedBlock(origin.add(r.x1, y, z), s));
            }
        }
    }

    private static DoorSide chooseDoorSideTowardRing(Rect room, Rect ring) {
        int dxW = Math.abs(room.x0 - ring.x1);
        int dxE = Math.abs(room.x1 - ring.x0);
        int dzN = Math.abs(room.z0 - ring.z1);
        int dzS = Math.abs(room.z1 - ring.z0);
        int best = Math.min(Math.min(dxW, dxE), Math.min(dzN, dzS));
        if (best == dxW) return DoorSide.WEST;
        if (best == dxE) return DoorSide.EAST;
        if (best == dzN) return DoorSide.NORTH;
        return DoorSide.SOUTH;
    }

    private static void carveDoorToRing(List<PlannedBlock> blocks,
                                        BlockPos origin,
                                        Rect room,
                                        Rect ring,
                                        DoorSide side,
                                        int y0,
                                        int y1,
                                        Random rng) {
        int x;
        int z;
        switch (side) {
            case WEST -> {
                x = room.x1;
                if (x < ring.x0 - 1 || x > ring.x1 + 1) return;
                z = clamp(room.z0 + 1 + rng.nextInt(Math.max(1, room.d() - 2)), room.z0 + 1, room.z1 - 1);
            }
            case EAST -> {
                x = room.x0;
                if (x < ring.x0 - 1 || x > ring.x1 + 1) return;
                z = clamp(room.z0 + 1 + rng.nextInt(Math.max(1, room.d() - 2)), room.z0 + 1, room.z1 - 1);
            }
            case NORTH -> {
                z = room.z1;
                if (z < ring.z0 - 1 || z > ring.z1 + 1) return;
                x = clamp(room.x0 + 1 + rng.nextInt(Math.max(1, room.w() - 2)), room.x0 + 1, room.x1 - 1);
            }
            case SOUTH -> {
                z = room.z0;
                if (z < ring.z0 - 1 || z > ring.z1 + 1) return;
                x = clamp(room.x0 + 1 + rng.nextInt(Math.max(1, room.w() - 2)), room.x0 + 1, room.x1 - 1);
            }
            default -> { return; }
        }
        for (int y = y0; y <= y1; y++) {
            blocks.add(new PlannedBlock(origin.add(x, y, z), Blocks.AIR.getDefaultState()));
        }
    }

    private static void placeCoreStairs(List<PlannedBlock> blocks,
                                        BlockPos origin,
                                        Rect core,
                                        int y0,
                                        int h,
                                        BlockState stairsBase,
                                        Random rng) {
        if (core.w() < 6 || core.d() < 6) return;

        int sx0 = core.x0 + 1;
        int sz0 = core.z0 + 1;

        // carve a 3x3 shaft for headroom + floor openings
        int clearY0 = y0 + 1;
        int clearY1 = Math.min(h + 2, y0 + 6);
        for (int x = sx0; x < sx0 + 3; x++) {
            for (int z = sz0; z < sz0 + 3; z++) {
                for (int y = clearY0; y <= clearY1; y++) {
                    blocks.add(new PlannedBlock(origin.add(x, y, z), Blocks.AIR.getDefaultState()));
                }
            }
        }

        int band = Math.max(0, y0 / 4);
        boolean flip = (band % 2) == 1;

        BlockState stairs = stairsBase != null ? stairsBase : Blocks.STONE_BRICK_STAIRS.getDefaultState();
        if (!(stairs.getBlock() instanceof StairsBlock)) stairs = Blocks.STONE_BRICK_STAIRS.getDefaultState();

        // small deterministic jitter in shaft
        int x1 = sx0, z1 = sz0;
        int x2 = sx0 + 1, z2 = sz0;
        int x3 = sx0 + 1, z3 = sz0 + 1;
        if (rng != null && rng.nextBoolean()) {
            if (flip) { x1 = sx0 + 1; x2 = sx0 + 1; x3 = sx0 + 2; }
            else { z1 = sz0 + 1; z2 = sz0 + 1; z3 = sz0 + 2; }
        }

        Direction f1 = flip ? Direction.SOUTH : Direction.EAST;
        Direction f2 = flip ? Direction.EAST : Direction.SOUTH;
        Direction f3 = flip ? Direction.EAST : Direction.SOUTH;

        placeStair(blocks, origin.add(x1, y0 + 1, z1), stairs, f1);
        placeStair(blocks, origin.add(x2, y0 + 2, z2), stairs, f2);
        placeStair(blocks, origin.add(x3, y0 + 3, z3), stairs, f3);

        if (y0 + 4 <= h) blocks.add(new PlannedBlock(origin.add(x3, y0 + 4, z3), Blocks.AIR.getDefaultState()));
    }

    private static void placeStair(List<PlannedBlock> blocks, BlockPos pos, BlockState stairBase, Direction facing) {
        BlockState s = stairBase != null ? stairBase : Blocks.STONE_BRICK_STAIRS.getDefaultState();
        if (s.contains(Properties.HORIZONTAL_FACING)) s = s.with(Properties.HORIZONTAL_FACING, facing);
        blocks.add(new PlannedBlock(pos, s));
        blocks.add(new PlannedBlock(pos.up(), Blocks.AIR.getDefaultState()));
        blocks.add(new PlannedBlock(pos.up(2), Blocks.AIR.getDefaultState()));
    }

    private static Rect clampRect(Rect r, int x0, int z0, int x1, int z1) {
        return new Rect(Math.max(x0, r.x0), Math.max(z0, r.z0), Math.min(x1, r.x1), Math.min(z1, r.z1));
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}


