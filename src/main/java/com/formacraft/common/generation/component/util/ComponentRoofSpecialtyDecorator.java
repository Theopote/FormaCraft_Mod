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
                    // Typology/hint mansard_dormer only applies when the roof itself is mansard
                    // or the component/params explicitly request dormers — never every pitched roof.
                    if (parsed == SpecialtyLevel.MANSARD_DORMER
                            && !isMansardRoofType(params, component)
                            && !shouldApplyDormers(plan, params, component)) {
                        return SpecialtyLevel.MANSARD;
                    }
                    return parsed;
                }
            }
            String typology = String.valueOf(hints.getOrDefault("typology", "")).toLowerCase(Locale.ROOT);
            // Mansard silhouette only — dormers require an explicit cue (屋顶 specialty / roof_dormers /
            // feature tokens). Auto-adding three dormers to every baroque/townhouse roof was wrong.
            if (typology.contains("baroque") || typology.contains("townhouse") || typology.contains("paris")
                    || typology.contains("classical") || typology.contains("monument")
                    || typology.contains("palace")) {
                return SpecialtyLevel.MANSARD;
            }
        }
        if (shouldApplyDormers(plan, params, component) && isMansardRoofType(params, component)) {
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
        emitDormers(out, roofPatches, baseX, baseY, baseZ, width, depth, profile, count, null,
                trimBlock, glassBlock, roofBlock);
    }

    public static void emitDormers(
            List<BlockPatch> out, List<BlockPatch> roofPatches, int baseX, int baseY, int baseZ,
            int width, int depth, MansardProfile profile, Integer count, Map<String, Object> params,
            String trimBlock, String glassBlock, String roofBlock
    ) {
        if (out == null || (count == null && Math.max(width, depth) < 7)) {
            return;
        }
        int setback = profile.setback();
        // Explicit dormer_axis / dormer_face win; otherwise prefer the longer slope, and when a
        // count is authored pick the axis with the longest continuous fold strip.
        boolean alongWidth = resolveDormerAlongWidth(
                width, depth, count, roofPatches, baseX, baseZ, setback, params);
        int majorSpan = alongWidth ? width : depth;
        int minorSpan = alongWidth ? depth : width;
        if (count == null && majorSpan < 7) {
            return;
        }
        int faceMinor = setback;
        int protrude = 2;
        List<Integer> centers = count == null
                ? dormerCenterXs(alongWidth ? baseX : baseZ, majorSpan)
                : fittedDormerCenters(roofPatches, alongWidth ? baseX : baseZ, majorSpan,
                alongWidth ? baseZ + faceMinor : baseX + faceMinor, setback, count, alongWidth);
        for (int cMajor : centers) {
            int sampleX = alongWidth ? cMajor : baseX + faceMinor;
            int sampleZ = alongWidth ? baseZ + faceMinor : cMajor;
            int surfaceY = findRoofSurfaceY(roofPatches, sampleX, sampleZ);
            if (surfaceY == Integer.MIN_VALUE) {
                int localMajor = alongWidth ? (sampleX - baseX) : (sampleZ - baseZ);
                int distance = Math.min(Math.min(localMajor, majorSpan - 1 - localMajor),
                        Math.min(setback, minorSpan - 1 - setback));
                surfaceY = baseY + profile.riseAt(distance);
            }
            // cMajor is along the long facade; faceMinor is the setback into the slope.
            emitSingleDormer(out, roofPatches, cMajor, surfaceY, alongWidth ? baseZ + faceMinor : baseX + faceMinor,
                    protrude, setback, alongWidth, trimBlock, glassBlock, roofBlock);
        }
    }

    /**
     * {@code dormer_axis}=x|width → along width; {@code dormer_axis}=z|depth → along depth.
     * {@code dormer_face}=north|south → along width; east|west → along depth.
     * Auto: longer face, or the face with greater continuous fold capacity when count is set.
     */
    static boolean resolveDormerAlongWidth(
            int width, int depth, Integer count, List<BlockPatch> roofPatches,
            int baseX, int baseZ, int setback, Map<String, Object> params
    ) {
        Boolean explicit = parseDormerAxis(params);
        if (explicit != null) {
            return explicit;
        }
        if (count != null && roofPatches != null) {
            int widthCap = foldStripCapacity(roofPatches, baseX, width, baseZ + setback, setback, true);
            int depthCap = foldStripCapacity(roofPatches, baseZ, depth, baseX + setback, setback, false);
            if (widthCap != depthCap) {
                return widthCap > depthCap;
            }
        }
        return width >= depth;
    }

    private static Boolean parseDormerAxis(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        Object axis = params.get("dormer_axis");
        if (axis == null) axis = params.get("dormerAxis");
        if (axis != null) {
            String s = String.valueOf(axis).trim().toLowerCase(Locale.ROOT);
            if ("x".equals(s) || "width".equals(s) || "along_width".equals(s)) return true;
            if ("z".equals(s) || "depth".equals(s) || "along_depth".equals(s)) return false;
        }
        Object face = params.get("dormer_face");
        if (face == null) face = params.get("dormerFace");
        if (face != null) {
            String s = String.valueOf(face).trim().toLowerCase(Locale.ROOT);
            if ("north".equals(s) || "south".equals(s)) return true;
            if ("east".equals(s) || "west".equals(s)) return false;
        }
        return null;
    }

    private static int foldStripCapacity(
            List<BlockPatch> roofPatches, int baseMajor, int majorSpan,
            int faceMinorCoord, int setback, boolean alongWidth
    ) {
        boolean[] face = new boolean[majorSpan];
        boolean[] front = new boolean[majorSpan];
        for (BlockPatch patch : roofPatches == null ? List.<BlockPatch>of() : roofPatches) {
            if (!GeneratedSurfaceCapture.occupied(patch)) continue;
            int major = alongWidth ? patch.dx() - baseMajor : patch.dz() - baseMajor;
            int minor = alongWidth ? patch.dz() : patch.dx();
            if (major < 0 || major >= majorSpan) continue;
            if (minor == faceMinorCoord) face[major] = true;
            if (minor == faceMinorCoord - 1) front[major] = true;
        }
        int bestLength = 0;
        int length = 0;
        for (int i = setback; i < majorSpan - setback; i++) {
            length = face[i] && front[i] ? length + 1 : 0;
            if (length > bestLength) bestLength = length;
        }
        return bestLength < 3 ? 0 : (bestLength + 1) / 4;
    }

    private static List<Integer> fittedDormerCenters(List<BlockPatch> roofPatches, int baseMajor, int majorSpan,
                                                     int faceMinorCoord, int setback, int count, boolean alongWidth) {
        if (count < 1) {
            throw new IllegalArgumentException("E_ROOF_GEOMETRY_INVALID: mansard_dormer_count must be a positive whole number");
        }
        boolean[] face = new boolean[majorSpan];
        boolean[] front = new boolean[majorSpan];
        for (BlockPatch patch : roofPatches == null ? List.<BlockPatch>of() : roofPatches) {
            if (!GeneratedSurfaceCapture.occupied(patch)) continue;
            int major = alongWidth ? patch.dx() - baseMajor : patch.dz() - baseMajor;
            int minor = alongWidth ? patch.dz() : patch.dx();
            if (major < 0 || major >= majorSpan) continue;
            if (minor == faceMinorCoord) face[major] = true;
            if (minor == faceMinorCoord - 1) front[major] = true;
        }
        int bestStart = 0;
        int bestLength = 0;
        int length = 0;
        // A dormer is three cells wide and must stay on the major-face slope.
        for (int x = setback; x < majorSpan - setback; x++) {
            length = face[x] && front[x] ? length + 1 : 0;
            if (length > bestLength) {
                bestStart = x - length + 1;
                bestLength = length;
            }
        }
        int capacity = bestLength < 3 ? 0 : (bestLength + 1) / 4;
        if (count > capacity) {
            throw new IllegalArgumentException("E_ROOF_GEOMETRY_INVALID: mansard_dormer_count=" + count
                    + " exceeds the available fold strip capacity of " + capacity);
        }
        int first = baseMajor + bestStart + 1;
        int centerSpan = bestLength - 3;
        List<Integer> centers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            centers.add(first + (count == 1 ? centerSpan / 2
                    : (int) Math.round(i * (double) centerSpan / (count - 1))));
        }
        return centers;
    }

    /**
     * @param majorCenter center along the long facade axis (X when alongWidth, else Z)
     * @param faceMinor   setback coordinate on the short axis (Z when alongWidth, else X)
     */
    static void emitSingleDormer(
            List<BlockPatch> out,
            List<BlockPatch> roofPatches,
            int majorCenter,
            int surfaceY,
            int faceMinor,
            int protrude,
            int embedDepth,
            boolean alongWidth,
            String trimBlock,
            String glassBlock,
            String roofBlock
    ) {
        // Shell sits one block above the roof skin; carve the host slope at surfaceY.
        int baseY = surfaceY + 1;
        String stairs = ComponentFloorCorniceDecorator.inferStairsBlock(roofBlock);
        // Embed one cell into the slope so the dormer sits in the roof, not floating outside.
        int embed = Math.max(1, embedDepth);
        for (int dy = 0; dy <= 2; dy++) {
            for (int across = -1; across <= 1; across++) {
                for (int d = 0; d < protrude + embed; d++) {
                    int outward = d - embed;
                    boolean inRoof = outward < 0;
                    boolean isFront = outward == protrude - 1;
                    int x = alongWidth ? majorCenter + across : faceMinor - outward;
                    int z = alongWidth ? faceMinor - outward : majorCenter + across;
                    // Carve a pocket into the host slope before placing the dormer shell.
                    // Cheek columns (across=±1) must stay solid wall — never leave empty roof voids.
                    if (inRoof && roofPatches != null) {
                        if (Math.abs(across) == 1) {
                            out.add(new BlockPatch(BlockPatch.PLACE, x, surfaceY + dy, z, trimBlock));
                        } else {
                            out.add(new BlockPatch(BlockPatch.REMOVE, x, surfaceY + dy, z, "minecraft:air"));
                        }
                    }
                    String block;
                    if (isFront && across == 0) {
                        block = glassBlock;
                    } else if (isFront || Math.abs(across) == 1) {
                        block = trimBlock;
                    } else {
                        continue;
                    }
                    out.add(new BlockPatch(BlockPatch.PLACE, x, baseY + dy, z, block));
                }
            }
        }
        // Dormer roof: continue the slope back into the host roof with stairs facing the ridge.
        String ridgeFacing = alongWidth ? "south" : "east";
        for (int run = 0; run <= protrude + embed; run++) {
            int outward = run - embed;
            int roofY = baseY + 3 + Math.max(0, (protrude - Math.max(0, outward)) / 2);
            for (int across = -1; across <= 1; across++) {
                int x = alongWidth ? majorCenter + across : faceMinor - outward;
                int z = alongWidth ? faceMinor - outward : majorCenter + across;
                boolean ridgeCell = across == 0 && run == protrude + embed;
                String block;
                if (ridgeCell) {
                    block = roofBlock;
                } else if (stairs != null && !stairs.isBlank()) {
                    block = stairs + "[facing=" + ridgeFacing + ",half=bottom]";
                } else {
                    block = roofBlock;
                }
                out.add(new BlockPatch(BlockPatch.PLACE, x, roofY, z, block));
            }
        }
        out.add(new BlockPatch(BlockPatch.PLACE,
                alongWidth ? majorCenter : faceMinor - protrude,
                baseY + 4,
                alongWidth ? faceMinor - protrude : majorCenter,
                roofBlock));
        String lintel = ComponentFloorCorniceDecorator.inferStairsBlock(trimBlock);
        String lintelFacing = alongWidth ? "south" : "east";
        out.add(new BlockPatch(BlockPatch.PLACE,
                alongWidth ? majorCenter : faceMinor - protrude + 1,
                baseY + 2,
                alongWidth ? faceMinor - protrude + 1 : majorCenter,
                lintel + "[facing=" + lintelFacing + ",half=top]"));
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
