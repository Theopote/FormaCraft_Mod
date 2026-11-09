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
        if (com.formacraft.common.style.ExplicitDesignPolicy.noComplexDecor(plan, params)) return false;
        if (isDisabled(getParam(params, "roof_dormers", "roofDormers", "dormers"))) {
            return false;
        }
        if (isEnabled(getParam(params, "roof_dormers", "roofDormers", "dormers"))) {
            return true;
        }
        if (getParam(params, "mansard_dormer_count") != null
                && RoofTypeCatalog.extractExplicitType(component, params)
                .flatMap(RoofTypeCatalog::parseExplicit).orElse(null) == RoofTypeKind.MANSARD) {
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
        return resolveMansardProfile(width, depth, totalHeight, null)
                .riseAt(Math.min(Math.min(x, width - 1 - x), Math.min(z, depth - 1 - z)));
    }

    public record MansardProfile(int setback, int breakRise, int upperRun, int upperRise) {
        public int riseAt(int distance) {
            if (distance < setback) {
                return (int) Math.round(distance / (double) setback * breakRise);
            }
            double t = Math.min(upperRun, distance - setback) / (double) upperRun;
            return breakRise + (int) Math.round(t * upperRise);
        }
    }

    public static MansardProfile resolveMansardProfile(int width, int depth, int height, Map<String, Object> params) {
        int setback = mansardSetback(width, depth);
        int center = Math.min(width - 1, depth - 1) / 2;
        int lowerHeight = Math.max(2, (height * 3) / 5);
        String runParam = getParam(params, "mansard_break_run");
        String riseParam = getParam(params, "mansard_break_rise");
        if (runParam == null && riseParam == null) {
            if (width < 2 || depth < 2 || height < 2) return new MansardProfile(setback, 0, 1, 0);
            return new MansardProfile(setback, lowerHeight - 1, Math.max(1, center - setback),
                    Math.max(1, height - lowerHeight));
        }
        int run = runParam == null ? setback : positiveMansardInteger(runParam, "mansard_break_run");
        int rise = riseParam == null ? lowerHeight - 1 : positiveMansardInteger(riseParam, "mansard_break_rise");
        int maxRise = height - 1;
        if (run < 1 || run >= center || rise < 1 || rise >= maxRise
                || (long) rise * center <= (long) run * maxRise) {
            throw new IllegalArgumentException("E_ROOF_GEOMETRY_INVALID: mansard_break_run and mansard_break_rise "
                    + "must put the fold inside the roof and keep the lower slope steeper than the upper slope");
        }
        return new MansardProfile(run, rise, center - run, maxRise - rise);
    }

    public static Integer mansardDormerCount(Map<String, Object> params) {
        String raw = getParam(params, "mansard_dormer_count");
        return raw == null ? null : positiveMansardInteger(raw, "mansard_dormer_count");
    }

    private static int positiveMansardInteger(String raw, String key) {
        String error = "E_ROOF_GEOMETRY_INVALID: " + key + " must be a positive whole number";
        try {
            double value = Double.parseDouble(raw);
            if (value == Math.rint(value) && value >= 1 && value <= Integer.MAX_VALUE) return (int) value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(error, e);
        }
        throw new IllegalArgumentException(error);
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
        emitDormers(out, roofPatches, baseX, baseY, baseZ, width, depth,
                resolveMansardProfile(width, depth, height, null), trimBlock, glassBlock, roofBlock);
    }

    public static void emitDormers(
            List<BlockPatch> out, List<BlockPatch> roofPatches, int baseX, int baseY, int baseZ,
            int width, int depth, MansardProfile profile, String trimBlock, String glassBlock, String roofBlock
    ) {
        emitDormers(out, roofPatches, baseX, baseY, baseZ, width, depth, profile, null, trimBlock, glassBlock, roofBlock);
    }

    public static void emitDormers(
            List<BlockPatch> out, List<BlockPatch> roofPatches, int baseX, int baseY, int baseZ,
            int width, int depth, MansardProfile profile, Integer count, String trimBlock, String glassBlock, String roofBlock
    ) {
        if (out == null || (count == null && width < 7)) {
            return;
        }
        int setback = profile.setback();
        int faceZ = baseZ + setback;
        int protrude = 2;
        List<Integer> centers = count == null ? dormerCenterXs(baseX, width)
                : fittedDormerCenters(roofPatches, baseX, width, faceZ, setback, count);
        for (int cx : centers) {
            int surfaceY = findRoofSurfaceY(roofPatches, cx, faceZ);
            if (surfaceY == Integer.MIN_VALUE) {
                int localX = cx - baseX;
                int distance = Math.min(Math.min(localX, width - 1 - localX), Math.min(setback, depth - 1 - setback));
                surfaceY = baseY + profile.riseAt(distance);
            }
            emitSingleDormer(out, cx, surfaceY, faceZ, protrude, trimBlock, glassBlock, roofBlock);
        }
    }

    private static List<Integer> fittedDormerCenters(List<BlockPatch> roofPatches, int baseX, int width,
                                                     int faceZ, int setback, int count) {
        if (count < 1) {
            throw new IllegalArgumentException("E_ROOF_GEOMETRY_INVALID: mansard_dormer_count must be a positive whole number");
        }
        boolean[] face = new boolean[width];
        boolean[] front = new boolean[width];
        for (BlockPatch patch : roofPatches == null ? List.<BlockPatch>of() : roofPatches) {
            if (!GeneratedSurfaceCapture.occupied(patch)) continue;
            int x = patch.dx() - baseX;
            if (x < 0 || x >= width) continue;
            if (patch.dz() == faceZ) face[x] = true;
            if (patch.dz() == faceZ - 1) front[x] = true;
        }
        int bestStart = 0;
        int bestLength = 0;
        int length = 0;
        // A dormer is three cells wide and must stay on the front slope.
        for (int x = setback; x < width - setback; x++) {
            length = face[x] && front[x] ? length + 1 : 0;
            if (length > bestLength) {
                bestStart = x - length + 1;
                bestLength = length;
            }
        }
        int capacity = (bestLength + 1) / 4;
        if (count > capacity) {
            throw new IllegalArgumentException("E_ROOF_GEOMETRY_INVALID: mansard_dormer_count=" + count
                    + " exceeds the available fold strip capacity of " + capacity);
        }
        int first = baseX + bestStart + 1;
        int centerSpan = bestLength - 3;
        List<Integer> centers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            centers.add(first + (count == 1 ? centerSpan / 2
                    : (int) Math.round(i * (double) centerSpan / (count - 1))));
        }
        return centers;
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
