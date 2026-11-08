package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared storey spacing for mass shells, facade windows, and related openings.
 * Authority: explicit floor_height, else envelope/floor_count (clamped to min clear height),
 * else {@link InteriorSpaceDefaults#defaultStoreyHeight}.
 */
public record ResolvedFloorLayout(int floorHeight, int floorCount, List<Integer> floorYs, boolean fits) {

    public static ResolvedFloorLayout from(Component component, int envelopeHeight) {
        Map<String, Object> params = component == null ? null : component.params();
        int explicitFh = ComponentParamParsers.intParam(params, 0, "floor_height", "floorHeight");
        if (explicitFh <= 0 && component != null) {
            explicitFh = Math.max(0,
                    ProportionalFacadeCalculator.extractFloorHeightFromFeatures(component.features()));
        }
        int floorCount = ComponentParamParsers.intParam(params, 0, "floor_count", "floorCount");
        int groundClear = ComponentParamParsers.intParam(params, 0, "ground_clear_height", "groundClearHeight");
        int height = Math.max(1, envelopeHeight);

        int floorHeight;
        if (explicitFh > 0) {
            // Honour authored values even when below the default min storey band.
            floorHeight = explicitFh;
        } else if (floorCount > 0) {
            // floor_height is a storey band (slab spacing); keep ≥ band for MIN_CLEAR air.
            floorHeight = Math.max(InteriorSpaceDefaults.minStoreyBand(), height / floorCount);
        } else {
            floorHeight = InteriorSpaceDefaults.defaultStoreyHeight(height);
        }

        List<Integer> levels = new ArrayList<>();
        if (floorCount > 0) {
            // Taller lobby (ground_clear_height) + equal upper bands — avoid a leftover "half floor".
            int y = 0;
            for (int floor = 0; floor < floorCount; floor++) {
                int band = (floor == 0 && groundClear > 0) ? groundClear : floorHeight;
                if (floor > 0 && y >= height) {
                    break;
                }
                levels.add(y);
                y += Math.max(1, band);
            }
        }
        boolean fits;
        if (floorCount <= 0) {
            fits = true;
        } else if (groundClear > 0) {
            long need = (long) groundClear + (long) Math.max(0, floorCount - 1) * floorHeight;
            fits = need <= height;
        } else {
            fits = (long) floorHeight * floorCount <= height;
        }
        return new ResolvedFloorLayout(floorHeight, floorCount, List.copyOf(levels), fits);
    }

    /** Band period for window placement; never silently raised to 3. */
    public int bandHeight() {
        return Math.max(1, floorHeight);
    }
}
