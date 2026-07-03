package com.formacraft.common.generation.component.util;

import java.util.Locale;

/**
 * A4: a small, pure 2D facade-pattern DSL for carving openings / tracery into solid walls.
 *
 * <p>Given a wall face parameterised by {@code (u, v)} — {@code u} runs along the facade, {@code v}
 * is height — {@link #cellAt} classifies each cell as {@link Cell#WALL solid}, {@link Cell#AIR an
 * opening}, or {@link Cell#FRAME a decorative frame}. This fills the previously-missing "perforation
 * / lattice / rose window" capability and is deterministic (no RNG), so results are reproducible.</p>
 *
 * <p>Callers opt in via a pattern name; an unknown/blank name yields {@link Cell#WALL} everywhere,
 * i.e. no behavioral change.</p>
 */
public final class FacadePatternDsl {

    public enum Cell { WALL, AIR, FRAME }

    private FacadePatternDsl() {}

    /**
     * Classify a wall cell for the given pattern.
     *
     * @param pattern pattern name (lattice/diagrid/checker/rose/arches/…); blank ⇒ all WALL
     * @param u       along-facade coordinate (0-based)
     * @param v       height coordinate (0-based)
     * @param uSize   facade length in blocks
     * @param vSize   wall height in blocks
     */
    public static Cell cellAt(String pattern, int u, int v, int uSize, int vSize) {
        if (pattern == null) {
            return Cell.WALL;
        }
        String p = pattern.trim().toLowerCase(Locale.ROOT);
        if (p.isEmpty() || p.equals("none") || p.equals("solid")) {
            return Cell.WALL;
        }
        // Keep a solid margin around the wall edges so openings never touch corners/top/bottom.
        if (u <= 0 || v <= 0 || u >= uSize - 1 || v >= vSize - 1) {
            return Cell.WALL;
        }

        if (p.contains("lattice") || p.contains("grille") || p.contains("perfor")) {
            // Regular grid of holes with solid mullions between them.
            boolean holeU = (u % 2 == 1);
            boolean holeV = (v % 2 == 1);
            if (holeU && holeV) return Cell.AIR;
            return Cell.WALL;
        }

        if (p.contains("diagrid") || p.contains("diagonal") || p.contains("diamond")) {
            int s = u + v;
            int d = u - v;
            if (s % 3 == 0 || d % 3 == 0) return Cell.FRAME;
            if ((s % 3 == 1) && (Math.floorMod(d, 3) == 1)) return Cell.AIR;
            return Cell.WALL;
        }

        if (p.contains("checker")) {
            boolean cut = ((u / 2) + (v / 2)) % 2 == 0;
            return cut ? Cell.AIR : Cell.WALL;
        }

        if (p.contains("rose") || p.contains("circle") || p.contains("oculus")) {
            // Circular opening centred on the wall, ringed by a frame.
            double cu = (uSize - 1) / 2.0;
            double cv = (vSize - 1) / 2.0;
            double radius = Math.max(1.5, Math.min(uSize, vSize) * 0.28);
            double du = u - cu;
            double dv = v - cv;
            double dist = Math.sqrt(du * du + dv * dv);
            if (dist <= radius - 1.0) return Cell.AIR;
            if (dist <= radius) return Cell.FRAME;
            return Cell.WALL;
        }

        if (p.contains("arch")) {
            // Repeating arched openings: vertical bay of air topped by a frame arch.
            int bay = 4;
            int local = Math.floorMod(u, bay);
            boolean inBay = (local == 1 || local == 2);
            if (!inBay) return Cell.WALL;
            int top = vSize - 2;
            if (v >= top - 1) return Cell.FRAME; // arch crown
            return Cell.AIR;
        }

        return Cell.WALL;
    }
}
