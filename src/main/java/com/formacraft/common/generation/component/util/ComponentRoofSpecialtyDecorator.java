package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.patch.BlockPatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * L4 屋顶 specialty：Mansard 折线轮廓 + 老虎窗（dormer）。
 */
public final class ComponentRoofSpecialtyDecorator {

    public enum SpecialtyLevel {
        OFF,
        MANSARD,
        MANSARD_DORMER
    }

    private ComponentRoofSpecialtyDecorator() {}

    public static SpecialtyLevel resolveLevel(LlmPlan plan, Map<String, Object> params, Component component) {
        if (isDisabled(getParam(params, "roof_specialty", "roofSpecialty"))) {
            return SpecialtyLevel.OFF;
        }
        String raw = getParam(params, "roof_specialty", "roofSpecialty");
        if (raw != null) {
            SpecialtyLevel parsed = parseLevel(raw);
            if (parsed != SpecialtyLevel.OFF) {
                return parsed;
            }
        }
        if (isMansardRoofType(params, component)) {
            return shouldApplyDormers(plan, params, component) ? SpecialtyLevel.MANSARD_DORMER : SpecialtyLevel.MANSARD;
        }
        if (plan != null && plan.proportionHints() != null) {
            Map<String, Object> hints = plan.proportionHints();
            Object hint = hints.get("roof_specialty");
            if (hint == null) {
                hint = hints.get("roofSpecialty");
            }
            if (hint != null) {
                SpecialtyLevel parsed = parseLevel(String.valueOf(hint));
                if (parsed != SpecialtyLevel.OFF) {
                    return parsed;
                }
            }
            String typology = String.valueOf(hints.getOrDefault("typology", "")).toLowerCase(Locale.ROOT);
            if (typology.contains("baroque") || typology.contains("townhouse") || typology.contains("paris")) {
                return SpecialtyLevel.MANSARD_DORMER;
            }
            if (typology.contains("classical") || typology.contains("monument") || typology.contains("palace")) {
                return SpecialtyLevel.MANSARD;
            }
        }
        if (shouldApplyDormers(plan, params, component)) {
            return SpecialtyLevel.MANSARD_DORMER;
        }
        return SpecialtyLevel.OFF;
    }

