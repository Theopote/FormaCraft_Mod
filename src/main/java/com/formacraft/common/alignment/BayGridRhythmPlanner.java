package com.formacraft.common.alignment;

import com.formacraft.common.facade.rhythm.RepeatingPattern;
import com.formacraft.common.facade.rhythm.RepeatingPatternTiler;
import com.formacraft.common.generation.component.util.ComponentFacadeRhythmPlanner;
import com.formacraft.common.llm.dto.GlobalConstraints;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps {@code bay_grid_x/z} (from {@link AlignmentContractEnforcer}) to facade rhythm and entrance snap.
 */
public final class BayGridRhythmPlanner {

    public record EntranceBay(int start, int width, String role) {}

    public record EntranceSnap(int axisStart, int axisSpan) {}

    private BayGridRhythmPlanner() {}

    public static BayGridResolver.ResolvedAxisGrid pickGrid(Map<String, Object> params, int axisMax) {
        if (params == null || axisMax <= 0) {
            return null;
        }
        BayGridResolver.ResolvedAxisGrid x = parseGridParam(params.get("bay_grid_x"));
        if (x != null && x.totalSpan() == axisMax) {
            return x;
        }
        BayGridResolver.ResolvedAxisGrid z = parseGridParam(params.get("bay_grid_z"));
        if (z != null && z.totalSpan() == axisMax) {
            return z;
        }
        return null;
    }

    public static BayGridResolver.ResolvedAxisGrid pickFacadeGrid(
            Map<String, Object> params,
            int width,
            int depth,
            GlobalConstraints.Facing facing
    ) {
        if (params == null || width <= 0 || depth <= 0) {
            return null;
        }
        GlobalConstraints.Facing f = facing != null ? facing : GlobalConstraints.Facing.SOUTH;
        return switch (f) {
            case NORTH, SOUTH -> {
                BayGridResolver.ResolvedAxisGrid grid = parseGridParam(params.get("bay_grid_x"));
                yield grid != null && grid.totalSpan() == width ? grid : null;
            }
            case EAST, WEST -> {
                BayGridResolver.ResolvedAxisGrid grid = parseGridParam(params.get("bay_grid_z"));
                yield grid != null && grid.totalSpan() == depth ? grid : null;
            }
        };
    }

    public static EntranceSnap snapEntrance(
            Map<String, Object> params,
            int width,
            int depth,
            GlobalConstraints.Facing facing
    ) {
        BayGridResolver.ResolvedAxisGrid grid = pickFacadeGrid(params, width, depth, facing);
        if (grid == null || grid.bays().isEmpty()) {
            return null;
        }
        EntranceBay bay = findEntranceBay(grid);
        if (bay == null || bay.width() <= 0) {
            return null;
        }
        int axisMax = switch (facing != null ? facing : GlobalConstraints.Facing.SOUTH) {
            case NORTH, SOUTH -> width;
            case EAST, WEST -> depth;
        };
        int span = Math.min(bay.width(), axisMax - bay.start());
        if (span <= 0) {
            return null;
        }
        return new EntranceSnap(bay.start(), span);
    }

    public static ComponentFacadeRhythmPlanner.RhythmPlan toRhythmPlan(
            BayGridResolver.ResolvedAxisGrid grid,
            int axisMax
    ) {
        return toRhythmPlan(grid, axisMax, null);
    }

