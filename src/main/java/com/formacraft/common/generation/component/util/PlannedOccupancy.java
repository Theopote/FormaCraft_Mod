package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Plan-geometry occupancy from BlockPatch replay under an empty-world assumption.
 * This is <em>not</em> a real Minecraft world snapshot: {@link Cell#UNTOUCHED} means
 * never written by the plan (body-clear offline), not "verified clear in the live world".
 *
 * <p>{@link Cell#PLANNED_AIR} (explicit REMOVE/air) is distinct from {@link Cell#UNTOUCHED}.
 * Both allow body passage offline; only {@link Cell#SOLID} supports standing.
 */
public final class PlannedOccupancy {
    private static final Set<String> AIR_PATHS = Set.of("air", "cave_air", "void_air");

    public enum Cell {
        SOLID,
        PLANNED_AIR,
        UNTOUCHED
    }

    private final Map<Vec3i, Cell> cells;

    private PlannedOccupancy(Map<Vec3i, Cell> cells) {
        this.cells = cells;
    }

    public static PlannedOccupancy fromPatches(Iterable<BlockPatch> patches) {
        Map<Vec3i, Cell> cells = new HashMap<>();
        if (patches == null) {
            return new PlannedOccupancy(cells);
        }
        for (BlockPatch patch : patches) {
            if (patch == null) {
                continue;
            }
            Vec3i pos = new Vec3i(patch.dx(), patch.dy(), patch.dz());
            if (BlockPatch.REMOVE.equals(patch.action()) || isAirId(patch.targetBlock())) {
                cells.put(pos, Cell.PLANNED_AIR);
            } else {
                cells.put(pos, Cell.SOLID);
            }
        }
        return new PlannedOccupancy(cells);
    }

    /** Test helper: place known cells without patches. */
    public static PlannedOccupancy of(Map<Vec3i, Cell> cells) {
        return new PlannedOccupancy(cells == null ? Map.of() : new HashMap<>(cells));
    }

    public Cell get(int x, int y, int z) {
        Cell c = cells.get(new Vec3i(x, y, z));
        return c == null ? Cell.UNTOUCHED : c;
    }

    public Cell get(Vec3i pos) {
        if (pos == null) {
            return Cell.UNTOUCHED;
        }
        return get(pos.x(), pos.y(), pos.z());
    }

    public boolean isSolid(int x, int y, int z) {
        return get(x, y, z) == Cell.SOLID;
    }

    /** Body may pass through planned air or never-written void (hollow interiors). */
    public boolean isBodyClear(int x, int y, int z) {
        Cell c = get(x, y, z);
        return c == Cell.PLANNED_AIR || c == Cell.UNTOUCHED;
    }

    public boolean isSupport(int x, int y, int z) {
        return isSolid(x, y, z);
    }

    /**
     * Strict air-id check for plan geometry: path segment after {@code :} (strip {@code [...]})
     * must be exactly {@code air}, {@code cave_air}, or {@code void_air}.
     * Never substring-match {@code "air"} (that mis-classifies {@code *_stairs}).
     * Does not touch the block registry — safe before Minecraft bootstrap.
     */
    public static boolean isAirId(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return true;
        }
        String raw = blockId.trim().toLowerCase(Locale.ROOT);
        int bracket = raw.indexOf('[');
        if (bracket >= 0) {
            raw = raw.substring(0, bracket);
        }
        int colon = raw.indexOf(':');
        String path = colon >= 0 ? raw.substring(colon + 1) : raw;
        return AIR_PATHS.contains(path);
    }
}
