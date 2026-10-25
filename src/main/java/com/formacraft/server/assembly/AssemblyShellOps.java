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

    public static void applyShellBox(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx,
                                     BlockPos origin, Map<String, Object> op, AssemblySolidOps.Adapter adapter) {
        int w = adapter.clamp(adapter.i(op.get("w"), 15), 5, 129);
        int d = adapter.clamp(adapter.i(op.get("d"), 15), 5, 129);
        int h = adapter.clamp(adapter.i(op.get("h"), 18), 6, 255);
        int floorStep = adapter.clamp(adapter.i(op.get("floorStep"), 4), 3, 8);
        double twistTurns = adapter.clamp(adapter.d(op.get("twistTurns"), adapter.d(op.get("twist_turns"), 0.0)), -2.0, 2.0);
        double twistPhase = adapter.clamp(adapter.d(op.get("twistPhase"), adapter.d(op.get("twist_phase"), 0.0)), 0.0, 1.0);
        boolean colonnade = isColonnade(op, adapter);
        int postSpacing = adapter.clamp(
                adapter.i(op.get("postSpacing"), adapter.i(op.get("post_spacing"), 2)), 1, 8);

        BlockState wall = adapter.pick(ctx, op, "wall", "WALL_BASE", 0xA55001L, Blocks.STONE_BRICKS.getDefaultState());
        BlockState glass = adapter.pick(ctx, op, "window", "WINDOW", 0xA55002L, Blocks.GLASS_PANE.getDefaultState());
        BlockState floor = adapter.pick(ctx, op, "floor", "FLOORING", 0xA55003L, Blocks.SMOOTH_STONE.getDefaultState());
        BlockState roof = adapter.pick(ctx, op, "roof", "FLOOR_SLAB", 0xA55004L, Blocks.SMOOTH_STONE_SLAB.getDefaultState());
        BlockState post = adapter.pick(ctx, op, "post", "COLUMN", 0xA55005L, wall);

        int halfW = w / 2;
        int halfD = d / 2;
        boolean hasTwist = Math.abs(twistTurns) > 0.001 || Math.abs(twistPhase) > 0.001;

        // Clear interior samples before structure (avoid wiping twisted perimeter later).
        for (int yy = 1; yy <= h; yy++) {
            for (int x = -halfW + 1; x <= halfW - 1; x++) {
                for (int z = -halfD + 1; z <= halfD - 1; z++) {
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
                boolean windowBand = (yy % 4 == 2) && yy <= h - 2;
                for (int x = -halfW; x <= halfW; x++) {
                    for (int z = -halfD; z <= halfD; z++) {
                        MetaAssemblyEngine.meterSample(ctx);
                        boolean edge = (Math.abs(x) == halfW) || (Math.abs(z) == halfD);
                        if (!edge) continue;
                        int[] xz = twistXZ(x, z, yy, h, twistTurns, twistPhase, hasTwist);
                        BlockState s = wall;
                        if (windowBand && (Math.abs(x) != halfW || Math.abs(z) != halfD)) {
                            s = glass;
                        }
                        adapter.put(out, ctx, origin, xz[0], yy, xz[1], s);
                    }
                }
            }
        }

        for (int yy = 0; yy <= h; yy += floorStep) {
            // Colonnade: full deck only at ground; elevated floors stay interior so open bays remain.
            boolean fullDeck = !colonnade || yy == 0;
            int x0 = fullDeck ? (colonnade ? -halfW : -halfW + 1) : -halfW + 1;
            int x1 = fullDeck ? (colonnade ? halfW : halfW - 1) : halfW - 1;
            int z0 = fullDeck ? (colonnade ? -halfD : -halfD + 1) : -halfD + 1;
            int z1 = fullDeck ? (colonnade ? halfD : halfD - 1) : halfD - 1;
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    MetaAssemblyEngine.meterSample(ctx);
                    int[] xz = twistXZ(x, z, yy, h, twistTurns, twistPhase, hasTwist);
                    adapter.put(out, ctx, origin, xz[0], yy, xz[1], floor);
                }
            }
        }

        if (h > 0) {
            for (int x = -halfW; x <= halfW; x++) {
                for (int z = -halfD; z <= halfD; z++) {
                    MetaAssemblyEngine.meterSample(ctx);
                    int[] xz = twistXZ(x, z, h, h, twistTurns, twistPhase, hasTwist);
                    adapter.put(out, ctx, origin, xz[0], h + 1, xz[1], roof);
                }
            }
        }
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

    private static int[] twistXZ(int x, int z, int yy, int h, double twistTurns, double twistPhase, boolean hasTwist) {
        if (!hasTwist) {
            return new int[]{x, z};
        }
        double t = h > 0 ? (double) yy / h : 0.0;
        double angle = (twistTurns * Math.PI * 2.0) * t + (twistPhase * Math.PI * 2.0);
        double cosA = Math.cos(angle);
        double sinA = Math.sin(angle);
        return new int[]{
                (int) Math.round(x * cosA - z * sinA),
                (int) Math.round(x * sinA + z * cosA)
        };
    }
}