    public static ComponentFacadeRhythmPlanner.RhythmPlan toRhythmPlan(
            BayGridResolver.ResolvedAxisGrid grid,
            int axisMax,
            RepeatingPattern pattern
    ) {
        if (grid == null || grid.bays().isEmpty() || axisMax <= 2) {
            return ComponentFacadeRhythmPlanner.RhythmPlan.inactive(axisMax);
        }

        BitSet windows = new BitSet();
        BitSet pilasters = new BitSet();
        BitSet entranceBay = new BitSet();
        EntranceBay entrance = findEntranceBay(grid);
        boolean usePattern = pattern != null && pattern.isValid();

        pilasters.set(0);
        pilasters.set(axisMax - 1);

        for (BayGridResolver.BaySpan bay : grid.bays()) {
            if (bay == null || bay.width() <= 0) {
                continue;
            }
            int bayEnd = bay.start() + bay.width() - 1;
            if (bay.start() > 0 && bay.start() < axisMax) {
                pilasters.set(bay.start());
            }
            if (bayEnd > 0 && bayEnd < axisMax - 1) {
                pilasters.set(bayEnd);
            }

            boolean isEntrance = entrance != null
                    && bay.start() == entrance.start()
                    && bay.width() == entrance.width();

            if (usePattern) {
                RepeatingPatternTiler.TiledAxes tiled = RepeatingPatternTiler.tileWithinBay(
                        pattern, axisMax, bay.start(), bay.width());
                if (isEntrance) {
                    mergeInteriorAxes(entranceBay, tiled.windowAxes(), bay.start(), bayEnd, axisMax);
                } else {
                    mergeInteriorAxes(windows, tiled.windowAxes(), bay.start(), bayEnd, axisMax);
                }
                mergeInteriorAxes(pilasters, tiled.pilasterAxes(), bay.start(), bayEnd, axisMax);
                continue;
            }

            for (int axis = bay.start(); axis <= bayEnd && axis < axisMax; axis++) {
                if (axis <= 0 || axis >= axisMax - 1) {
                    continue;
                }
                if (axis == bay.start() || axis == bayEnd) {
                    continue;
                }
                if (isEntrance) {
                    entranceBay.set(axis);
                } else if (bay.width() >= 3) {
                    windows.set(axis);
                }
            }
        }

        clearWindowPilasterOverlaps(windows, pilasters);
        clearWindowPilasterOverlaps(entranceBay, pilasters);

        if (windows.isEmpty() && entranceBay.isEmpty()) {
            return ComponentFacadeRhythmPlanner.RhythmPlan.inactive(axisMax);
        }
        String presetId = usePattern ? "BAY_GRID+REPEATING_PATTERN" : "BAY_GRID";
        return new ComponentFacadeRhythmPlanner.RhythmPlan(
                axisMax,
                windows,
                pilasters,
                entranceBay,
                presetId
        );
    }

    private static void mergeInteriorAxes(BitSet target, BitSet source, int bayStart, int bayEnd, int axisMax) {
        if (target == null || source == null) {
            return;
        }
        for (int axis = source.nextSetBit(0); axis >= 0; axis = source.nextSetBit(axis + 1)) {
            if (axis <= bayStart || axis >= bayEnd || axis <= 0 || axis >= axisMax - 1) {
                continue;
            }
            target.set(axis);
        }
    }

    private static void clearWindowPilasterOverlaps(BitSet windows, BitSet pilasters) {
        if (windows == null || pilasters == null) {
            return;
        }
        for (int axis = pilasters.nextSetBit(0); axis >= 0; axis = pilasters.nextSetBit(axis + 1)) {
            windows.clear(axis);
        }
    }

    public static EntranceBay findEntranceBay(BayGridResolver.ResolvedAxisGrid grid) {
        if (grid == null || grid.bays().isEmpty()) {
            return null;
        }
        List<BayGridResolver.BaySpan> bays = grid.bays();
        for (BayGridResolver.BaySpan bay : bays) {
            if (bay != null && isEntranceRole(bay.role())) {
                return new EntranceBay(bay.start(), bay.width(), bay.role());
            }
        }
        if (bays.size() == 3) {
            BayGridResolver.BaySpan center = bays.get(1);
            if (center != null && center.width() > 0) {
                return new EntranceBay(center.start(), center.width(), center.role());
            }
        }
        int mid = bays.size() / 2;
        BayGridResolver.BaySpan middle = bays.get(mid);
        if (middle != null && middle.width() > 0) {
            return new EntranceBay(middle.start(), middle.width(), middle.role());
        }
        BayGridResolver.BaySpan widest = bays.getFirst();
        for (BayGridResolver.BaySpan bay : bays) {
            if (bay != null && bay.width() > widest.width()) {
                widest = bay;
            }
        }
        return new EntranceBay(widest.start(), widest.width(), widest.role());
    }

    static BayGridResolver.ResolvedAxisGrid parseGridParam(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Object baysRaw = map.get("bays");
        if (!(baysRaw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        List<BayGridResolver.BaySpan> bays = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> bayMap)) {
                continue;
            }
            int start = intValue(bayMap.get("start"), 0);
            int width = intValue(bayMap.get("width"), 0);
            Object role = bayMap.get("role");
            if (width <= 0) {
                continue;
            }
            bays.add(new BayGridResolver.BaySpan(
                    start,
                    width,
                    role == null ? null : String.valueOf(role).trim()
            ));
        }
        if (bays.isEmpty()) {
            return null;
        }
        int totalSpan = intValue(map.get("total_span"), 0);
        if (totalSpan <= 0) {
            BayGridResolver.BaySpan last = bays.getLast();
            totalSpan = last.start() + last.width();
        }
        return new BayGridResolver.ResolvedAxisGrid(totalSpan, bays);
    }

    private static boolean isEntranceRole(String role) {
        if (role == null || role.isBlank()) {
            return false;
        }
        String normalized = role.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("entrance")
                || normalized.contains("portal")
                || normalized.contains("hall")
                || normalized.contains("center")
                || normalized.contains("main")
                || normalized.contains("nave");
    }

    private static int intValue(Object raw, int fallback) {
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
