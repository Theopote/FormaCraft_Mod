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
     * @param seedCells folded PLACE cells from the processor input (previous patch list)
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
        // No volume/surface: seed-only (never unbounded).
        Set<BlockPos> carveable = new HashSet<>(seeds);
        carveable.removeAll(clearance);
        Set<BlockPos> place = new HashSet<>(seeds);
        return new PostProcessWriteScope(place, carveable, false);
    }

    /** Clearance-only scope for ProtectedClearanceRestorer. */
    public static PostProcessWriteScope clearanceOnly(Set<BlockPos> clearanceCells) {
        Set<BlockPos> cells = clearanceCells == null ? Set.of() : Set.copyOf(clearanceCells);
        return new PostProcessWriteScope(cells, cells, false);
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

    /** Scope that also accepts any cell inside buildingVolumes. */
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
            if (explicitPlace.contains(p)) return true;
            return inVolume(p);
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
                if (v.contains(p.getX(), p.getY(), p.getZ())) return true;
            }
            return false;
        }
    }
}
