package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.build.GeometrySource;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.typology.TypologyGenerationException;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Post-generation hard checks for pagoda morphology.
 * Prefer role-tagged blocks from the builder ({@code pagoda.shaft.*} / {@code pagoda.eave.*} /
 * {@code pagoda.finial}) so material swaps (slab vs full block) cannot flip pass/fail.
 */
public final class PagodaMorphologyAudit {
    public static final String ROLE_SHAFT = "pagoda.shaft";
    public static final String ROLE_EAVE = "pagoda.eave";
    public static final String ROLE_EAVE_TRIM = "pagoda.eave_trim";
    public static final String ROLE_FINIAL = "pagoda.finial";

    private PagodaMorphologyAudit() {}

    public static GeometrySource shaftRole(int level) {
        return GeometrySource.generated(ROLE_SHAFT + "." + Math.max(0, level));
    }

    public static GeometrySource eaveRole(int level, int tier) {
        return GeometrySource.generated(ROLE_EAVE + "." + Math.max(0, level) + "." + Math.max(0, tier));
    }

    public static GeometrySource eaveTrimRole() {
        return GeometrySource.generated(ROLE_EAVE_TRIM);
    }

    public static GeometrySource finialRole() {
        return GeometrySource.generated(ROLE_FINIAL);
    }

    public record Result(
            int expectedFloors,
            int actualFloors,
            int expectedEaveTiers,
            int actualEaveTiers,
            boolean setbackObserved,
            boolean finialCentered,
            List<String> diagnostics
    ) {
        public boolean passed() {
            return diagnostics == null || diagnostics.isEmpty();
        }
    }

    public record GeometryObservation(
            int measuredFloors,
            int measuredEaveTiers,
            boolean setbackObserved,
            boolean finialCentered,
            int span,
            boolean rolesPresent
    ) {}

    /**
     * Legacy counter-based check (kept for unit tests of the diagnostic messaging).
     * Prefer {@link #checkFromBlocks} for production builders.
     */
    public static Result check(
            String typologyId,
            PagodaVerticalSemantics expected,
            int actualFloors,
            int actualEaveTiers,
            boolean setbackObserved,
            boolean finialCentered,
            boolean hardFail
    ) {
        return finalizeCheck(
                typologyId, expected, actualFloors, actualEaveTiers,
                setbackObserved, finialCentered, hardFail, true);
    }

    /** Measure final planned blocks, then hard-check against expected vertical semantics. */
    public static Result checkFromBlocks(
            String typologyId,
            PagodaVerticalSemantics expected,
            List<PlannedBlock> blocks,
            BlockPos origin,
            boolean hardFail
    ) {
        GeometryObservation geo = observe(blocks, origin);
        // Role-tagged builds use exact explicit counts; heuristic fallback stays tolerant.
        return finalizeCheck(
                typologyId,
                expected,
                geo.measuredFloors(),
                geo.measuredEaveTiers(),
                geo.setbackObserved(),
                geo.finialCentered(),
                hardFail,
                geo.rolesPresent());
    }

    public static GeometryObservation observe(List<PlannedBlock> blocks, BlockPos origin) {
        if (blocks == null || blocks.isEmpty() || origin == null) {
            return new GeometryObservation(0, 0, false, false, 0, false);
        }
        boolean rolesPresent = false;
        for (PlannedBlock b : blocks) {
            if (producer(b) != null && producer(b).startsWith("pagoda.")) {
                rolesPresent = true;
                break;
            }
        }
        return rolesPresent ? observeByRole(blocks, origin) : observeByGeometry(blocks, origin);
    }