    public static boolean isMansardRoofType(Map<String, Object> params, Component component) {
        String type = getParam(params, "roof_type", "roofType");
        if (type != null && type.toLowerCase(Locale.ROOT).contains("mansard")) {
            return true;
        }
        if (component != null && component.features() != null) {
            for (String f : component.features()) {
                if (f != null && f.toLowerCase(Locale.ROOT).contains("mansard")) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean shouldApplyDormers(LlmPlan plan, Map<String, Object> params, Component component) {
        if (isDisabled(getParam(params, "roof_dormers", "roofDormers", "dormers"))) {
            return false;
        }
        if (isEnabled(getParam(params, "roof_dormers", "roofDormers", "dormers"))) {
            return true;
        }
        if (component != null && component.features() != null) {
            for (String f : component.features()) {
                if (f != null) {
                    String lower = f.toLowerCase(Locale.ROOT);
                    if (lower.contains("dormer") || lower.contains("老虎窗")) {
                        return true;
                    }
                }
            }
        }
        if (plan != null && plan.proportionHints() != null) {
            Map<String, Object> hints = plan.proportionHints();
            if (isEnabled(hints.get("roof_dormers")) || isEnabled(hints.get("roofDormers"))) {
                return true;
            }
            String specialty = String.valueOf(hints.getOrDefault("roof_specialty", "")).toLowerCase(Locale.ROOT);
            return specialty.contains("dormer");
        }
        return false;
    }

    /** Mansard 折坡：周边陡、内圈缓，返回 0..totalHeight-1 */
    public static int computeMansardRise(int x, int z, int width, int depth, int totalHeight) {
        if (width < 2 || depth < 2 || totalHeight < 2) {
            return 0;
        }
        int lowerH = Math.max(2, (totalHeight * 3) / 5);
        int upperH = Math.max(1, totalHeight - lowerH);
        int setback = Math.max(1, Math.min(width, depth) / 5);
        int dist = Math.min(Math.min(x, width - 1 - x), Math.min(z, depth - 1 - z));
        if (dist < setback) {
            return (int) Math.round((dist / (double) setback) * (lowerH - 1));
        }
        int centerDist = Math.min(width - 1, depth - 1) / 2;
        int innerMax = Math.max(1, centerDist - setback);
        int innerDist = Math.min(innerMax, dist - setback);
        double t = innerDist / (double) innerMax;
        return (lowerH - 1) + (int) Math.round(t * upperH);
    }

    public static int mansardSetback(int width, int depth) {
        return Math.max(1, Math.min(width, depth) / 5);
    }

    public static List<Integer> dormerCenterXs(int baseX, int width) {
        List<Integer> centers = new ArrayList<>();
        centers.add(baseX + width / 2);
        if (width >= 11) {
            centers.add(baseX + width / 3);
            centers.add(baseX + (width * 2) / 3);
        }
        return centers;
    }

    public static int findRoofSurfaceY(List<BlockPatch> patches, int x, int z) {
        int maxY = Integer.MIN_VALUE;
        if (patches == null) {
            return maxY;
        }
        for (BlockPatch patch : patches) {
            if (patch == null || BlockPatch.REMOVE.equals(patch.action())) {
                continue;
            }
            if (patch.dx() == x && patch.dz() == z && patch.dy() > maxY) {
                maxY = patch.dy();
            }
        }
        return maxY;
    }

    public static void emitDormers(
            List<BlockPatch> out,
            List<BlockPatch> roofPatches,
            int baseX,
            int baseY,
            int baseZ,
            int width,
            int depth,
            int height,
            String trimBlock,
            String glassBlock,
            String roofBlock
    ) {
        if (out == null || width < 7) {
            return;
        }
        int setback = mansardSetback(width, depth);
        int faceZ = baseZ + setback;
        int protrude = 2;
        List<Integer> centers = dormerCenterXs(baseX, width);
        for (int cx : centers) {
            int surfaceY = findRoofSurfaceY(roofPatches, cx, faceZ);
            if (surfaceY == Integer.MIN_VALUE) {
                surfaceY = baseY + computeMansardRise(cx - baseX, faceZ - baseZ, width, depth, height);
            }
            emitSingleDormer(out, cx, surfaceY, faceZ, protrude, trimBlock, glassBlock, roofBlock);
        }
    }

    static void emitSingleDormer(
            List<BlockPatch> out,
            int cx,
            int surfaceY,
            int faceZ,
            int protrude,
            String trimBlock,
            String glassBlock,
            String roofBlock
    ) {
        int baseY = surfaceY + 1;
        for (int dy = 0; dy <= 2; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int d = 0; d < protrude; d++) {
                    int z = faceZ - d;
                    boolean isFront = d == protrude - 1;
                    String block;
                    if (isFront && dx == 0) {
                        block = glassBlock;
                    } else if (isFront || Math.abs(dx) == 1) {
                        block = trimBlock;
                    } else {
                        continue;
                    }
                    out.add(new BlockPatch(BlockPatch.PLACE, cx + dx, baseY + dy, z, block));
                }
            }
        }
        int capZ = faceZ - protrude + 1;
        for (int dx = -1; dx <= 1; dx++) {
            out.add(new BlockPatch(BlockPatch.PLACE, cx + dx, baseY + 3, capZ, roofBlock));
        }
        out.add(new BlockPatch(BlockPatch.PLACE, cx, baseY + 4, capZ, roofBlock));
        String lintel = ComponentFloorCorniceDecorator.inferStairsBlock(trimBlock);
        out.add(new BlockPatch(BlockPatch.PLACE, cx, baseY + 2, capZ,
                lintel + "[facing=south,half=top]"));
    }

    private static SpecialtyLevel parseLevel(String raw) {
        if (raw == null) {
            return SpecialtyLevel.OFF;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty() || "off".equals(v) || "none".equals(v) || "false".equals(v)) {
            return SpecialtyLevel.OFF;
        }
        if (v.contains("dormer")) {
            return SpecialtyLevel.MANSARD_DORMER;
        }
        if (v.contains("mansard")) {
            return SpecialtyLevel.MANSARD;
        }
        return SpecialtyLevel.OFF;
    }

    private static boolean isDisabled(Object v) {
        if (v == null) {
            return false;
        }
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return "false".equals(s) || "off".equals(s) || "none".equals(s);
    }

    private static boolean isEnabled(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return "true".equals(s) || "yes".equals(s) || "on".equals(s);
    }

    private static String getParam(Map<String, Object> params, String... keys) {
        if (params == null) {
            return null;
        }
        for (String key : keys) {
            Object v = params.get(key);
            if (v == null) {
                continue;
            }
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return null;
    }
}
