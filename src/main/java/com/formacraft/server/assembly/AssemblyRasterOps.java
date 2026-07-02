package com.formacraft.server.assembly;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 2D line rasterization helpers used by MetaAssemblyEngine (profile/beam ops).
 */
public final class AssemblyRasterOps {
    private AssemblyRasterOps() {}

    private static final int[] ZERO_I3 = new int[]{0, 0, 0};

    public static int[] parsePoint(Object v) {
        if (v instanceof Map<?, ?> m) {
            return new int[]{
                    AssemblyValueParser.i(m.get("x"), 0),
                    AssemblyValueParser.i(m.get("y"), 0),
                    AssemblyValueParser.i(m.get("z"), 0)
            };
        }
        return ZERO_I3;
    }

    public static List<BlockPos> rasterizeLine2D(
            BlockPos a,
            BlockPos b,
            ServerWorld world,
            boolean followTerrain,
            int maxStep
    ) {
        // Bresenham in XZ; Y is taken from a/b (or terrain if followTerrain)
        int x0 = a.getX(), z0 = a.getZ();
        int x1 = b.getX(), z1 = b.getZ();
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;

        List<BlockPos> pts = new ArrayList<>(Math.max(dx, dz) + 1);
        List<Integer> ground = new ArrayList<>();

        int x = x0, z = z0;
        while (true) {
            int y = a.getY();
            int gy = y;
            if (followTerrain && world != null) {
                int top = world.getTopY(net.minecraft.world.Heightmap.Type.WORLD_SURFACE, x, z);
                gy = top - 1;
                y = Math.max(y, gy);
            }
            pts.add(new BlockPos(x, y, z));
            ground.add(gy);
            if (x == x1 && z == z1) break;
            int e2 = 2 * err;
            if (e2 > -dz) { err -= dz; x += sx; }
            if (e2 < dx) { err += dx; z += sz; }
        }

        if (followTerrain && maxStep > 0 && pts.size() > 2) {
            int n = pts.size();
            int[] ys = new int[n];
            for (int i = 0; i < n; i++) ys[i] = pts.get(i).getY();
            for (int it = 0; it < 3; it++) {
                for (int i = 1; i < n; i++) {
                    int prev = ys[i - 1];
                    int cur = ys[i];
                    if (cur > prev + maxStep) cur = prev + maxStep;
                    if (cur < prev - maxStep) cur = prev - maxStep;
                    if (cur < ground.get(i)) cur = ground.get(i);
                    ys[i] = cur;
                }
                for (int i = n - 2; i >= 0; i--) {
                    int next = ys[i + 1];
                    int cur = ys[i];
                    if (cur > next + maxStep) cur = next + maxStep;
                    if (cur < next - maxStep) cur = next - maxStep;
                    if (cur < ground.get(i)) cur = ground.get(i);
                    ys[i] = cur;
                }
            }
            for (int i = 0; i < n; i++) {
                BlockPos p = pts.get(i);
                pts.set(i, new BlockPos(p.getX(), ys[i], p.getZ()));
            }
        }
        return pts;
    }
}

