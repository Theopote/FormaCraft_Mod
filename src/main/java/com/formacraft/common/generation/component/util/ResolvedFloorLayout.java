package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared storey spacing for mass shells, facade windows, and related openings.
 * <p>
 * <b>Canonical storey contract (Phase 02.3.2):</b>
 * <ul>
 *   <li>{@code floor_height} / {@code ground_clear_height} = storey <em>band</em> including
 *       one slab thickness (existing Java meaning — not clear-air alone).</li>
 *   <li>{@code slab_thickness} defaults to {@code 1}; clear-air height = band − slab_thickness
 *       (see {@link InteriorSpaceDefaults}).</li>
 *   <li>{@link #floorYs()} are slab <em>bottom</em> Y in <strong>host-local</strong> coordinates
 *       (MassMain {@code y==0} / {@code interiorFloorLevels.contains(y)}). Plan-space Y =
 *       host origin + slot + local Y.</li>
 *   <li>When {@code floor_count × bands} exceed the authored envelope, {@link #fits()} is false —
 *       callers must surface {@code E_FLOOR_LAYOUT_OVERFLOW} / conflict gap; never silently grow
 *       the envelope.</li>
 * </ul>
 * Authority: explicit floor_height, else envelope/floor_count (clamped to min clear height),
 * else {@link InteriorSpaceDefaults#defaultStoreyHeight}.
 */
public record ResolvedFloorLayout(
        int floorHeight,
        int floorCount,
        List<Integer> floorYs,
        boolean fits,
        int slabThickness
) {

    public ResolvedFloorLayout(int floorHeight, int floorCount, List<Integer> floorYs, boolean fits) {
        this(floorHeight, floorCount, floorYs, fits, 1);
    }

    public static ResolvedFloorLayout from(Component component, int envelopeHeight) {
        Map<String, Object> params = component == null ? null : component.params();
        int explicitFh = ComponentParamParsers.intParam(params, 0, "floor_height", "floorHeight");
        if (explicitFh <= 0 && component != null) {
            explicitFh = Math.max(0,
                    ProportionalFacadeCalculator.extractFloorHeightFromFeatures(component.features()));
        }
        int floorCount = ComponentParamParsers.intParam(params, 0, "floor_count", "floorCount");
        int groundClear = ComponentParamParsers.intParam(params, 0, "ground_clear_height", "groundClearHeight");
        int slabThickness = Math.max(1, ComponentParamParsers.intParam(params, 1, "slab_thickness", "slabThickness"));
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
        return new ResolvedFloorLayout(floorHeight, floorCount, List.copyOf(levels), fits, slabThickness);
    }

    /** Clear-air height for a storey band (band − slab thickness). */
    public int clearHeight() {
        return Math.max(0, floorHeight - slabThickness);
    }

    /** Band period for window placement; never silently raised to 3. */
    public int bandHeight() {
        return Math.max(1, floorHeight);
    }
}
