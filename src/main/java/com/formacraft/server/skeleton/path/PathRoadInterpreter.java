package com.formacraft.server.skeleton.path;

import com.formacraft.common.skeleton.path.PolylinePathPlan;
import com.formacraft.server.build.BuildConstraintContext;
import com.formacraft.server.build.PlannedBlock;
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

    public PathRoadInterpreter(BlockState road, BlockState border, boolean useBorder) {
        this.road = road;
        this.border = border;
        this.useBorder = useBorder;
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
                    int top = world.getTopY(net.minecraft.world.Heightmap.Type.WORLD_SURFACE, p.getX(), p.getZ());
                    y = Math.max(y, top);
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
                    if (BuildConstraintContext.allow(bp)) out.add(new PlannedBlock(bp, road));
                }
                if (useBorder) {
                    BlockPos b1 = new BlockPos(p.getX() + rx * (half + 1), y, p.getZ() + rz * (half + 1));
                    if (BuildConstraintContext.allow(b1)) out.add(new PlannedBlock(b1, border));
                    BlockPos b2 = new BlockPos(p.getX() - rx * (half + 1), y, p.getZ() - rz * (half + 1));
                    if (BuildConstraintContext.allow(b2)) out.add(new PlannedBlock(b2, border));
                }

                // lamps
                if (plan.lamps && (stepCounter % plan.lampInterval == 0)) {
                    int lx = p.getX() + rx * (half + 2);
                    int lz = p.getZ() + rz * (half + 2);
                    int ly = y + 1;
                    BlockPos lp = new BlockPos(lx, ly, lz);
                    if (BuildConstraintContext.allow(lp)) out.add(new PlannedBlock(lp, Blocks.LANTERN.getDefaultState()));
                    BlockPos post = new BlockPos(lx, ly - 1, lz);
                    if (BuildConstraintContext.allow(post)) out.add(new PlannedBlock(post, Blocks.COBBLESTONE_WALL.getDefaultState()));
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


