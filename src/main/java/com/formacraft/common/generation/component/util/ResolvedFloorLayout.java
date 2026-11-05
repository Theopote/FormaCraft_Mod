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
        int height = Math.max(1, envelopeHeight);

        int floorHeight;
        if (explicitFh > 0) {
            // Honour authored values even when below the default min clear height.
            floorHeight = explicitFh;
        } else if (floorCount > 0) {
            floorHeight = Math.max(InteriorSpaceDefaults.MIN_CLEAR_HEIGHT, height / floorCount);
        } else {
            floorHeight = InteriorSpaceDefaults.defaultStoreyHeight(height);
        }

        List<Integer> levels = new ArrayList<>();
        if (floorCount > 0) {
            int fitting = Math.min(floorCount, (height - 1) / floorHeight + 1);
            for (int floor = 0; floor < fitting; floor++) {
                levels.add(floor * floorHeight);
            }
        }
        boolean fits = floorCount <= 0 || (long) floorHeight * floorCount <= height;
        return new ResolvedFloorLayout(floorHeight, floorCount, List.copyOf(levels), fits);
    }

    /** Band period for window placement; never silently raised to 3. */
    public int bandHeight() {
        return Math.max(1, floorHeight);
    }
}
