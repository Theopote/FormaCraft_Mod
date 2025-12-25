package com.formacraft.server.skeleton.grid;

import com.formacraft.common.skeleton.Skeleton;
import com.formacraft.common.skeleton.SkeletonParams;
import com.formacraft.common.skeleton.SkeletonType;
import com.formacraft.common.skeleton.grid.GridPlan;
import com.formacraft.common.skeleton.transform.BlockTransform;

/**
 * GridSkeleton: computes placements for a repeated module.
 * Origin convention: center of the whole grid at (0,0,0).
 */
public final class GridSkeleton implements Skeleton<GridPlan> {
    private final GridPlan plan;

    public GridSkeleton(GridPlan plan) {
        this.plan = plan;
    }

    @Override
    public SkeletonType type() {
        return SkeletonType.GRID;
    }

    @Override
    public GridPlan generate(SkeletonParams params) {
        int rows = getInt(params, "rows", 3, 1, 32);
        int cols = getInt(params, "cols", 4, 1, 32);
        int spacingX = getInt(params, "spacingX", 14, 6, 128);
        int spacingZ = getInt(params, "spacingZ", 14, 6, 128);

        // center the grid around origin
        int x0 = -((cols - 1) * spacingX) / 2;
        int z0 = -((rows - 1) * spacingZ) / 2;

        plan.placements.clear();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int dx = x0 + c * spacingX;
                int dz = z0 + r * spacingZ;
                plan.placements.add(BlockTransform.translate(dx, 0, dz));
            }
        }
        return plan;
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
}


