package com.formacraft.server.skeleton.path;

import com.formacraft.common.skeleton.path.PolylinePathPlan;
import com.formacraft.server.build.BuildConstraintContext;
import com.formacraft.server.build.PlannedBlock;
import com.formacraft.server.material.PaletteResolver;
import com.formacraft.server.skeleton.SkeletonInterpreter;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * PathRoadInterpreter: interprets a polyline into a simple road surface (with optional lamps).
 * Uses Bresenham on XZ, optionally snaps Y to WORLD_SURFACE.
 */
public final class PathRoadInterpreter implements SkeletonInterpreter<PolylinePathPlan> {
    private final BlockState road;
    private final BlockState border;
    private final boolean useBorder;
    private final String paletteId;
    private final BlockState lamp;
    private final BlockState lampPost;
    private final String ornamentProfile;
    private final boolean clearHeadroom;
    private final int clearHeight;

    public PathRoadInterpreter(BlockState road, BlockState border, boolean useBorder) {
        this(road, border, useBorder, null, null, null);
    }

    public PathRoadInterpreter(BlockState road, BlockState border, boolean useBorder, String paletteId, BlockState lamp, BlockState lampPost) {
        this(road, border, useBorder, paletteId, lamp, lampPost, null);
    }

    public PathRoadInterpreter(BlockState road, BlockState border, boolean useBorder, String paletteId, BlockState lamp, BlockState lampPost, String ornamentProfile) {
        this(road, border, useBorder, paletteId, lamp, lampPost, ornamentProfile, false, 2);
    }

    public PathRoadInterpreter(BlockState road,
                               BlockState border,
                               boolean useBorder,
                               String paletteId,
                               BlockState lamp,
                               BlockState lampPost,
                               String ornamentProfile,
                               boolean clearHeadroom,
                               int clearHeight) {
        this.road = road;
        this.border = border;
        this.useBorder = useBorder;
        this.paletteId = (paletteId == null || paletteId.isBlank()) ? null : paletteId.trim();
        this.lamp = lamp;
        this.lampPost = lampPost;
        this.ornamentProfile = (ornamentProfile == null || ornamentProfile.isBlank()) ? null : ornamentProfile.trim().toLowerCase(java.util.Locale.ROOT);
        this.clearHeadroom = clearHeadroom;
        this.clearHeight = Math.max(0, clearHeight);
    }

