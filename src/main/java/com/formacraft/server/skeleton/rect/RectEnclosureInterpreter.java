package com.formacraft.server.skeleton.rect;

import com.formacraft.common.skeleton.rect.RectEnclosurePlan;
import com.formacraft.server.build.BuildConstraintContext;
import com.formacraft.server.build.PlannedBlock;
import com.formacraft.server.material.PaletteResolver;
import com.formacraft.server.skeleton.SkeletonInterpreter;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
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
    private final String paletteId;

    public RectEnclosureInterpreter(BlockState wall, BlockState cap) {
        this.wall = wall;
        this.cap = cap;
        this.cap2 = cap;
        this.capLayers = 1;
        this.capOverhang = 0;
        this.gatePillar = wall;
        this.openArcadeGate = false;
        this.paletteId = null;
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
        this.paletteId = null;
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
        this.paletteId = null;
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
        this.paletteId = null;
    }

    /**
     * Palette-aware constructor (v4):
     * - If paletteId is present, wall blocks are picked from semantic part WALL_BASE with weighted randomness (deterministic per position).
     */
    public RectEnclosureInterpreter(BlockState wall, BlockState cap, BlockState cap2, int capLayers, int capOverhang,
                                    BlockState gatePillar, boolean openArcadeGate, String paletteId) {
        this.wall = wall;
        this.cap = cap;
        this.cap2 = cap2 != null ? cap2 : cap;
        this.capLayers = Math.max(1, Math.min(3, capLayers));
        this.capOverhang = Math.max(0, Math.min(1, capOverhang));
        this.gatePillar = gatePillar != null ? gatePillar : wall;
        this.openArcadeGate = openArcadeGate;
        this.paletteId = (paletteId == null || paletteId.isBlank()) ? null : paletteId.trim();
    }

    @Override
    public List<PlannedBlock> interpret(RectEnclosurePlan plan, BlockPos origin, ServerWorld world) {
        int w = Math.max(7, plan.width);
        int d = Math.max(7, plan.depth);
        int h = Math.max(2, plan.wallHeight);
        int t = Math.max(1, plan.thickness);

        int halfW = w / 2;
        int halfD = d / 2;

        int gateW = Math.max(0, plan.gateWidth);
        int gateHalf = gateW / 2;

        List<PlannedBlock> blocks = new ArrayList<>(Math.max(2000, (w + d) * h * t));
        int gateClearH = (gateW == 0) ? 0 : Math.min(h, openArcadeGate ? 4 : 2); // how tall the opening is carved

        // perimeter bands at thickness t
        for (int layer = 0; layer < t; layer++) {
            int xMin = -halfW + layer;
            int xMax = halfW - layer;
            int zMin = -halfD + layer;
            int zMax = halfD - layer;

            // north/south edges (z fixed)
            for (int x = xMin; x <= xMax; x++) {
                placeWallColumn(blocks, origin, world, x, zMin, h, gateClearH, plan, gateHalf); // north
                placeWallColumn(blocks, origin, world, x, zMax, h, gateClearH, plan, gateHalf); // south
            }
            // west/east edges (x fixed)
            for (int z = zMin; z <= zMax; z++) {
                placeWallColumn(blocks, origin, world, xMin, z, h, gateClearH, plan, gateHalf); // west
                placeWallColumn(blocks, origin, world, xMax, z, h, gateClearH, plan, gateHalf); // east
            }
        }

        // cap line (supports outward overhang)
        int oh = Math.max(0, capOverhang);
        int capXMin = -halfW - oh;
        int capXMax = halfW + oh;
        int capZMin = -halfD - oh;
        int capZMax = halfD + oh;

        for (int x = capXMin; x <= capXMax; x++) {
            BlockPos p1 = origin.add(x, h, capZMin);
            if (BuildConstraintContext.allow(p1)) blocks.add(new PlannedBlock(p1, cap));
            BlockPos p2 = origin.add(x, h, capZMax);
            if (BuildConstraintContext.allow(p2)) blocks.add(new PlannedBlock(p2, cap));
        }
        for (int z = capZMin; z <= capZMax; z++) {
            BlockPos p1 = origin.add(capXMin, h, z);
            if (BuildConstraintContext.allow(p1)) blocks.add(new PlannedBlock(p1, cap));
            BlockPos p2 = origin.add(capXMax, h, z);
            if (BuildConstraintContext.allow(p2)) blocks.add(new PlannedBlock(p2, cap));
        }

        // extra coping band below the cap (v2+) - follows the same overhang footprint
        if (capLayers >= 2) {
            int y2 = h - 1;
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

        // battlements (crenels) on top edge (optional)
        if (plan.battlements) {
            int by = h + 1;
            int spacing = Math.max(1, plan.battlementSpacing);
            BlockState crenel = Blocks.STONE_BRICK_WALL.getDefaultState();

            // Use outer perimeter (layer=0) for crenels
            int xMin = -halfW;
            int zMin = -halfD;

            int idx = 0;
            // north edge
            for (int x = xMin; x <= halfW; x++) {
                if ((idx++ % spacing) != 0) continue;
                if (plan.gateWidth > 0 && isGateOpening(x, zMin, plan, gateHalf)) continue;
                BlockPos p = origin.add(x, by, zMin);
                if (!BuildConstraintContext.allow(p)) continue;
                BlockState st = crenel;
                if (paletteId != null) {
                    long salt = (x * 31L) ^ (zMin * 17L) ^ (by * 13L);
                    st = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", p, salt, crenel);
                }
                blocks.add(new PlannedBlock(p, st));
            }
            // south edge
            for (int x = xMin; x <= halfW; x++) {
                if ((idx++ % spacing) != 0) continue;
                if (plan.gateWidth > 0 && isGateOpening(x, halfD, plan, gateHalf)) continue;
                BlockPos p = origin.add(x, by, halfD);
                if (!BuildConstraintContext.allow(p)) continue;
                BlockState st = crenel;
                if (paletteId != null) {
                    long salt = (x * 31L) ^ (halfD * 17L) ^ (by * 13L);
                    st = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", p, salt, crenel);
                }
                blocks.add(new PlannedBlock(p, st));
            }
            // west edge
            for (int z = zMin; z <= halfD; z++) {
                if ((idx++ % spacing) != 0) continue;
                if (plan.gateWidth > 0 && isGateOpening(xMin, z, plan, gateHalf)) continue;
                BlockPos p = origin.add(xMin, by, z);
                if (!BuildConstraintContext.allow(p)) continue;
                BlockState st = crenel;
                if (paletteId != null) {
                    long salt = (xMin * 31L) ^ (z * 17L) ^ (by * 13L);
                    st = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", p, salt, crenel);
                }
                blocks.add(new PlannedBlock(p, st));
            }
            // east edge
            for (int z = zMin; z <= halfD; z++) {
                if ((idx++ % spacing) != 0) continue;
                if (plan.gateWidth > 0 && isGateOpening(halfW, z, plan, gateHalf)) continue;
                BlockPos p = origin.add(halfW, by, z);
                if (!BuildConstraintContext.allow(p)) continue;
                BlockState st = crenel;
                if (paletteId != null) {
                    long salt = (halfW * 31L) ^ (z * 17L) ^ (by * 13L);
                    st = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", p, salt, crenel);
                }
                blocks.add(new PlannedBlock(p, st));
            }
        }

        // banners near the gate (optional, recognizable landmark detail)
        if (plan.banner && plan.gateWidth > 0) {
            addGateBanners(blocks, plan, origin, world, halfW, halfD, h, gateClearH, gateHalf);
        }

        return blocks;
    }

    private void addGateBanners(List<PlannedBlock> blocks, RectEnclosurePlan plan, BlockPos origin, ServerWorld world,
                                int halfW, int halfD, int h, int gateClearH, int gateHalf) {
        // Place 2 banners flanking the gate opening, on the inside face.
        int y = Math.max(2, Math.min(h - 1, gateClearH + 1));
        int margin = 1;

        String c = (plan.bannerColor == null || plan.bannerColor.isBlank()) ? "red" : plan.bannerColor.trim().toLowerCase();
        String id = "minecraft:" + c + "_wall_banner";
        if (!c.matches("^[a-z_]{3,20}$")) id = "minecraft:red_wall_banner";
        BlockState banner = PaletteResolver.stateFromId(world, id);
        if (banner == null) banner = PaletteResolver.stateFromId(world, "minecraft:red_wall_banner");
        if (banner == null) banner = Blocks.RED_WOOL.getDefaultState();

        Direction faceToCenter = plan.gateSide.getOpposite();
        if (banner.contains(Properties.HORIZONTAL_FACING)) {
            banner = banner.with(Properties.HORIZONTAL_FACING, faceToCenter);
        }

        switch (plan.gateSide) {
            case SOUTH -> {
                int zWall = halfD;
                int zInside = zWall - 1;
                int x0 = -(gateHalf + margin);
                int x1 = (gateHalf + margin);
                BlockPos p0 = origin.add(x0, y, zInside);
                BlockPos p1 = origin.add(x1, y, zInside);
                if (BuildConstraintContext.allow(p0)) blocks.add(new PlannedBlock(p0, banner));
                if (BuildConstraintContext.allow(p1)) blocks.add(new PlannedBlock(p1, banner));
            }
            case NORTH -> {
                int zWall = -halfD;
                int zInside = zWall + 1;
                int x0 = -(gateHalf + margin);
                int x1 = (gateHalf + margin);
                BlockPos p0 = origin.add(x0, y, zInside);
                BlockPos p1 = origin.add(x1, y, zInside);
                if (BuildConstraintContext.allow(p0)) blocks.add(new PlannedBlock(p0, banner));
                if (BuildConstraintContext.allow(p1)) blocks.add(new PlannedBlock(p1, banner));
            }
            case EAST -> {
                int xWall = halfW;
                int xInside = xWall - 1;
                int z0 = -(gateHalf + margin);
                int z1 = (gateHalf + margin);
                BlockPos p0 = origin.add(xInside, y, z0);
                BlockPos p1 = origin.add(xInside, y, z1);
                if (BuildConstraintContext.allow(p0)) blocks.add(new PlannedBlock(p0, banner));
                if (BuildConstraintContext.allow(p1)) blocks.add(new PlannedBlock(p1, banner));
            }
            case WEST -> {
                int xWall = -halfW;
                int xInside = xWall + 1;
                int z0 = -(gateHalf + margin);
                int z1 = (gateHalf + margin);
                BlockPos p0 = origin.add(xInside, y, z0);
                BlockPos p1 = origin.add(xInside, y, z1);
                if (BuildConstraintContext.allow(p0)) blocks.add(new PlannedBlock(p0, banner));
                if (BuildConstraintContext.allow(p1)) blocks.add(new PlannedBlock(p1, banner));
            }
            default -> {}
        }
    }

    private void placeWallColumn(List<PlannedBlock> blocks, BlockPos origin, ServerWorld world, int x, int z, int h, int gateClearH,
                                 RectEnclosurePlan plan, int gateHalf) {
        if (gateHalf >= 0 && plan.gateWidth > 0 && isGateOpening(x, z, plan, gateHalf)) {
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
            if (BuildConstraintContext.allow(p)) {
                BlockState st = wall;
                if (paletteId != null) {
                    long salt = (x * 1315423911L) ^ (z * 2654435761L) ^ (y * 97531L);
                    st = PaletteResolver.pick(world, paletteId, "WALL_BASE", p, salt, wall);
                }
                blocks.add(new PlannedBlock(p, st));
            }
        }
    }

    private boolean isGateOpening(int x, int z, RectEnclosurePlan plan, int gateHalf) {
        Direction side = plan.gateSide;
        // gate at center of selected side
        return switch (side) {
            case NORTH -> z < 0 && Math.abs(x) <= gateHalf;
            case EAST -> x > 0 && Math.abs(z) <= gateHalf;
            case WEST -> x < 0 && Math.abs(z) <= gateHalf;
            default -> z > 0 && Math.abs(x) <= gateHalf;
        };
    }

    private boolean isGateEdgeColumn(int x, int z, RectEnclosurePlan plan, int gateHalf) {
        Direction side = plan.gateSide;
        return switch (side) {
            case EAST, WEST -> Math.abs(z) == gateHalf;
            default -> Math.abs(x) == gateHalf;
        };
    }
}


