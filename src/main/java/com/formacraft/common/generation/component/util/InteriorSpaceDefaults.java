package com.formacraft.common.generation.component.util;

/**
 * Default indoor space policy when the user does not author clear-height / solidness.
 * <ul>
 *   <li>Clear height (净高) ≥ {@link #MIN_CLEAR_HEIGHT}</li>
 *   <li>Ground storey defaults taller than upper storeys</li>
 *   <li>Enterable buildings default hollow unless solid is requested or the envelope is tiny</li>
 * </ul>
 */
public final class InteriorSpaceDefaults {
    /** Minimum usable indoor clear height in blocks (standing room + headroom). */
    public static final int MIN_CLEAR_HEIGHT = 3;
    /** Default clear / storey height for upper floors when unspecified. */
    public static final int DEFAULT_UPPER_CLEAR_HEIGHT = 3;
    /** Default clear / storey height for the ground floor when unspecified. */
    public static final int DEFAULT_GROUND_CLEAR_HEIGHT = 4;

    private InteriorSpaceDefaults() {}

    /** Uniform default storey height for cornice / band heuristics (never below min clear). */
    public static int defaultStoreyHeight(int buildingHeight) {
        if (buildingHeight >= 8) {
            return Math.max(MIN_CLEAR_HEIGHT, 4);
        }
        return MIN_CLEAR_HEIGHT;
    }

    /** Per-storey default: ground floor taller when multi-storey and unspecified. */
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
        return width >= 4 && depth >= 4 && height >= MIN_CLEAR_HEIGHT + 1;
    }
}