    @Override
    public List<PlannedBlock> interpret(PolylinePathPlan plan, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> out = new ArrayList<>(Math.max(1000, plan.points.size() * plan.width * 20));
        if (plan.points == null || plan.points.size() < 2) return out;

        int half = plan.width / 2;
        int stepCounter = 0;

        for (int i = 0; i < plan.points.size() - 1; i++) {
            BlockPos aRel = plan.points.get(i);
            BlockPos bRel = plan.points.get(i + 1);
            BlockPos a = origin.add(aRel);
            BlockPos b = origin.add(bRel);
            List<BlockPos> line = bresenhamXZ(a, b);

            for (BlockPos p : line) {
                int y = p.getY();
                if (plan.followTerrain) {
                    // Prefer a walkable ground height that ignores leaves, so roads don't sit on tree canopies.
                    int top = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
                    // 路/道默认应“贴地”而不是只抬高：直接使用地表高度（避免悬空/桥梁式道路）
                    y = top;
                }

                // determine perpendicular direction from segment direction (cardinal-ish)
                int dx = Integer.compare(b.getX() - a.getX(), 0);
                int dz = Integer.compare(b.getZ() - a.getZ(), 0);
                int rx = -dz;
                int rz = dx;
                if (rx == 0 && rz == 0) { rx = 1; rz = 0; }

                for (int w = -half; w <= half; w++) {
                    int x2 = p.getX() + rx * w;
                    int z2 = p.getZ() + rz * w;
                    BlockPos bp = new BlockPos(x2, y, z2);
                    if (BuildConstraintContext.allow(bp)) {
                        BlockState rs = road;
                        if (paletteId != null) {
                            long salt = ((long) x2 * 31L) ^ ((long) z2 * 17L) ^ ((long) w * 13L) ^ (stepCounter * 7L);
                            rs = PaletteResolver.pick(world, paletteId, "ROAD_SURFACE", bp, salt, rs);
                        }
                        out.add(new PlannedBlock(bp, rs));
                        if (clearHeadroom && clearHeight > 0) {
                            for (int h = 1; h <= clearHeight; h++) {
                                BlockPos ap = bp.up(h);
                                if (BuildConstraintContext.allow(ap)) out.add(new PlannedBlock(ap, Blocks.AIR.getDefaultState()));
                            }
                        }
                    }
                }
                if (useBorder) {
                    BlockPos b1 = new BlockPos(p.getX() + rx * (half + 1), y, p.getZ() + rz * (half + 1));
                    if (BuildConstraintContext.allow(b1)) {
                        BlockState bs = border;
                        if (paletteId != null) {
                            long salt = ((long) b1.getX() * 31L) ^ ((long) b1.getZ() * 17L) ^ (stepCounter * 11L) ^ 0xB01DL;
                            bs = PaletteResolver.pick(world, paletteId, "ROAD_BORDER", b1, salt, bs);
                        }
                        out.add(new PlannedBlock(b1, bs));
                    }
                    BlockPos b2 = new BlockPos(p.getX() - rx * (half + 1), y, p.getZ() - rz * (half + 1));
                    if (BuildConstraintContext.allow(b2)) {
                        BlockState bs = border;
                        if (paletteId != null) {
                            long salt = ((long) b2.getX() * 31L) ^ ((long) b2.getZ() * 17L) ^ (stepCounter * 11L) ^ 0xB02DL;
                            bs = PaletteResolver.pick(world, paletteId, "ROAD_BORDER", b2, salt, bs);
                        }
                        out.add(new PlannedBlock(b2, bs));
                    }
                }

                // lamps
                if (plan.lamps && (stepCounter % plan.lampInterval == 0)) {
                    int lx = p.getX() + rx * (half + 2);
                    int lz = p.getZ() + rz * (half + 2);
                    int ly = y + 1;
                    BlockPos lp = new BlockPos(lx, ly, lz);
                    BlockState lampState = (lamp != null) ? lamp : Blocks.LANTERN.getDefaultState();
                    if (paletteId != null) {
                        long salt = ((long) lx * 31L) ^ ((long) lz * 17L) ^ (stepCounter * 19L) ^ 0x11A17L;
                        lampState = PaletteResolver.pick(world, paletteId, "ROAD_LIGHT", lp, salt, lampState);
                    }
                    if (BuildConstraintContext.allow(lp)) out.add(new PlannedBlock(lp, lampState));
                    BlockPos post = new BlockPos(lx, ly - 1, lz);
                    BlockState postState = (lampPost != null) ? lampPost : Blocks.COBBLESTONE_WALL.getDefaultState();
                    if (paletteId != null) {
                        long salt = ((long) lx * 31L) ^ ((long) lz * 17L) ^ (stepCounter * 23L) ^ 0x9057L;
                        // Lamp post is a structural element first (beam/frame), then a detail.
                        postState = PaletteResolver.pick(world, paletteId, "STRUCTURAL_BEAM", post, salt ^ 0x51EEL, postState);
                        postState = PaletteResolver.pick(world, paletteId, "FRAME", post, salt ^ 0xF8A1L, postState);
                        postState = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", post, salt, postState);
                    }
                    if (BuildConstraintContext.allow(post)) out.add(new PlannedBlock(post, postState));
                }

                // roadside signage / ornaments (best-effort, low intrusion)
                // Put them on the opposite side of the lamp side to reduce collisions.
                if (ornamentProfile != null && paletteId != null) {
                    int interval = Math.max(10, plan.lampInterval * 2);
                    if (stepCounter % interval == 0) {
                        int sx = p.getX() - rx * (half + 2);
                        int sz = p.getZ() - rz * (half + 2);
                        BlockPos base = new BlockPos(sx, y + 1, sz);
                        // Support should be solid so the "signage block" above doesn't float too much visually.
                        BlockState support = border != null ? border : Blocks.STONE_BRICKS.getDefaultState();
                        long saltS = ((long) sx * 31L) ^ ((long) sz * 17L) ^ (stepCounter * 29L) ^ 0x516E0L;
                        support = PaletteResolver.pick(world, paletteId, "STRUCTURAL_BEAM", base, saltS ^ 0x51EEL, support);
                        support = PaletteResolver.pick(world, paletteId, "FRAME", base, saltS ^ 0xF8A1L, support);
                        support = PaletteResolver.pick(world, paletteId, "ROAD_BORDER", base, saltS, support);
                        if (BuildConstraintContext.allow(base)) out.add(new PlannedBlock(base, support));

                        BlockPos sigPos = base.up();
                        BlockState fallbackSign = Blocks.RED_WOOL.getDefaultState();
                        String op = ornamentProfile;
                        if (op.contains("cyber") || op.contains("sign")) {
                            fallbackSign = Blocks.GLOWSTONE.getDefaultState();
                        } else if (op.contains("banner")) {
                            fallbackSign = Blocks.RED_WOOL.getDefaultState();
                        } else if (op.contains("organic") || op.contains("lantern")) {
                            fallbackSign = Blocks.SHROOMLIGHT.getDefaultState();
                        } else if (op.contains("steam") || op.contains("pipe")) {
                            fallbackSign = Blocks.COPPER_BLOCK.getDefaultState();
                        } else if (op.contains("plaque") || op.contains("chinese")) {
                            fallbackSign = Blocks.DARK_OAK_PLANKS.getDefaultState();
                        }
                        long saltG = ((long) sx * 31L) ^ ((long) sz * 17L) ^ (stepCounter * 31L) ^ 0x516F1L;
                        BlockState signage = PaletteResolver.pick(world, paletteId, "ROAD_SIGNAGE", sigPos, saltG, fallbackSign);
                        if (BuildConstraintContext.allow(sigPos)) out.add(new PlannedBlock(sigPos, signage));
                    }
                }

                stepCounter++;
            }
        }

        return out;
    }

    private static List<BlockPos> bresenhamXZ(BlockPos a, BlockPos b) {
        List<BlockPos> pts = new ArrayList<>();
        int x0 = a.getX();
        int z0 = a.getZ();
        int x1 = b.getX();
        int z1 = b.getZ();
        int y = a.getY();

        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;

        int x = x0;
        int z = z0;
        while (true) {
            pts.add(new BlockPos(x, y, z));
            if (x == x1 && z == z1) break;
            int e2 = 2 * err;
            if (e2 > -dz) { err -= dz; x += sx; }
            if (e2 < dx) { err += dx; z += sz; }
        }
        return pts;
    }
}


