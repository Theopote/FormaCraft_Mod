package com.formacraft.common.compiler.postprocess;

import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Spatial authority for a post-process step. Policy flags alone do not grant unbounded edits.
 */
public class PostProcessWriteScope {
    private final Set<BlockPos> placeable;
    private final Set<BlockPos> carveable;
    private final boolean volumeOrSurfaceBounded;

    private PostProcessWriteScope(Set<BlockPos> placeable, Set<BlockPos> carveable, boolean volumeOrSurfaceBounded) {
        this.placeable = Set.copyOf(placeable);
        this.carveable = Set.copyOf(carveable);
        this.volumeOrSurfaceBounded = volumeOrSurfaceBounded;
    }

    /**
     * Prefer the pipeline-frozen {@link PostProcessContext#authorizedWriteScope()} when present
     * (Phase 01.3 / P1-04). Otherwise build from {@code seedCells} — used by unit tests and
     * callers outside {@link PostProcessPipeline}.
     */
    public static PostProcessWriteScope forStep(PostProcessContext ctx, Set<BlockPos> seedCells) {
        if (ctx != null && ctx.authorizedWriteScope() != null) {
            return ctx.authorizedWriteScope();
        }
        return fromContext(ctx, seedCells);
    }

    /**
     * @param seedCells folded PLACE cells that authorize this step (must be pipeline-entry seeds
     *                  when freezing; never later décor-expanded cells)
     */
    public static PostProcessWriteScope fromContext(PostProcessContext ctx, Set<BlockPos> seedCells) {
        Set<BlockPos> seeds = seedCells == null ? Set.of() : Set.copyOf(seedCells);
        Set<BlockPos> clearance = ctx == null || ctx.protectedClearance() == null
                ? Set.of() : ctx.protectedClearance();
        Set<BlockPos> surfaces = ctx == null || ctx.generatedSurfaces() == null
                ? Set.of() : ctx.generatedSurfaces();
        boolean hasVolumes = ctx != null && ctx.buildingVolumes() != null && !ctx.buildingVolumes().isEmpty();
        boolean hasSurfaces = !surfaces.isEmpty();

        Set<BlockPos> placeable = new HashSet<>(seeds);
        placeable.addAll(surfaces);

        if (hasVolumes || hasSurfaces) {
            // Volume membership is checked dynamically via containsVolume; seeds/surfaces are explicit.
            return new VolumeAwareScope(placeable, clearance, ctx, true);
        }
        // No volume/surface: seed neighborhood pad for aesthetic PLACE (window trim / lights),
        // never unbounded. Carve stays on exact seeds.
        Set<BlockPos> carveable = new HashSet<>(seeds);
        carveable.removeAll(clearance);
        Set<BlockPos> place = expandChebyshev(seeds, EXTERIOR_PLACE_PAD);
        place.removeAll(clearance);
        return new PostProcessWriteScope(place, carveable, false);
    }

    /** Inclusive Chebyshev ball around each seed (pad=1 → 3×3×3 neighborhood). */
    static Set<BlockPos> expandChebyshev(Set<BlockPos> seeds, int pad) {
        if (seeds == null || seeds.isEmpty() || pad <= 0) {
            return seeds == null ? Set.of() : Set.copyOf(seeds);
        }
        Set<BlockPos> out = new HashSet<>(seeds.size() * 8);
        for (BlockPos s : seeds) {
            if (s == null) continue;
            for (int dx = -pad; dx <= pad; dx++) {
                for (int dy = -pad; dy <= pad; dy++) {
                    for (int dz = -pad; dz <= pad; dz++) {
                        out.add(s.add(dx, dy, dz));
                    }
                }
            }
        }
        return out;
    }

    /** Clearance-only scope for ProtectedClearanceRestorer. */
    public static PostProcessWriteScope clearanceOnly(Set<BlockPos> clearanceCells) {
        Set<BlockPos> cells = clearanceCells == null ? Set.of() : Set.copyOf(clearanceCells);
        return new PostProcessWriteScope(cells, cells, false);
    }

    /**
     * Exact-cell scope for incremental edits (Phase 01 closeout P1-03): PLACE/CARVE only
     * on the authorized Ω — no volume pad expansion.
     */
    public static PostProcessWriteScope exactCells(Set<BlockPos> authorized) {
        Set<BlockPos> cells = authorized == null ? Set.of() : Set.copyOf(authorized);
        return new PostProcessWriteScope(cells, cells, true);
    }

    /** Policy-only tests: every coordinate is spatially allowed. */
    public static PostProcessWriteScope unbounded() {
        return new PostProcessWriteScope(Set.of(), Set.of(), false) {
            @Override
            public boolean allowsPlace(BlockPos pos) {
                return pos != null;
            }

            @Override
            public boolean allowsCarve(BlockPos pos) {
                return pos != null;
            }
        };
    }

    public boolean allowsPlace(BlockPos pos) {
        return pos != null && placeable.contains(pos.toImmutable());
    }

    public boolean allowsCarve(BlockPos pos) {
        return pos != null && carveable.contains(pos.toImmutable());
    }

    public boolean volumeOrSurfaceBounded() {
        return volumeOrSurfaceBounded;
    }

    /** Exterior PLACE pad beyond volume AABB (Chebyshev / AABB expand by 1). Carve stays interior. */
    public static final int EXTERIOR_PLACE_PAD = 1;

    /** Scope that also accepts any cell inside buildingVolumes (+1 PLACE pad). */
    private static final class VolumeAwareScope extends PostProcessWriteScope {
        private final PostProcessContext ctx;
        private final Set<BlockPos> clearance;
        private final Set<BlockPos> explicitPlace;

        VolumeAwareScope(Set<BlockPos> explicitPlace, Set<BlockPos> clearance, PostProcessContext ctx, boolean bounded) {
            super(explicitPlace, Set.of(), bounded);
            this.ctx = Objects.requireNonNull(ctx);
            this.clearance = clearance;
            this.explicitPlace = explicitPlace;
        }

        @Override
        public boolean allowsPlace(BlockPos pos) {
            if (pos == null) return false;
            BlockPos p = pos.toImmutable();
            if (clearance.contains(p)) return false;
            if (explicitPlace.contains(p)) return true;
            return inVolume(p) || inExteriorPad(p);
        }

        @Override
        public boolean allowsCarve(BlockPos pos) {
            if (pos == null) return false;
            BlockPos p = pos.toImmutable();
            if (clearance.contains(p)) return false;
            if (explicitPlace.contains(p)) return true;
            return inVolume(p);
        }

        private boolean inVolume(BlockPos p) {
            for (var v : ctx.buildingVolumes()) {
                // Prefer domain occupancy when Phase 02.3.2 handoff attached a CSG domain.
                if (v.occupancyContains(p.getX(), p.getY(), p.getZ())) return true;
            }
            return false;
        }

        /** Half-open volume AABB expanded by {@link #EXTERIOR_PLACE_PAD} on every axis. */
        private boolean inExteriorPad(BlockPos p) {
            int pad = EXTERIOR_PLACE_PAD;
            for (var v : ctx.buildingVolumes()) {
                var b = v.bounds();
                if (p.getX() >= b.minX() - pad && p.getX() < b.maxX() + pad
                        && p.getY() >= b.minY() - pad && p.getY() < b.maxY() + pad
                        && p.getZ() >= b.minZ() - pad && p.getZ() < b.maxZ() + pad) {
                    return true;
                }
            }
            return false;
        }
    }
}
