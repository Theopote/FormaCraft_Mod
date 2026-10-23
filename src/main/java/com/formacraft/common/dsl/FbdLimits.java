package com.formacraft.common.dsl;

/** Authority-owned limits; programs cannot supply or raise them. */
public record FbdLimits(int bytes, int nodes, int depth, int steps, int repeats,
                        int commands, long samples, long writes) {
    public static final FbdLimits DEFAULT = new FbdLimits(65_536, 2_000, 24,
            200_000, 5_000, 20_000, 2_000_000, 200_000);

    public FbdLimits {
        if (bytes < 1 || nodes < 1 || depth < 1 || steps < 1 || repeats < 0
                || commands < 1 || samples < 1 || writes < 1) {
            throw new IllegalArgumentException("Invalid FBD limits");
        }
    }
}
