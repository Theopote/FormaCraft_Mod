package com.formacraft.server.skeleton.rect;

import com.formacraft.common.skeleton.rect.RectEnclosurePlan;
import com.formacraft.server.build.BuildConstraintContext;
import com.formacraft.server.build.PlannedBlock;
import com.formacraft.server.skeleton.SkeletonInterpreter;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * RectEnclosureInterpreter: builds a perimeter wall around a rectangle.
 * Includes a gate opening centered on one side.
 */
public final class RectEnclosureInterpreter implements SkeletonInterpreter<RectEnclosurePlan> {
    private final BlockState wall;
    private final BlockState cap;
    private final BlockState cap2;
    private final int capLayers;
    private final int capOverhang;
    private final BlockState gatePillar;
    private final boolean openArcadeGate;

    public RectEnclosureInterpreter(BlockState wall, BlockState cap) {
        this.wall = wall;
        this.cap = cap;
        this.cap2 = cap;
        this.capLayers = 1;
        this.capOverhang = 0;
        this.gatePillar = wall;
        this.openArcadeGate = false;
    }

    /**
     * Style-aware constructor (v1):
     * - If openArcadeGate=true, gate opening becomes a framed arcade (pillars at edges).
     */
    public RectEnclosureInterpreter(BlockState wall, BlockState cap, BlockState gatePillar, boolean openArcadeGate) {
        this.wall = wall;
        this.cap = cap;
        this.cap2 = cap;
        this.capLayers = 1;
        this.capOverhang = 0;
        this.gatePillar = gatePillar != null ? gatePillar : wall;
        this.openArcadeGate = openArcadeGate;
    }

    /**
     * Style-aware constructor (v2):
     * - capLayers>=2 adds an extra coping band below the cap line using cap2.
     */
    public RectEnclosureInterpreter(BlockState wall, BlockState cap, BlockState cap2, int capLayers, BlockState gatePillar, boolean openArcadeGate) {
        this.wall = wall;
        this.cap = cap;
        this.cap2 = cap2 != null ? cap2 : cap;
        this.capLayers = Math.max(1, Math.min(3, capLayers));
        this.capOverhang = 0;
        this.gatePillar = gatePillar != null ? gatePillar : wall;
        this.openArcadeGate = openArcadeGate;
    }

    /**
     * Style-aware constructor (v3):
     * - capLayers>=2 adds an extra coping band below the cap line using cap2.
     * - capOverhang>0 expands cap/cap2 outward by N blocks (currently clamped to 0..1 by caller).
     */
    public RectEnclosureInterpreter(BlockState wall, BlockState cap, BlockState cap2, int capLayers, int capOverhang,
                                   BlockState gatePillar, boolean openArcadeGate) {
        this.wall = wall;
        this.cap = cap;
        this.cap2 = cap2 != null ? cap2 : cap;
        this.capLayers = Math.max(1, Math.min(3, capLayers));
        this.capOverhang = Math.max(0, Math.min(1, capOverhang));
        this.gatePillar = gatePillar != null ? gatePillar : wall;
        this.openArcadeGate = openArcadeGate;
    }

