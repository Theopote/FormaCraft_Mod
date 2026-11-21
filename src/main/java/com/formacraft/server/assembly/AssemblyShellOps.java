package com.formacraft.server.assembly;

import com.formacraft.common.build.PlannedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Box shell generation: clear interior, then walls/posts, floors and roof. */
public final class AssemblyShellOps {
    private AssemblyShellOps() {}

    public static final class InvalidTopScale extends IllegalArgumentException {
        private InvalidTopScale() {
            super("E_SHELL_BOX_TOP_SCALE: expected 0.5..1.0 for a closed shell, or 1.0 for open walls");
        }
    }

    public static void applyShellBox(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx,
                                     BlockPos origin, Map<String, Object> op, AssemblySolidOps.Adapter adapter) {
        int w = adapter.clamp(adapter.i(op.get("w"), 15), 5, 129);
        int d = adapter.clamp(adapter.i(op.get("d"), 15), 5, 129);
        int h = adapter.clamp(adapter.i(op.get("h"), 18), 6, 255);
        int floorStep = adapter.clamp(adapter.i(op.get("floorStep"), 4), 3, 8);
        double twistTurns = adapter.clamp(adapter.d(op.get("twistTurns"), adapter.d(op.get("twist_turns"), 0.0)), -2.0, 2.0);
        double twistPhase = adapter.clamp(adapter.d(op.get("twistPhase"), adapter.d(op.get("twist_phase"), 0.0)), 0.0, 1.0);
        boolean colonnade = isColonnade(op, adapter);
        double topScale = op.containsKey("topScale") ? adapter.d(op.get("topScale"), Double.NaN) : 1.0;
        if (!(topScale >= 0.5 && topScale <= 1.0) || (colonnade && topScale != 1.0)) {
            throw new InvalidTopScale();
        }
        int postSpacing = adapter.clamp(
                adapter.i(op.get("postSpacing"), adapter.i(op.get("post_spacing"), 2)), 1, 8);

        BlockState wall = adapter.pick(ctx, op, "wall", "WALL_BASE", 0xA55001L, Blocks.STONE_BRICKS.getDefaultState());
        BlockState glass = adapter.pick(ctx, op, "window", "WINDOW", 0xA55002L, Blocks.GLASS_PANE.getDefaultState());
        BlockState floor = adapter.pick(ctx, op, "floor", "FLOORING", 0xA55003L, Blocks.SMOOTH_STONE.getDefaultState());
        BlockState roof = adapter.pick(ctx, op, "roof", "FLOOR_SLAB", 0xA55004L, Blocks.SMOOTH_STONE_SLAB.getDefaultState());
        BlockState post = adapter.pick(ctx, op, "post", "COLUMN", 0xA55005L, wall);

        int halfW = w / 2;
        int halfD = d / 2;
        Slice[] slices = new Slice[h + 1];
        for (int y = 0; y <= h; y++) {
            double scale = 1.0 + (topScale - 1.0) * y / h;
            int width = (int) Math.round(w * scale);
            int depth = (int) Math.round(d * scale);
            slices[y] = colonnade ? new Slice(-halfW, halfW, -halfD, halfD)
                    : new Slice(-width / 2, width - width / 2 - 1, -depth / 2, depth - depth / 2 - 1);
        }
        boolean hasTwist = hasTwist(twistTurns, twistPhase);

        // Clear interior samples before structure (avoid wiping twisted perimeter later).
        for (int yy = 1; yy <= h; yy++) {
            Slice slice = slices[yy];
            if (hasTwist && !colonnade) {
                sampleTwistedRectangle(out, ctx, origin, adapter, yy, slice.minX + 1, slice.maxX - 1, slice.minZ + 1, slice.maxZ - 1,
                        twistAngle(yy, h, twistTurns, twistPhase), Blocks.AIR.getDefaultState());
                continue;
            }
            for (int x = slice.minX + 1; x <= slice.maxX - 1; x++) {
                for (int z = slice.minZ + 1; z <= slice.maxZ - 1; z++) {
                    MetaAssemblyEngine.meterSample(ctx);
                    int[] xz = twistXZ(x, z, yy, h, twistTurns, twistPhase, hasTwist);
                    adapter.put(out, ctx, origin, xz[0], yy, xz[1], Blocks.AIR.getDefaultState());
                }
            }
        }

        if (colonnade) {
            placeColonnade(out, ctx, origin, adapter, halfW, halfD, h,
                    twistTurns, twistPhase, hasTwist, post, wall, postSpacing);
        } else {
            for (int yy = 0; yy <= h; yy++) {
                Slice slice = slices[yy];
                boolean windowBand = (yy % 4 == 2) && yy <= h - 2;
                for (int x = slice.minX; x <= slice.maxX; x++) {
                    for (int z = slice.minZ; z <= slice.maxZ; z++) {
                        MetaAssemblyEngine.meterSample(ctx);
                        boolean edgeX = x == slice.minX || x == slice.maxX;
                        boolean edgeZ = z == slice.minZ || z == slice.maxZ;
                        boolean edge = edgeX || edgeZ;
                        if (!edge) continue;
                        int[] xz = twistXZ(x, z, yy, h, twistTurns, twistPhase, hasTwist);
                        BlockState s = wall;
                        if (windowBand && !(edgeX && edgeZ)) {
                            s = glass;
                        }
                        adapter.put(out, ctx, origin, xz[0], yy, xz[1], s);
                        if (hasTwist) {
                            if (edgeZ && x < slice.maxX) {
                                bridgeTwistedEdge(out, ctx, origin, adapter, yy, xz,
                                        twistXZ(x + 1, z, yy, h, twistTurns, twistPhase, true), s);
                            }
                            if (edgeX && z < slice.maxZ) {
                                bridgeTwistedEdge(out, ctx, origin, adapter, yy, xz,
                                        twistXZ(x, z + 1, yy, h, twistTurns, twistPhase, true), s);
                            }
                        }
                    }
                }
            }
        }

        for (int yy = 0; yy <= h; yy += floorStep) {
            // Colonnade: full deck only at ground; elevated floors stay interior so open bays remain.
            Slice slice = slices[yy];
            int inset = colonnade && yy == 0 ? 0 : 1;
            int x0 = slice.minX + inset;
            int x1 = slice.maxX - inset;
            int z0 = slice.minZ + inset;
            int z1 = slice.maxZ - inset;
            if (hasTwist && !colonnade) {
                sampleTwistedRectangle(out, ctx, origin, adapter, yy, x0, x1, z0, z1,
                        twistAngle(yy, h, twistTurns, twistPhase), floor);
                continue;
            }
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    MetaAssemblyEngine.meterSample(ctx);
                    int[] xz = twistXZ(x, z, yy, h, twistTurns, twistPhase, hasTwist);
                    adapter.put(out, ctx, origin, xz[0], yy, xz[1], floor);
                }
            }
        }

        if (h > 0) {
            Slice slice = slices[h];
            if (hasTwist && !colonnade) {
                sampleTwistedRectangle(out, ctx, origin, adapter, h + 1, slice.minX, slice.maxX, slice.minZ, slice.maxZ,
                        twistAngle(h, h, twistTurns, twistPhase), roof);
                return;
            }
            for (int x = slice.minX; x <= slice.maxX; x++) {
                for (int z = slice.minZ; z <= slice.maxZ; z++) {
                    MetaAssemblyEngine.meterSample(ctx);
                    int[] xz = twistXZ(x, z, h, h, twistTurns, twistPhase, hasTwist);
                    adapter.put(out, ctx, origin, xz[0], h + 1, xz[1], roof);
                }
            }
        }
    }

    private record Slice(int minX, int maxX, int minZ, int maxZ) {}

    /** Sample target cells back into a local rectangle so rounding cannot leave holes. */
    private static void sampleTwistedRectangle(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx,
                                         BlockPos origin, AssemblySolidOps.Adapter adapter, int y,
                                         int minLocalX, int maxLocalX, int minLocalZ, int maxLocalZ,
                                         double angle, BlockState state) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double centerX = (minLocalX + maxLocalX) * 0.5;
        double centerZ = (minLocalZ + maxLocalZ) * 0.5;
        double halfWidth = (maxLocalX - minLocalX + 1) * 0.5;
        double halfDepth = (maxLocalZ - minLocalZ + 1) * 0.5;
        double rotatedX = centerX * cos - centerZ * sin;
        double rotatedZ = centerX * sin + centerZ * cos;
        double extentX = halfWidth * Math.abs(cos) + halfDepth * Math.abs(sin);
        double extentZ = halfWidth * Math.abs(sin) + halfDepth * Math.abs(cos);
        int minX = (int) Math.ceil(rotatedX - extentX);
        int maxX = (int) Math.floor(rotatedX + extentX);
        int minZ = (int) Math.ceil(rotatedZ - extentZ);
        int maxZ = (int) Math.floor(rotatedZ + extentZ);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                MetaAssemblyEngine.meterSample(ctx);
                if (Math.abs(x * cos + z * sin - centerX) <= halfWidth
                        && Math.abs(-x * sin + z * cos - centerZ) <= halfDepth) {
                    adapter.put(out, ctx, origin, x, y, z, state);
                }
            }
        }
    }

    private static void bridgeTwistedEdge(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx,
                                          BlockPos origin, AssemblySolidOps.Adapter adapter, int y,
                                          int[] from, int[] to, BlockState state) {
        if (Math.abs(from[0] - to[0]) != 1 || Math.abs(from[1] - to[1]) != 1) return;
        // Choose the inside of the two possible steps to avoid expanding the shell outward.
        int a = from[0] * from[0] + to[1] * to[1];
        int b = to[0] * to[0] + from[1] * from[1];
        boolean useFromX = a <= b;
        MetaAssemblyEngine.meterSample(ctx);
        adapter.put(out, ctx, origin, useFromX ? from[0] : to[0], y, useFromX ? to[1] : from[1], state);
    }

    private static boolean isColonnade(Map<String, Object> op, AssemblySolidOps.Adapter adapter) {
        String mode = adapter.str(op.get("wallMode"), adapter.str(op.get("wall_mode"), "SOLID"));
        if (mode != null && !mode.isBlank()) {
            String upper = mode.trim().toUpperCase(Locale.ROOT);
            if (upper.equals("COLONNADE") || upper.equals("OPEN") || upper.equals("PILLARS")
                    || upper.equals("POSTS")) {
                return true;
            }
        }
        return adapter.d(op.get("openness"), -1.0) >= 0.65;
    }

    /** Open pavilion: corner + bay posts and a top perimeter beam — no solid walls. */
    private static void placeColonnade(
            List<PlannedBlock> out,
            MetaAssemblyEngine.Context ctx,
            BlockPos origin,
            AssemblySolidOps.Adapter adapter,
            int halfW,
            int halfD,
            int h,
            double twistTurns,
            double twistPhase,
            boolean hasTwist,
            BlockState post,
            BlockState beam,
            int spacing
    ) {
        Set<Long> posts = new LinkedHashSet<>();
        posts.add(pack(halfW * -1, halfD * -1));
        posts.add(pack(halfW, halfD * -1));
        posts.add(pack(halfW * -1, halfD));
        posts.add(pack(halfW, halfD));
        for (int x = -halfW + spacing; x < halfW; x += spacing) {
            posts.add(pack(x, -halfD));
            posts.add(pack(x, halfD));
        }
        for (int z = -halfD + spacing; z < halfD; z += spacing) {
            posts.add(pack(-halfW, z));
            posts.add(pack(halfW, z));
        }

        for (long packed : posts) {
            int x = unpackX(packed);
            int z = unpackZ(packed);
            for (int yy = 1; yy <= h; yy++) {
                MetaAssemblyEngine.meterSample(ctx);
                int[] xz = twistXZ(x, z, yy, h, twistTurns, twistPhase, hasTwist);
                adapter.put(out, ctx, origin, xz[0], yy, xz[1], post);
            }
        }

        for (int x = -halfW; x <= halfW; x++) {
            for (int z : new int[]{-halfD, halfD}) {
                MetaAssemblyEngine.meterSample(ctx);
                int[] xz = twistXZ(x, z, h, h, twistTurns, twistPhase, hasTwist);
                adapter.put(out, ctx, origin, xz[0], h, xz[1], beam);
            }
        }
        for (int z = -halfD + 1; z <= halfD - 1; z++) {
            for (int x : new int[]{-halfW, halfW}) {
                MetaAssemblyEngine.meterSample(ctx);
                int[] xz = twistXZ(x, z, h, h, twistTurns, twistPhase, hasTwist);
                adapter.put(out, ctx, origin, xz[0], h, xz[1], beam);
            }
        }
    }

    private static long pack(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    private static int unpackZ(long packed) {
        return (int) packed;
    }

    static boolean hasTwist(double turns, double phase) {
        return Math.abs(turns) > 0.001 || Math.abs(phase) > 0.001;
    }

    static int[] twistXZ(int x, int z, int yy, int h, double twistTurns, double twistPhase, boolean hasTwist) {
        if (!hasTwist) {
            return new int[]{x, z};
        }
        double angle = twistAngle(yy, h, twistTurns, twistPhase);
        double cosA = Math.cos(angle);
        double sinA = Math.sin(angle);
        return new int[]{
                (int) Math.round(x * cosA - z * sinA),
                (int) Math.round(x * sinA + z * cosA)
        };
    }

    private static double twistAngle(int y, int height, double turns, double phase) {
        double progress = height > 0 ? (double) y / height : 0;
        return (turns * Math.PI * 2.0) * progress + (phase * Math.PI * 2.0);
    }
}
