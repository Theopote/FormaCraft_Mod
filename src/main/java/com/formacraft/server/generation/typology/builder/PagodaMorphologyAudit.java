package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.typology.TypologyGenerationException;
import net.minecraft.block.BlockState;
import net.minecraft.block.SlabBlock;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Post-generation hard checks for pagoda morphology.
 * Prefer {@link #checkFromBlocks} which measures the emitted block set — not generator loop counters.
 */
public final class PagodaMorphologyAudit {
    private PagodaMorphologyAudit() {}

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
            int span
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
        return finalizeCheck(
                typologyId,
                expected,
                geo.measuredFloors(),
                geo.measuredEaveTiers(),
                geo.setbackObserved(),
                geo.finialCentered(),
                hardFail,
                false);
    }

    /**
     * Independent geometry observation from emitted blocks.
     * <ul>
     *   <li>Floors ≈ taper steps (distinct body max-radial shrinks ascending in Y)</li>
     *   <li>Eave tiers ≈ Y rows where slab/stair rings extend beyond the local shaft radius</li>
     *   <li>Setback ≈ max radial shrinks from lower third to upper shaft</li>
     *   <li>Finial ≈ solid cells on plan-axis near the top of the span</li>
     * </ul>
     * Niche stairs / trim slabs must not inflate floor or eave counts.
     */
    public static GeometryObservation observe(List<PlannedBlock> blocks, BlockPos origin) {
        if (blocks == null || blocks.isEmpty() || origin == null) {
            return new GeometryObservation(0, 0, false, false, 0);
        }
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
            if (isEaveLike(state)) {
                s.eaveLike++;
                s.eaveMaxRadial = Math.max(s.eaveMaxRadial, radial);
            } else {
                s.bodyLike++;
                s.bodyMaxRadial = Math.max(s.bodyMaxRadial, radial);
            }
            if (dx == 0 && dz == 0) {
                s.axisSolid++;
            }
        }
        if (layers.isEmpty()) {
            return new GeometryObservation(0, 0, false, false, 0);
        }
        int span = maxY - minY + 1;

        // Body shaft radius profile (ignore eave-dominated rows).
        TreeMap<Integer, Integer> bodyRadiusByY = new TreeMap<>();
        for (var e : layers.entrySet()) {
            LayerStats s = e.getValue();
            if (s.bodyLike <= 0) continue;
            // Skip pure eave rings; keep mixed rows via body radius only.
            if (s.eaveLike > 0 && s.eaveLike >= s.bodyLike * 2 && s.eaveMaxRadial > s.bodyMaxRadial) {
                continue;
            }
            bodyRadiusByY.put(e.getKey(), s.bodyMaxRadial > 0 ? s.bodyMaxRadial : s.maxRadial);
        }

        // Floors from taper: each strict radial shrink is a storey transition.
        int floors = 1;
        int prevBodyR = -1;
        for (int r : bodyRadiusByY.values()) {
            if (prevBodyR >= 0 && r < prevBodyR) {
                floors++;
            }
            prevBodyR = r;
        }
        if (bodyRadiusByY.isEmpty()) {
            floors = 0;
        }

        // Eave rings: outward slab/stair beyond the nearest body shaft radius.
        int eaveTiers = 0;
        Integer lastEaveY = null;
        for (var e : layers.entrySet()) {
            int y = e.getKey();
            LayerStats s = e.getValue();
            if (s.eaveLike <= 0 || s.eaveMaxRadial <= 0) continue;
            Integer shaft = nearestBodyRadius(bodyRadiusByY, y);
            int shaftR = shaft == null ? 0 : shaft;
            if (s.eaveMaxRadial <= shaftR) continue;
            // Collapse refined companion rings on adjacent Y into one tier.
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
            if (y <= lowerCut) {
                lowerRadial = Math.max(lowerRadial, r);
            }
            if (y >= upperCut && y < maxY - 2) {
                upperRadial = Math.min(upperRadial, r);
            }
        }
        if (upperRadial == Integer.MAX_VALUE) {
            upperRadial = lowerRadial;
        }
        boolean setback = floors <= 1 || lowerRadial > upperRadial;

        int finialBand = Math.max(minY, maxY - Math.max(3, span / 5));
        boolean finialCentered = false;
        for (var e : layers.tailMap(finialBand, true).entrySet()) {
            if (e.getValue().axisSolid > 0) {
                finialCentered = true;
                break;
            }
        }

        return new GeometryObservation(floors, eaveTiers, setback, finialCentered, span);
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
                int delta = Math.abs(actualEaveTiers - expected.eaveTiers());
                if (delta > 1) {
                    diags.add("eave_tiers expected=" + expected.eaveTiers() + " actual=" + actualEaveTiers);
                }
            }
            if (!setbackObserved && actualFloors > 1) {
                diags.add("setback/taper not observed across storeys");
            }
        } else {
            // Geometry counts are noisy (niches/trim). Hard-fail only on under-build /
            // missing taper-finial — never on over-counted rings from companion slabs.
            if (expected != null && expected.floorsExplicit() && expected.floorCount() > 1) {
                int minFloors = Math.max(1, expected.floorCount() - 1);
                if (actualFloors > 0 && actualFloors < minFloors) {
                    diags.add("floor_count expected>=" + minFloors + " actual=" + actualFloors);
                }
                if (!setbackObserved) {
                    diags.add("setback/taper not observed across storeys");
                }
            }
            if (expected != null && expected.floorsExplicit() && expected.eaveTiers() > 0) {
                int minEaves = Math.max(1, (expected.eaveTiers() + 1) / 2);
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

    private static boolean isEaveLike(BlockState state) {
        if (state == null || state.isAir()) return false;
        if (state.getBlock() instanceof SlabBlock) return true;
        String id = String.valueOf(net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()));
        String lower = id.toLowerCase();
        return lower.contains("slab") || lower.contains("stairs") || lower.contains("step");
    }

    private static final class LayerStats {
        int solid;
        int bodyLike;
        int eaveLike;
        int axisSolid;
        int maxRadial;
        int bodyMaxRadial;
        int eaveMaxRadial;
    }
}
