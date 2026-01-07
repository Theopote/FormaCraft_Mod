package com.formacraft.common.skeleton.stack;

import com.formacraft.common.skeleton.SkeletonPlan;
import com.formacraft.common.skeleton.SkeletonType;
import net.minecraft.util.math.Direction;

import java.util.List;

/**
 * VerticalStackPlan: a sequence of stacked levels (square footprints), typically shrinking upward.
 */
public final class VerticalStackPlan extends SkeletonPlan {
    public final List<Level> levels;
    public final Direction facing;
    public final boolean refined;

    public VerticalStackPlan(List<Level> levels, Direction facing, boolean refined) {
        super();
        this.levels = levels;
        this.facing = facing;
        this.refined = refined;
    }

    @Override
    public SkeletonType type() {
        return SkeletonType.VERTICAL_STACK;
    }

    public static final class Level {
        public final int y0;
        public final int height;
        public final int half;     // half-width of square footprint at this level
        public final int eaveY;    // y at which to place eave band (top of level)

        public Level(int y0, int height, int half, int eaveY) {
            this.y0 = y0;
            this.height = height;
            this.half = half;
            this.eaveY = eaveY;
        }
    }
}


