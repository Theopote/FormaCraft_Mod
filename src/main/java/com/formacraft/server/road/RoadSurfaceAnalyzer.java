package com.formacraft.server.road;

import com.formacraft.server.build.BuildConstraintContext;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

/**
 * RoadSurfaceAnalyzer:
 * - samples "walkable surface" height for a given XZ
 * - classifies whether it's bridge-worthy (water/gap), and whether it is blocked by constraints/solids
 */
public final class RoadSurfaceAnalyzer {
    private final ServerWorld world;
    private final Heightmap.Type heightmap;
    private final int clearHeight;
    private final int maxStep; // max allowed dy between adjacent nodes

    public RoadSurfaceAnalyzer(ServerWorld world, int clearHeight, int maxStep) {
        this.world = world;
        this.heightmap = Heightmap.Type.MOTION_BLOCKING_NO_LEAVES;
        this.clearHeight = Math.max(0, clearHeight);
        this.maxStep = Math.max(1, maxStep);
    }

    public int surfaceY(int x, int z) {
        return world.getTopY(heightmap, x, z);
    }

    public SurfaceInfo sample(int x, int z) {
        int y = surfaceY(x, z);
        BlockPos p = new BlockPos(x, y, z);

        // hard constraints (selection/outline/protected zones)
        if (!BuildConstraintContext.allow(p)) return new SurfaceInfo(p, RoadSurface.BLOCKED, 0);

        // clearance check: ensure enough headroom for walking
        for (int i = 1; i <= clearHeight; i++) {
            BlockPos up = p.up(i);
            if (!BuildConstraintContext.allow(up)) return new SurfaceInfo(p, RoadSurface.BLOCKED, 0);
        }

        // If the road block would replace something unbreakable? keep it simple: avoid bedrock.
        BlockState at = world.getBlockState(p);
        if (at.isOf(net.minecraft.block.Blocks.BEDROCK)) return new SurfaceInfo(p, RoadSurface.BLOCKED, 0);

        BlockState below = world.getBlockState(p.down());
        boolean isWater = !below.getFluidState().isEmpty();
        boolean isGap = below.isAir();
        int localSlope = estimateLocalSlope(y, x, z);
        if (isWater || isGap) return new SurfaceInfo(p, RoadSurface.BRIDGE, localSlope);

        return new SurfaceInfo(p, RoadSurface.GROUND, localSlope);
    }

    public int maxStep() {
        return maxStep;
    }

    /**
     * localSlope: average abs height delta to 4-neighbors (0..n).
     * Larger => more rugged terrain around this point.
     */
    private int estimateLocalSlope(int y, int x, int z) {
        int yE = surfaceY(x + 1, z);
        int yW = surfaceY(x - 1, z);
        int yS = surfaceY(x, z + 1);
        int yN = surfaceY(x, z - 1);
        int sum = Math.abs(y - yE) + Math.abs(y - yW) + Math.abs(y - yS) + Math.abs(y - yN);
        return sum / 4;
    }

    public record SurfaceInfo(BlockPos pos, RoadSurface surface, int localSlope) {}
}