    @Override
    public List<PlannedBlock> interpret(RectEnclosurePlan plan, BlockPos origin, ServerWorld world) {
        int w = Math.max(7, plan.width);
        int d = Math.max(7, plan.depth);
        int h = Math.max(2, plan.wallHeight);
        int t = Math.max(1, plan.thickness);

        int halfW = w / 2;
        int halfD = d / 2;

        int gateW = Math.max(1, plan.gateWidth);
        int gateHalf = gateW / 2;

        List<PlannedBlock> blocks = new ArrayList<>(Math.max(2000, (w + d) * h * t));
        int gateClearH = Math.min(h, openArcadeGate ? 4 : 2); // how tall the opening is carved

        // perimeter bands at thickness t
        for (int layer = 0; layer < t; layer++) {
            int xMin = -halfW + layer;
            int xMax = halfW - layer;
            int zMin = -halfD + layer;
            int zMax = halfD - layer;

            // north/south edges (z fixed)
            for (int x = xMin; x <= xMax; x++) {
                placeWallColumn(blocks, origin, x, zMin, h, gateClearH, plan, gateHalf); // north
                placeWallColumn(blocks, origin, x, zMax, h, gateClearH, plan, gateHalf); // south
            }
            // west/east edges (x fixed)
            for (int z = zMin; z <= zMax; z++) {
                placeWallColumn(blocks, origin, xMin, z, h, gateClearH, plan, gateHalf); // west
                placeWallColumn(blocks, origin, xMax, z, h, gateClearH, plan, gateHalf); // east
            }
        }

        // cap line (supports outward overhang)
        int capY = h;
        int oh = Math.max(0, capOverhang);
        int capXMin = -halfW - oh;
        int capXMax = halfW + oh;
        int capZMin = -halfD - oh;
        int capZMax = halfD + oh;

        for (int x = capXMin; x <= capXMax; x++) {
            BlockPos p1 = origin.add(x, capY, capZMin);
            if (BuildConstraintContext.allow(p1)) blocks.add(new PlannedBlock(p1, cap));
            BlockPos p2 = origin.add(x, capY, capZMax);
            if (BuildConstraintContext.allow(p2)) blocks.add(new PlannedBlock(p2, cap));
        }
        for (int z = capZMin; z <= capZMax; z++) {
            BlockPos p1 = origin.add(capXMin, capY, z);
            if (BuildConstraintContext.allow(p1)) blocks.add(new PlannedBlock(p1, cap));
            BlockPos p2 = origin.add(capXMax, capY, z);
            if (BuildConstraintContext.allow(p2)) blocks.add(new PlannedBlock(p2, cap));
        }

        // extra coping band below the cap (v2+) - follows the same overhang footprint
        if (capLayers >= 2) {
            int y2 = h - 1;
            if (y2 >= 0) {
                for (int x = capXMin; x <= capXMax; x++) {
                    BlockPos p1 = origin.add(x, y2, capZMin);
                    if (BuildConstraintContext.allow(p1)) blocks.add(new PlannedBlock(p1, cap2));
                    BlockPos p2 = origin.add(x, y2, capZMax);
                    if (BuildConstraintContext.allow(p2)) blocks.add(new PlannedBlock(p2, cap2));
                }
                for (int z = capZMin; z <= capZMax; z++) {
                    BlockPos p1 = origin.add(capXMin, y2, z);
                    if (BuildConstraintContext.allow(p1)) blocks.add(new PlannedBlock(p1, cap2));
                    BlockPos p2 = origin.add(capXMax, y2, z);
                    if (BuildConstraintContext.allow(p2)) blocks.add(new PlannedBlock(p2, cap2));
                }
            }
        }

        return blocks;
    }

    private void placeWallColumn(List<PlannedBlock> blocks, BlockPos origin, int x, int z, int h, int gateClearH,
                                 RectEnclosurePlan plan, int gateHalf) {
        if (isGateOpening(x, z, plan, gateHalf)) {
            // v1: carve opening; if openArcadeGate, keep pillars on the edges of the opening.
            boolean edge = isGateEdgeColumn(x, z, plan, gateHalf);
            for (int y = 1; y <= gateClearH; y++) {
                BlockPos p = origin.add(x, y, z);
                if (!BuildConstraintContext.allow(p)) continue;
                if (openArcadeGate && edge) {
                    blocks.add(new PlannedBlock(p, gatePillar));
                } else {
                    blocks.add(new PlannedBlock(p, Blocks.AIR.getDefaultState()));
                }
            }
            return;
        }
        for (int y = 0; y <= h; y++) {
            BlockPos p = origin.add(x, y, z);
            if (BuildConstraintContext.allow(p)) blocks.add(new PlannedBlock(p, wall));
        }
    }

    private boolean isGateOpening(int x, int z, RectEnclosurePlan plan, int gateHalf) {
        Direction side = plan.gateSide;
        // gate at center of selected side
        return switch (side) {
            case SOUTH -> z > 0 && Math.abs(x) <= gateHalf;
            case NORTH -> z < 0 && Math.abs(x) <= gateHalf;
            case EAST -> x > 0 && Math.abs(z) <= gateHalf;
            case WEST -> x < 0 && Math.abs(z) <= gateHalf;
            default -> z > 0 && Math.abs(x) <= gateHalf;
        };
    }

    private boolean isGateEdgeColumn(int x, int z, RectEnclosurePlan plan, int gateHalf) {
        Direction side = plan.gateSide;
        return switch (side) {
            case SOUTH, NORTH -> Math.abs(x) == gateHalf;
            case EAST, WEST -> Math.abs(z) == gateHalf;
            default -> Math.abs(x) == gateHalf;
        };
    }
}


