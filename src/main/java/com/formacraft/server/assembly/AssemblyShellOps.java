package com.formacraft.server.assembly;

import com.formacraft.common.build.PlannedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import java.util.List;
import java.util.Map;

/** Box shell generation: clear interior, then walls, floors and roof. */
public final class AssemblyShellOps {
    private AssemblyShellOps() {}

    public static void applyShellBox(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx,
                                     BlockPos origin, Map<String, Object> op, AssemblySolidOps.Adapter adapter) {
        // box shell with semantic materials
        int w = adapter.clamp(adapter.i(op.get("w"), 15), 5, 129);
        int d = adapter.clamp(adapter.i(op.get("d"), 15), 5, 129);
        int h = adapter.clamp(adapter.i(op.get("h"), 18), 6, 255);
        int floorStep = adapter.clamp(adapter.i(op.get("floorStep"), 4), 3, 8);
        // Forma-Gene integration: twist support (twistTurns: number of full rotations, e.g., 0.25 = 90°, 1.0 = 360°)
        double twistTurns = adapter.clamp(adapter.d(op.get("twistTurns"), adapter.d(op.get("twist_turns"), 0.0)), -2.0, 2.0);
        double twistPhase = adapter.clamp(adapter.d(op.get("twistPhase"), adapter.d(op.get("twist_phase"), 0.0)), 0.0, 1.0);

        BlockState wall = adapter.pick(ctx, op, "wall", "WALL_BASE", 0xA55001L, Blocks.STONE_BRICKS.getDefaultState());
        BlockState glass = adapter.pick(ctx, op, "window", "WINDOW", 0xA55002L, Blocks.GLASS_PANE.getDefaultState());
        BlockState floor = adapter.pick(ctx, op, "floor", "FLOORING", 0xA55003L, Blocks.SMOOTH_STONE.getDefaultState());
        BlockState roof = adapter.pick(ctx, op, "roof", "FLOOR_SLAB", 0xA55004L, Blocks.SMOOTH_STONE_SLAB.getDefaultState());

        // local coords: centered around origin (like OfficeBlock)
        int halfW = w / 2;
        int halfD = d / 2;

        boolean hasTwist = Math.abs(twistTurns) > 0.001 || Math.abs(twistPhase) > 0.001;

        // Clear only transformed interior samples before generating any structure.
        // A final bounding-box clear would delete floors and twisted perimeter walls.
        for (int yy = 1; yy <= h; yy++) {
            double angle = (twistTurns * Math.PI * 2.0) * ((double) yy / h) + twistPhase * Math.PI * 2.0;
            double cosA = Math.cos(angle), sinA = Math.sin(angle);
            for (int x = -halfW + 1; x <= halfW - 1; x++) {
                for (int z = -halfD + 1; z <= halfD - 1; z++) {
                    int finalX = hasTwist ? (int) Math.round(x * cosA - z * sinA) : x;
                    int finalZ = hasTwist ? (int) Math.round(x * sinA + z * cosA) : z;
                    adapter.put(out, ctx, origin, finalX, yy, finalZ, Blocks.AIR.getDefaultState());
                }
            }
        }

        // shell walls
        for (int yy = 0; yy <= h; yy++) {
            boolean windowBand = (yy % 4 == 2) && yy <= h - 2;
            double t = h > 0 ? (double) yy / h : 0.0; // 0..1
            double angle = (twistTurns * Math.PI * 2.0) * t + (twistPhase * Math.PI * 2.0);
            double cosA = Math.cos(angle);
            double sinA = Math.sin(angle);

            for (int x = -halfW; x <= halfW; x++) {
                for (int z = -halfD; z <= halfD; z++) {
                    boolean edge = (Math.abs(x) == halfW) || (Math.abs(z) == halfD);
                    if (!edge) continue;

                    int finalX = x;
                    int finalZ = z;
                    if (hasTwist) {
                        // Rotate coordinates around center (0,0)
                        double rx = x * cosA - z * sinA;
                        double rz = x * sinA + z * cosA;
                        finalX = (int) Math.round(rx);
                        finalZ = (int) Math.round(rz);
                    }

                    BlockState s = wall;
                    if (windowBand && (Math.abs(x) != halfW || Math.abs(z) != halfD)) s = glass;
                    adapter.put(out, ctx, origin, finalX, yy, finalZ, s);
                }
            }
        }

        // floors (with twist)
        for (int yy = 0; yy <= h; yy += floorStep) {
            double t = h > 0 ? (double) yy / h : 0.0;
            double angle = (twistTurns * Math.PI * 2.0) * t + (twistPhase * Math.PI * 2.0);
            double cosA = Math.cos(angle);
            double sinA = Math.sin(angle);

            for (int x = -halfW + 1; x <= halfW - 1; x++) {
                for (int z = -halfD + 1; z <= halfD - 1; z++) {
                    int finalX = x;
                    int finalZ = z;
                    if (hasTwist) {
                        double rx = x * cosA - z * sinA;
                        double rz = x * sinA + z * cosA;
                        finalX = (int) Math.round(rx);
                        finalZ = (int) Math.round(rz);
                    }
                    adapter.put(out, ctx, origin, finalX, yy, finalZ, floor);
                }
            }
        }

        // roof cap (with twist)
        if (h > 0) {
            double angle = (twistTurns * Math.PI * 2.0) + (twistPhase * Math.PI * 2.0);
            double cosA = Math.cos(angle);
            double sinA = Math.sin(angle);
            for (int x = -halfW; x <= halfW; x++) {
                for (int z = -halfD; z <= halfD; z++) {
                    int finalX = x;
                    int finalZ = z;
                    if (hasTwist) {
                        double rx = x * cosA - z * sinA;
                        double rz = x * sinA + z * cosA;
                        finalX = (int) Math.round(rx);
                        finalZ = (int) Math.round(rz);
                    }
                    adapter.put(out, ctx, origin, finalX, h + 1, finalZ, roof);
                }
            }
        }

    }
}
