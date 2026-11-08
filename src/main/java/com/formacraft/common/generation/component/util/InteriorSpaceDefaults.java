package com.formacraft.common.generation.component.util;

/**
 * Default indoor space policy when the user does not author clear-height / solidness.
 * <p>
 * {@code floor_height} / {@code ground_clear_height} in plan params are <b>storey bands</b>
 * (floor slab Y spacing). Indoor 净高 (clear air between slabs) is {@code band - 1}.
 * So a requested 净高 of 3 requires {@code floor_height ≥ 4}.
 * <ul>
 *   <li>Clear height (净高) ≥ {@link #MIN_CLEAR_HEIGHT}</li>
 *   <li>Ground storey defaults taller than upper storeys</li>
 *   <li>Enterable buildings default hollow unless solid is requested or the envelope is tiny</li>
 * </ul>
 */
public final class InteriorSpaceDefaults {
    /** Minimum usable indoor clear height in air blocks (standing room + headroom). */
    public static final int MIN_CLEAR_HEIGHT = 3;
    /** Default clear / air height for upper floors when unspecified. */
    public static final int DEFAULT_UPPER_CLEAR_HEIGHT = 3;
    /** Default clear / air height for the ground floor when unspecified. */
    public static final int DEFAULT_GROUND_CLEAR_HEIGHT = 4;
    /** Office / commercial ground-floor preferred clear height (air). */
    public static final int OFFICE_GROUND_CLEAR_HEIGHT = 4;

    private InteriorSpaceDefaults() {}

    /**
     * Convert clear-air height to the {@code floor_height} storey band MassMain uses
     * (floors placed at y % band == 0). Always ≥ {@code clear + 1}.
     */
    public static int storeyBandForClear(int clearHeight) {
        int clear = Math.max(MIN_CLEAR_HEIGHT, clearHeight);
        return clear + 1;
    }

    /** Minimum storey band that preserves {@link #MIN_CLEAR_HEIGHT} air. */
    public static int minStoreyBand() {
        return storeyBandForClear(MIN_CLEAR_HEIGHT);
    }

    /** Uniform default storey band for cornice / band heuristics (never below min clear). */
    public static int defaultStoreyHeight(int buildingHeight) {
        int clear = buildingHeight >= 8 ? DEFAULT_GROUND_CLEAR_HEIGHT : DEFAULT_UPPER_CLEAR_HEIGHT;
        return storeyBandForClear(clear);
    }

    /** Per-storey default clear-air: ground floor taller when multi-storey and unspecified. */
    public static int defaultClearHeight(int floorIndex, int floorCount) {
        if (floorIndex <= 0 && floorCount > 1) {
            return DEFAULT_GROUND_CLEAR_HEIGHT;
        }
        return DEFAULT_UPPER_CLEAR_HEIGHT;
    }

    /**
     * True when an enterable mass should be hollowed by default.
     * Opt out with {@code hollow=false} or {@code solid}/{@code solid_core=true}.
     */
    public static boolean shouldHollowByDefault(
            boolean solidRequested,
            boolean hollowExplicitFalse,
            int width,
            int depth,
            int height
    ) {
        if (solidRequested || hollowExplicitFalse) {
            return false;
        }
        return width >= 4 && depth >= 4 && height >= minStoreyBand();
    }
}
