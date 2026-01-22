package com.formacraft.server.skeleton.linear;

import com.formacraft.common.skeleton.Skeleton;
import com.formacraft.common.skeleton.SkeletonParams;
import com.formacraft.common.skeleton.SkeletonType;
import com.formacraft.common.skeleton.linear.LinearPathPlan;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * LinearPathSkeleton: generates a simple linear path based on origin + facing + length.
 * Optionally follows terrain by sampling WORLD_SURFACE heightmap.
 */
public final class LinearPathSkeleton implements Skeleton<LinearPathPlan> {
    private final ServerWorld world;
    private final BlockPos origin;

    public LinearPathSkeleton(ServerWorld world, BlockPos origin) {
        this.world = world;
        this.origin = origin;
    }

    @Override
    public SkeletonType type() {
        return SkeletonType.LINEAR_PATH;
    }

    @Override
    public LinearPathPlan generate(SkeletonParams params) {
        int length = getInt(params, "length", 120, 20, 2000);
        int thickness = getInt(params, "thickness", 5, 3, 63);
        int height = getInt(params, "height", 10, 5, 120);
        int towerSpacing = getInt(params, "towerSpacing", 48, 8, 512);
        boolean crenels = getBool(params, "crenels");
        boolean followTerrain = getBool(params, "followTerrain");
        int maxStep = getInt(params, "maxStep", 0, 0, 8);

        Direction facing = parseFacing(String.valueOf(params.get("facing") == null ? "EAST" : params.get("facing")));

        int[] ground = new int[length + 1];
        int[] ys = new int[length + 1];
        for (int i = 0; i <= length; i++) {
            int dx = facing.getOffsetX() * i;
            int dz = facing.getOffsetZ() * i;
            int y = origin.getY();
            if (followTerrain) {
                BlockPos sample = origin.add(dx, 0, dz);
                int top = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, sample.getX(), sample.getZ());
                int gy = top - 1;
                ground[i] = gy;
                y = Math.max(y, gy);
            } else {
                ground[i] = y;
            }
            ys[i] = y;
        }

        if (followTerrain && maxStep > 0) {
            // Smooth the path to respect maxStep while never going below ground[i].
            // Iterate a few times to reduce bias.
            for (int it = 0; it < 3; it++) {
                // forward clamp
                for (int i = 1; i <= length; i++) {
                    int prev = ys[i - 1];
                    int cur = ys[i];
                    if (cur > prev + maxStep) cur = prev + maxStep;
                    if (cur < prev - maxStep) cur = prev - maxStep;
                    if (cur < ground[i]) cur = ground[i];
                    ys[i] = cur;
                }
                // backward clamp
                for (int i = length - 1; i >= 0; i--) {
                    int next = ys[i + 1];
                    int cur = ys[i];
                    if (cur > next + maxStep) cur = next + maxStep;
                    if (cur < next - maxStep) cur = next - maxStep;
                    if (cur < ground[i]) cur = ground[i];
                    ys[i] = cur;
                }
            }
        }

        List<BlockPos> pts = new ArrayList<>(length + 1);
        for (int i = 0; i <= length; i++) {
            int dx = facing.getOffsetX() * i;
            int dz = facing.getOffsetZ() * i;
            pts.add(new BlockPos(origin.getX() + dx, ys[i], origin.getZ() + dz));
        }

        return new LinearPathPlan(pts, thickness, height, towerSpacing, crenels);
    }

    private static int getInt(SkeletonParams p, String key, int def, int min, int max) {
        Object v = p.get(key);
        int n = def;
        try {
            if (v instanceof Number num) n = num.intValue();
            else if (v != null) n = Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception ignored) {}
        if (n < min) n = min;
        if (n > max) n = max;
        return n;
    }

    private static boolean getBool(SkeletonParams p, String key) {
        Object v = p.get(key);
        if (v instanceof Boolean b) return b;
        if (v == null) return true;
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.isEmpty()) return true;
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
    }

    private static Direction parseFacing(String s) {
        String v = (s == null ? "" : s).trim().toUpperCase();
        return switch (v) {
            case "N", "NORTH", "北", "朝北" -> Direction.NORTH;
            case "S", "SOUTH", "南", "朝南" -> Direction.SOUTH;
            case "W", "WEST", "西", "朝西" -> Direction.WEST;
            default -> Direction.EAST;
        };
    }
}


