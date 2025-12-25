package com.formacraft.server.skeleton.vertical;

import com.formacraft.common.skeleton.Skeleton;
import com.formacraft.common.skeleton.SkeletonParams;
import com.formacraft.common.skeleton.SkeletonType;
import com.formacraft.common.skeleton.vertical.VerticalTaperPlan;

import java.util.ArrayList;
import java.util.List;

/**
 * VerticalTaperSkeleton: computes per-y taper profile.
 * This is topology only; interpreters decide actual block placement patterns.
 */
public final class VerticalTaperSkeleton implements Skeleton<VerticalTaperPlan> {
    @Override
    public SkeletonType type() {
        return SkeletonType.VERTICAL_TAPER;
    }

    @Override
    public VerticalTaperPlan generate(SkeletonParams params) {
        int height = getInt(params, "height", 60, 16, 260);
        int baseWidth = getInt(params, "baseWidth", 27, 9, 101);
        if (baseWidth % 2 == 0) baseWidth += 1;
        int baseHalf = baseWidth / 2;

        int topHalf = getInt(params, "topHalf", 2, 1, Math.max(2, baseHalf));
        boolean refined = getBool(params, "refined", false);
        int platformCount = getInt(params, "platformCount", 2, 0, 4);

        int[] halfByY = new int[height + 1];
        for (int y = 0; y <= height; y++) {
            double t = (height <= 0) ? 1.0 : (y / (double) height);
            int curHalf = (int) Math.round(lerp(baseHalf, topHalf, t * 0.92));
            if (curHalf < topHalf) curHalf = topHalf;
            if (curHalf > baseHalf) curHalf = baseHalf;
            halfByY[y] = curHalf;
        }

        List<Integer> platforms = new ArrayList<>();
        if (platformCount >= 1) platforms.add(Math.max(8, (int) Math.round(height * 0.33)));
        if (platformCount >= 2) platforms.add(Math.max(8, (int) Math.round(height * 0.66)));
        if (platformCount >= 3) platforms.add(Math.max(8, (int) Math.round(height * 0.82)));
        if (platformCount >= 4) platforms.add(Math.max(8, (int) Math.round(height * 0.9)));

        int spireStart = Math.max(0, height - (refined ? 14 : 10));
        int spireEnd = height + (refined ? 6 : 4);

        return new VerticalTaperPlan(height, baseHalf, topHalf, halfByY, platforms, refined, spireStart, spireEnd);
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

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}