    private static GeometryObservation observeByRole(List<PlannedBlock> blocks, BlockPos origin) {
        Set<Integer> shaftLevels = new TreeSet<>();
        Set<String> eaveKeys = new HashSet<>();
        TreeMap<Integer, Integer> shaftRadiusByLevel = new TreeMap<>();
        int finialAxis = 0;
        int finialMinY = Integer.MAX_VALUE;
        int finialMaxY = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (PlannedBlock b : blocks) {
            if (b == null || b.getPos() == null || b.getTargetState() == null || b.getTargetState().isAir()) {
                continue;
            }
            int ry = b.getPos().getY() - origin.getY();
            int dx = Math.abs(b.getPos().getX() - origin.getX());
            int dz = Math.abs(b.getPos().getZ() - origin.getZ());
            int radial = Math.max(dx, dz);
            minY = Math.min(minY, ry);
            maxY = Math.max(maxY, ry);
            String p = producer(b);
            if (p == null) continue;
            if (p.startsWith(ROLE_SHAFT + ".")) {
                int level = parseTrailingInt(p, ROLE_SHAFT.length() + 1);
                if (level >= 0) {
                    shaftLevels.add(level);
                    shaftRadiusByLevel.merge(level, radial, Math::max);
                }
            } else if (p.startsWith(ROLE_EAVE + ".")) {
                // Intentionally authored eave tiers only — not trim/ornament companions.
                String key = p.substring(ROLE_EAVE.length() + 1);
                if (!key.isBlank()) eaveKeys.add(key);
            } else if (ROLE_FINIAL.equals(p) && dx == 0 && dz == 0) {
                finialAxis++;
                finialMinY = Math.min(finialMinY, ry);
                finialMaxY = Math.max(finialMaxY, ry);
            }
        }
        int floors = shaftLevels.size();
        int eaveTiers = eaveKeys.size();
        boolean setback = floors <= 1;
        if (!setback) {
            Integer prev = null;
            boolean shrink = false;
            for (int r : shaftRadiusByLevel.values()) {
                if (prev != null && r < prev) shrink = true;
                prev = r;
            }
            setback = shrink || shaftRadiusByLevel.size() <= 1;
        }
        boolean finial = finialAxis >= 3 && finialMaxY >= finialMinY && (finialMaxY - finialMinY) >= 2;
        int span = maxY >= minY ? maxY - minY + 1 : 0;
        return new GeometryObservation(floors, eaveTiers, setback, finial, span, true);
    }

    /**
     * Fallback when roles are missing: outward rings vs shaft radius — never block-id based.
     */
    private static GeometryObservation observeByGeometry(List<PlannedBlock> blocks, BlockPos origin) {
        TreeMap<Integer, LayerStats> layers = new TreeMap<>();
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (PlannedBlock b : blocks) {
            if (b == null || b.getPos() == null || b.getTargetState() == null) continue;
            BlockState state = b.getTargetState();
            if (state.isAir()) continue;
            int ry = b.getPos().getY() - origin.getY();
            int dx = Math.abs(b.getPos().getX() - origin.getX());
            int dz = Math.abs(b.getPos().getZ() - origin.getZ());
            int radial = Math.max(dx, dz);
            minY = Math.min(minY, ry);
            maxY = Math.max(maxY, ry);
            LayerStats s = layers.computeIfAbsent(ry, k -> new LayerStats());
            s.solid++;
            s.maxRadial = Math.max(s.maxRadial, radial);
            if (dx == 0 && dz == 0) s.axisSolid++;
        }
        if (layers.isEmpty()) {
            return new GeometryObservation(0, 0, false, false, 0, false);
        }
        int span = maxY - minY + 1;

        TreeMap<Integer, Integer> bodyRadiusByY = new TreeMap<>();
        for (var e : layers.entrySet()) {
            bodyRadiusByY.put(e.getKey(), e.getValue().maxRadial);
        }
        int floors = 1;
        int prevBodyR = -1;
        for (int r : bodyRadiusByY.values()) {
            if (prevBodyR >= 0 && r < prevBodyR) floors++;
            prevBodyR = r;
        }

        int eaveTiers = 0;
        Integer lastEaveY = null;
        for (var e : layers.entrySet()) {
            int y = e.getKey();
            LayerStats s = e.getValue();
            Integer shaft = nearestBodyRadius(bodyRadiusByY, y - 1);
            int shaftR = shaft == null ? 0 : shaft;
            // Outward ring: this Y's max radial exceeds nearby lower shaft radius.
            if (s.maxRadial <= shaftR) continue;
            if (lastEaveY != null && y - lastEaveY <= 1) {
                lastEaveY = y;
                continue;
            }
            eaveTiers++;
            lastEaveY = y;
        }

        int lowerCut = minY + Math.max(1, span / 3);
        int upperCut = maxY - Math.max(1, span / 3);
        int lowerRadial = 0;
        int upperRadial = Integer.MAX_VALUE;
        for (var e : bodyRadiusByY.entrySet()) {
            int y = e.getKey();
            int r = e.getValue();
            if (y <= lowerCut) lowerRadial = Math.max(lowerRadial, r);
            if (y >= upperCut && y < maxY - 2) upperRadial = Math.min(upperRadial, r);
        }
        if (upperRadial == Integer.MAX_VALUE) upperRadial = lowerRadial;
        boolean setback = floors <= 1 || lowerRadial > upperRadial;

        int finialBand = Math.max(minY, maxY - Math.max(3, span / 5));
        int axisHits = 0;
        Integer firstAxis = null;
        Integer lastAxis = null;
        for (var e : layers.tailMap(finialBand, true).entrySet()) {
            if (e.getValue().axisSolid > 0) {
                axisHits += e.getValue().axisSolid;
                if (firstAxis == null) firstAxis = e.getKey();
                lastAxis = e.getKey();
            }
        }
        boolean finialCentered = axisHits >= 3 && firstAxis != null && lastAxis != null && lastAxis - firstAxis >= 2;

        return new GeometryObservation(floors, eaveTiers, setback, finialCentered, span, false);
    }

