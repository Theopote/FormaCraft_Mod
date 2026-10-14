package com.formacraft.common.component.socket;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Host-authoritative cells that may be carved at a socket.
 * Distinct from {@link SizeConstraint} (install-size compatibility only).
 * <p>
 * Prefer explicit {@link #cells}; otherwise an axis-aligned box from {@link #ox}/{@link #oy}/{@link #oz}
 * + {@link #w}/{@link #h}/{@link #d} (local to the socket placement origin).
 */
public final class OpeningRegion {
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

    public boolean isEmpty() {
        if (cells != null && !cells.isEmpty()) return false;
        return w <= 0 || h <= 0 || d <= 0;
    }

    /** Local cells relative to socket origin (not yet world-transformed). */
    public Set<BlockPos> localCells() {
        Set<BlockPos> out = new LinkedHashSet<>();
        if (cells != null) {
            for (int[] c : cells) {
                if (c == null || c.length < 3) continue;
                out.add(new BlockPos(c[0], c[1], c[2]));
            }
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
}
