package com.formacraft.common.facade.rhythm;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Tiles a {@link RepeatingPattern} symmetrically from the facade center.
 */
public final class RepeatingPatternTiler {

    public record TiledAxes(
            int axisMax,
            BitSet windowAxes,
            BitSet pilasterAxes,
            BitSet entranceBayWindowAxes
    ) {}

    private RepeatingPatternTiler() {}

    public static TiledAxes tile(RepeatingPattern pattern, int axisMax) {
        BitSet windows = new BitSet();
        BitSet pilasters = new BitSet();
        BitSet entranceBay = new BitSet();
        if (pattern == null || !pattern.isValid() || axisMax <= 2) {
            return new TiledAxes(axisMax, windows, pilasters, entranceBay);
        }

        UnitMask mask = buildUnitMask(pattern);
        int unitW = pattern.unitWidth();
        int halfUnit = unitW / 2;
        int center = axisMax / 2;

        List<Integer> bayCenters = new ArrayList<>();
        bayCenters.add(center);
        for (int step = unitW; ; step += unitW) {
            boolean added = false;
            int left = center - step;
            int right = center + step;
            if (left > 0 && left < axisMax - 1) {
                bayCenters.add(left);
                added = true;
            }
            if (right > 0 && right < axisMax - 1) {
                bayCenters.add(right);
                added = true;
            }
            if (!added) {
                break;
            }
        }

        for (int bayCenter : bayCenters) {
            int unitStart = bayCenter - halfUnit;
            applyUnit(mask, unitStart, axisMax, windows, pilasters);
            if (bayCenter == center) {
                copyWindowAxesWithinUnit(mask, unitStart, axisMax, entranceBay);
            }
        }

        pilasters.set(0);
        pilasters.set(axisMax - 1);
        clearOverlaps(windows, pilasters);

        return new TiledAxes(axisMax, windows, pilasters, entranceBay);
    }

    private record UnitMask(BitSet windowLocal, BitSet pilasterLocal, int unitWidth) {}

    private static UnitMask buildUnitMask(RepeatingPattern pattern) {
        BitSet windowLocal = new BitSet();
        BitSet pilasterLocal = new BitSet();
        int offset = 0;
        for (RepeatingPatternElement element : pattern.elements()) {
            for (int i = 0; i < element.width(); i++) {
                int local = offset + i;
                if (element.isPillar()) {
                    pilasterLocal.set(local);
                } else if (element.isWindow()) {
                    if (i >= element.inset() && i < element.width() - element.inset()) {
                        windowLocal.set(local);
                    }
                }
            }
            offset += element.width();
        }
        return new UnitMask(windowLocal, pilasterLocal, pattern.unitWidth());
    }

    private static void applyUnit(UnitMask mask, int unitStart, int axisMax, BitSet windows, BitSet pilasters) {
        for (int local = 0; local < mask.unitWidth(); local++) {
            int axis = unitStart + local;
            if (axis <= 0 || axis >= axisMax - 1) {
                continue;
            }
            if (mask.windowLocal().get(local)) {
                windows.set(axis);
            }
            if (mask.pilasterLocal().get(local)) {
                pilasters.set(axis);
            }
        }
    }

    private static void copyWindowAxesWithinUnit(UnitMask mask, int unitStart, int axisMax, BitSet entranceBay) {
        for (int local = 0; local < mask.unitWidth(); local++) {
            if (!mask.windowLocal().get(local)) {
                continue;
            }
            int axis = unitStart + local;
            if (axis > 0 && axis < axisMax - 1) {
                entranceBay.set(axis);
            }
        }
    }

    private static void clearOverlaps(BitSet windows, BitSet pilasters) {
        for (int i = pilasters.nextSetBit(0); i >= 0; i = pilasters.nextSetBit(i + 1)) {
            windows.clear(i);
        }
    }
}
