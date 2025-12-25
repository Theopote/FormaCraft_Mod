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
        boolean crenels = getBool(params, "crenels", true);
        boolean followTerrain = getBool(params, "followTerrain", true);

        Direction facing = parseFacing(String.valueOf(params.get("facing") == null ? "EAST" : params.get("facing")));

        List<BlockPos> pts = new ArrayList<>(length + 1);
        for (int i = 0; i <= length; i++) {
            int dx = facing.getOffsetX() * i;
            int dz = facing.getOffsetZ() * i;
            int y = origin.getY();
            if (followTerrain) {
                BlockPos sample = origin.add(dx, 0, dz);
                int top = world.getTopY(net.minecraft.world.Heightmap.Type.WORLD_SURFACE, sample.getX(), sample.getZ());
                y = Math.max(y, top - 1);
            }
            pts.add(new BlockPos(origin.getX() + dx, y, origin.getZ() + dz));
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

    private static boolean getBool(SkeletonParams p, String key, boolean def) {
        Object v = p.get(key);
        if (v instanceof Boolean b) return b;
        if (v == null) return def;
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.isEmpty()) return def;
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
    }

    private static Direction parseFacing(String s) {
        String v = (s == null ? "" : s).trim().toUpperCase();
        return switch (v) {
            case "N", "NORTH", "北", "朝北" -> Direction.NORTH;
            case "S", "SOUTH", "南", "朝南" -> Direction.SOUTH;
            case "E", "EAST", "东", "朝东" -> Direction.EAST;
            case "W", "WEST", "西", "朝西" -> Direction.WEST;
            default -> Direction.EAST;
        };
    }
}


