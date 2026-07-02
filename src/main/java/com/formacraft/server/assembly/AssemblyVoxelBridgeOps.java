package com.formacraft.server.assembly;

import net.minecraft.block.BlockState;

/**
 * Voxel bridge/grid placement helpers extracted from MetaAssemblyEngine.
 */
public final class AssemblyVoxelBridgeOps {
    private AssemblyVoxelBridgeOps() {}

    @FunctionalInterface
    public interface VoxelPlacer {
        void place(int x, int y, int z, BlockState state);
    }

    public static void placePrism(
            VoxelPlacer placer,
            int cx,
            int cy,
            int cz,
            int thickness,
            int h,
            BlockState s
    ) {
        int half = thickness / 2;
        for (int x = -half; x <= half; x++) {
            for (int z = -half; z <= half; z++) {
                for (int y = 0; y < h; y++) {
                    placer.place(cx + x, cy + y, cz + z, s);
                }
            }
        }
    }

    public static void placeBeamLine(
            VoxelPlacer placer,
            int x0,
            int y0,
            int z0,
            int x1,
            int y1,
            int z1,
            int thickness,
            int beamH,
            BlockState s
    ) {
        int dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        int steps = Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz));
        if (steps <= 0) {
            placePrism(placer, x0, y0, z0, thickness, beamH, s);
            return;
        }
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            int x = (int) Math.round(x0 + dx * t);
            int y = (int) Math.round(y0 + dy * t);
            int z = (int) Math.round(z0 + dz * t);
            placePrism(placer, x, y, z, thickness, beamH, s);
        }
    }

    public static void connectSurfaceGrid(
            VoxelPlacer placer,
            int[][][] grid,
            int uN,
            int vN,
            int thick,
            BlockState mat
    ) {
        for (int iu = 0; iu <= uN; iu++) {
            for (int iv = 0; iv <= vN; iv++) {
                int x = grid[iu][iv][0], y = grid[iu][iv][1], z = grid[iu][iv][2];
                if (iu + 1 <= uN) {
                    int[] b = grid[iu + 1][iv];
                    placeBeamLine(placer, x, y, z, b[0], b[1], b[2], thick, 1, mat);
                }
                if (iv + 1 <= vN) {
                    int[] b = grid[iu][iv + 1];
                    placeBeamLine(placer, x, y, z, b[0], b[1], b[2], thick, 1, mat);
                }
            }
        }
    }
}

