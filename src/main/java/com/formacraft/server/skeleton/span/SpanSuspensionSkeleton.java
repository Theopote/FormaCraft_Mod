package com.formacraft.server.skeleton.span;

import com.formacraft.common.skeleton.SkeletonParamParsers;
import com.formacraft.common.skeleton.Skeleton;
import com.formacraft.common.skeleton.SkeletonParams;
import com.formacraft.common.skeleton.SkeletonType;
import com.formacraft.common.skeleton.span.SpanSuspensionPlan;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * SpanSuspensionSkeleton: computes the span topology (deck centers + cable curve),
 * leaving all block/material decisions to the interpreter.
 */
public final class SpanSuspensionSkeleton implements Skeleton<SpanSuspensionPlan> {
    private final ServerWorld world;
    private final BlockPos origin;

    public SpanSuspensionSkeleton(ServerWorld world, BlockPos origin) {
        this.world = world;
        this.origin = origin;
    }

    @Override
    public SkeletonType type() {
        return SkeletonType.SPAN_SUSPENSION;
    }

    @Override
    public SpanSuspensionPlan generate(SkeletonParams params) {
        int span = SkeletonParamParsers.boundedInt(params, "span", 180, 40, 2000);
        int deckWidth = SkeletonParamParsers.boundedInt(params, "deckWidth", 9, 5, 63);
        if (deckWidth % 2 == 0) deckWidth += 1;
        int halfW = deckWidth / 2;
        int towerH = SkeletonParamParsers.boundedInt(params, "towerHeight", 44, 18, 200);
        boolean followTerrain = getBool(params, "followTerrain", true);
        boolean refined = getBool(params, "refined", false);

        Direction facing = parseFacing(String.valueOf(params.get("facing") == null ? "EAST" : params.get("facing")));

        int z1 = (int) Math.round(span * 0.33);
        int z2 = (int) Math.round(span * 0.67);
        if (z2 <= z1 + 4) z2 = Math.min(span - 4, z1 + 8);

        // baseY0: follow terrain using midpoint (same strategy as original generator)
        int baseY0 = origin.getY();
        if (followTerrain) {
            BlockPos mid = origin.add(facing.getOffsetX() * (span / 2), 0, facing.getOffsetZ() * (span / 2));
            int top = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, mid.getX(), mid.getZ());
            baseY0 = Math.max(origin.getY(), top + 1);
        }

        List<BlockPos> deckCenters = new ArrayList<>(span + 1);
        int[] cableY = new int[span + 1];

        int cableTopY = baseY0 + towerH;
        int sag = refined ? 10 : 8;
        Parabola parab = Parabola.through(z1, cableTopY, (z1 + z2) / 2.0, cableTopY - sag, z2, cableTopY);

        for (int i = 0; i <= span; i++) {
            int dx = facing.getOffsetX() * i;
            int dz = facing.getOffsetZ() * i;

            int deckY = baseY0;
            if (followTerrain) {
                BlockPos sample = origin.add(dx, 0, dz);
                int top = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, sample.getX(), sample.getZ());
                deckY = Math.max(baseY0, top + 1);
            }
            BlockPos center = new BlockPos(origin.getX() + dx, deckY, origin.getZ() + dz);
            deckCenters.add(center);

            int yCable;
            if (i < z1) {
                yCable = lerpInt(deckY + (refined ? 12 : 10), cableTopY, i / (double) Math.max(1, z1));
            } else if (i > z2) {
                yCable = lerpInt(cableTopY, deckY + (refined ? 12 : 10), (i - z2) / (double) Math.max(1, (span - z2)));
            } else {
                yCable = (int) Math.round(parab.y(i));
            }
            cableY[i] = yCable;
        }

        return new SpanSuspensionPlan(deckCenters, halfW, z1, z2, towerH, cableY, refined);
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

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static int lerpInt(int a, int b, double t) {
        return (int) Math.round(lerp(a, b, t));
    }

    private static final class Parabola {
        private final double a, b, c;
        private Parabola(double a, double b, double c) { this.a = a; this.b = b; this.c = c; }

        static Parabola through(double x1, double y1, double x2, double y2, double x3, double y3) {
            double denom = (x1 - x2) * (x1 - x3) * (x2 - x3);
            if (Math.abs(denom) < 1e-9) return new Parabola(0, 0, y2);
            double A = (x3 * (y2 - y1) + x2 * (y1 - y3) + x1 * (y3 - y2)) / denom;
            double B = (x3 * x3 * (y1 - y2) + x2 * x2 * (y3 - y1) + x1 * x1 * (y2 - y3)) / denom;
            double C = (x2 * x3 * (x2 - x3) * y1 + x3 * x1 * (x3 - x1) * y2 + x1 * x2 * (x1 - x2) * y3) / denom;
            return new Parabola(A, B, C);
        }
        double y(double x) { return a * x * x + b * x + c; }
    }
}