    private static Integer nearestBodyRadius(TreeMap<Integer, Integer> bodyRadiusByY, int y) {
        if (bodyRadiusByY == null || bodyRadiusByY.isEmpty()) return null;
        var floor = bodyRadiusByY.floorEntry(y);
        var ceil = bodyRadiusByY.ceilingEntry(y);
        if (floor == null) return ceil.getValue();
        if (ceil == null) return floor.getValue();
        return (y - floor.getKey() <= ceil.getKey() - y) ? floor.getValue() : ceil.getValue();
    }

    private static Result finalizeCheck(
            String typologyId,
            PagodaVerticalSemantics expected,
            int actualFloors,
            int actualEaveTiers,
            boolean setbackObserved,
            boolean finialCentered,
            boolean hardFail,
            boolean exactCounts
    ) {
        List<String> diags = new ArrayList<>();
        if (exactCounts) {
            if (expected != null && expected.floorsExplicit() && actualFloors != expected.floorCount()) {
                diags.add("floor_count expected=" + expected.floorCount() + " actual=" + actualFloors);
            }
            if (expected != null && expected.floorsExplicit() && actualEaveTiers != expected.eaveTiers()) {
                diags.add("eave_tiers expected=" + expected.eaveTiers() + " actual=" + actualEaveTiers);
            }
            if (!setbackObserved && expected != null && expected.floorCount() > 1) {
                diags.add("setback/taper not observed across storeys");
            }
        } else {
            // Heuristic path only — detection noise must not rewrite user intent.
            if (expected != null && expected.floorsExplicit() && expected.floorCount() > 1) {
                int minFloors = Math.max(1, expected.floorCount() - 1);
                if (actualFloors > 0 && actualFloors < minFloors) {
                    diags.add("floor_count expected>=" + minFloors + " actual=" + actualFloors);
                }
                if (actualFloors > expected.floorCount() + 1) {
                    diags.add("floor_count expected<=" + (expected.floorCount() + 1) + " actual=" + actualFloors);
                }
                if (!setbackObserved) {
                    diags.add("setback/taper not observed across storeys");
                }
            }
            if (expected != null && expected.floorsExplicit() && expected.eaveTiers() > 0) {
                int minEaves = Math.max(1, expected.eaveTiers() - 1);
                if (actualEaveTiers < minEaves) {
                    diags.add("eave_tiers expected>=" + minEaves + " actual=" + actualEaveTiers);
                }
            }
        }
        if (!finialCentered) {
            diags.add("finial not centered on tower axis");
        }
        Result result = new Result(
                expected == null ? 0 : expected.floorCount(),
                actualFloors,
                expected == null ? 0 : expected.eaveTiers(),
                actualEaveTiers,
                setbackObserved,
                finialCentered,
                List.copyOf(diags));
        if (hardFail && !result.passed()) {
            throw new TypologyGenerationException(
                    typologyId,
                    "E_PAGODA_MORPHOLOGY",
                    "Pagoda morphology failed hard checks: " + String.join("; ", diags),
                    diags);
        }
        return result;
    }

    private static String producer(PlannedBlock b) {
        if (b == null || b.getSource() == null) return null;
        String p = b.getSource().producer();
        return p == null || p.isBlank() ? null : p.trim().toLowerCase(Locale.ROOT);
    }

    private static int parseTrailingInt(String s, int from) {
        if (s == null || from >= s.length()) return -1;
        try {
            return Integer.parseInt(s.substring(from).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static final class LayerStats {
        int solid;
        int axisSolid;
        int maxRadial;
    }
}
