package com.formacraft.common.component.variant;

import net.minecraft.util.math.Direction;

/** Presentation mapping only: persisted adaptation arrays remain in captured X/Y/Z order. */
public final class CapturedAxisLayout {
    private final boolean eastWest;

    private CapturedAxisLayout(boolean eastWest) { this.eastWest = eastWest; }

    public static CapturedAxisLayout forFacing(Direction facing) {
        return new CapturedAxisLayout(facing == Direction.EAST || facing == Direction.WEST);
    }

    /** Display dimensions are width, height, depth; result indexes captured X/Y/Z arrays. */
    public int worldAxis(int dimension) {
        return switch (dimension) {
            case 0 -> eastWest ? 2 : 0;
            case 1 -> 1;
            case 2 -> eastWest ? 0 : 2;
            default -> throw new IllegalArgumentException("Unknown dimension: " + dimension);
        };
    }

    public String label(int dimension) {
        String name = switch (dimension) {
            case 0 -> "正面宽度";
            case 1 -> "高度";
            case 2 -> "厚度";
            default -> throw new IllegalArgumentException("Unknown dimension: " + dimension);
        };
        return name + " " + "XYZ".charAt(worldAxis(dimension));
    }

    public int dimensionSize(int dimension, int x, int y, int z) {
        return switch (worldAxis(dimension)) { case 0 -> x; case 1 -> y; default -> z; };
    }
}
