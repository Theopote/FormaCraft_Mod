package com.formacraft.server.skeleton.stack;

import com.formacraft.common.skeleton.SkeletonParamParsers;
import com.formacraft.common.skeleton.Skeleton;
import com.formacraft.common.skeleton.SkeletonParams;
import com.formacraft.common.skeleton.SkeletonType;
import com.formacraft.common.skeleton.stack.VerticalStackPlan;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * VerticalStackSkeleton: computes a stacked, shrinking square tower plan.
 * Good for pagodas, tiered towers, and many "layered" vertical structures.
 */
public final class VerticalStackSkeleton implements Skeleton<VerticalStackPlan> {
    @Override
    public SkeletonType type() {
        return SkeletonType.VERTICAL_STACK;
    }

    @Override
    public VerticalStackPlan generate(SkeletonParams params) {
        int levels = SkeletonParamParsers.boundedInt(params, "levels", 7, 2, 20);
        int height = SkeletonParamParsers.boundedInt(params, "height", levels * 6, 10, 260);
        int baseWidth = SkeletonParamParsers.boundedInt(params, "baseWidth", 17, 7, 81);
        if (baseWidth % 2 == 0) baseWidth += 1;

        boolean refined = getBool(params, "refined", false);
        Direction facing = parseFacing(String.valueOf(params.get("facing") == null ? "SOUTH" : params.get("facing")));

        int minPer = refined ? 5 : 4;
        int maxPer = refined ? 7 : 6;
        int per = clamp(height / Math.max(1, levels), minPer, maxPer);
        int usedHeight = per * levels;
        int extraTop = Math.max(0, height - usedHeight);

        int y = 0;
        int w = baseWidth;
        int shrinkEvery = 2;

        List<VerticalStackPlan.Level> out = new ArrayList<>(levels);
        for (int lv = 0; lv < levels; lv++) {
            int levelH = per;
            if (lv == levels - 1) levelH += extraTop;
            int half = w / 2;
            int eaveY = y + levelH;
            out.add(new VerticalStackPlan.Level(y, levelH, half, eaveY));

            y += levelH + 1;
            w = Math.max(7, w - shrinkEvery);
            if (w % 2 == 0) w -= 1;
        }

        return new VerticalStackPlan(out, facing, refined);
    }

    private static boolean getBool(SkeletonParams p, String key, boolean def) {
        Object v = p.get(key);
        if (v instanceof Boolean b) return b;
        if (v == null) return def;
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.isEmpty()) return def;
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static Direction parseFacing(String s) {
        String v = (s == null ? "" : s).trim().toUpperCase();
        return switch (v) {
            case "N", "NORTH", "北", "朝北" -> Direction.NORTH;
            case "S", "SOUTH", "南", "朝南" -> Direction.SOUTH;
            case "E", "EAST", "东", "朝东" -> Direction.EAST;
            case "W", "WEST", "西", "朝西" -> Direction.WEST;
            default -> Direction.SOUTH;
        };
    }
}


