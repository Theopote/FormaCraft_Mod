package com.formacraft.common.component.socket;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Host-authoritative cells that may be carved at a socket.
 * Distinct from {@link SizeConstraint} (install-size compatibility only).
 * <p>
 * When {@link #cells} is non-empty it is the sole authority; the AABB box is ignored.
 * Only when cells are missing/empty does {@link #ox}/{@link #oy}/{@link #oz} + {@link #w}/{@link #h}/{@link #d} apply.
 */
public final class OpeningRegion {
    public static final int MAX_CELLS = 4096;

    /** Explicit local cells relative to socket origin (each int[3] = dx,dy,dz). */
    public List<int[]> cells;

    /** Box origin relative to socket placement origin. */
    public int ox, oy, oz;
    /** Box size (inclusive span). Zero size means unused. */
    public int w, h, d;

    public OpeningRegion() {}

    public OpeningRegion(int ox, int oy, int oz, int w, int h, int d) {
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.w = w;
        this.h = h;
        this.d = d;
    }

    public static OpeningRegion ofCells(List<int[]> cells) {
        OpeningRegion r = new OpeningRegion();
        r.cells = cells == null ? List.of() : new ArrayList<>(cells);
        return r;
    }

    public static OpeningRegion box(int w, int h, int d) {
        return new OpeningRegion(0, 0, 0, w, h, d);
    }

    public boolean hasExplicitCells() {
        return cells != null && !cells.isEmpty();
    }

    public boolean isEmpty() {
        if (hasExplicitCells()) return false;
        return w <= 0 || h <= 0 || d <= 0;
    }

    /**
     * Local cells relative to socket origin (not yet world-transformed).
     * Explicit cells win; box is fallback only.
     */
    public Set<BlockPos> localCells() {
        Set<BlockPos> out = new LinkedHashSet<>();
        if (hasExplicitCells()) {
            for (int[] c : cells) {
                if (c == null || c.length < 3) continue;
                out.add(new BlockPos(c[0], c[1], c[2]));
            }
            return out;
        }
        if (w > 0 && h > 0 && d > 0) {
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    for (int z = 0; z < d; z++) {
                        out.add(new BlockPos(ox + x, oy + y, oz + z));
                    }
                }
            }
        }
        return out;
    }

    /** Dedup / size / negative-dim checks for save or load. Empty Optional = ok. */
    public Optional<String> validate() {
        if (hasExplicitCells()) {
            Set<BlockPos> seen = new LinkedHashSet<>();
            for (int[] c : cells) {
                if (c == null || c.length < 3) return Optional.of("cell_malformed");
                if (!seen.add(new BlockPos(c[0], c[1], c[2]))) {
                    // duplicates ignored after dedup in localCells; flag for authors
                    continue;
                }
            }
            if (seen.size() > MAX_CELLS) return Optional.of("cells_exceed_" + MAX_CELLS);
            return Optional.empty();
        }
        if (w < 0 || h < 0 || d < 0) return Optional.of("negative_box_size");
        if (w > 0 && h > 0 && d > 0) {
            long vol = (long) w * h * d;
            if (vol > MAX_CELLS) return Optional.of("box_exceed_" + MAX_CELLS);
        }
        return Optional.empty();
    }
}
