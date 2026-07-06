package com.formacraft.common.facade.rhythm;

import java.util.List;

/**
 * Parametric facade rhythm unit tiled symmetrically along a wall axis.
 * <p>
 * Example: pillar(1) + window(3) + pillar(1) with {@code unitWidth = 5}.
 */
public record RepeatingPattern(
        int unitWidth,
        List<RepeatingPatternElement> elements
) {
    public static final String PRESET_CLASSICAL_PILASTER_BAY = "CLASSICAL_PILASTER_BAY";

    public RepeatingPattern {
        unitWidth = Math.max(0, unitWidth);
        elements = elements == null ? List.of() : List.copyOf(elements);
    }

    public boolean isValid() {
        if (unitWidth <= 0 || elements.isEmpty()) {
            return false;
        }
        int sum = 0;
        for (RepeatingPatternElement element : elements) {
            if (element == null || element.width() <= 0) {
                return false;
            }
            if (element.isWindow() && element.inset() * 2 >= element.width()) {
                return false;
            }
            sum += element.width();
        }
        return sum == unitWidth;
    }

    /** P(1) + W(3) + P(1), unit width 5 — matches {@link #PRESET_CLASSICAL_PILASTER_BAY}. */
    public static RepeatingPattern classicalPilasterBay() {
        return new RepeatingPattern(
                5,
                List.of(
                        new RepeatingPatternElement(RepeatingPatternElement.Type.PILLAR, 1, 0),
                        new RepeatingPatternElement(RepeatingPatternElement.Type.WINDOW, 3, 0),
                        new RepeatingPatternElement(RepeatingPatternElement.Type.PILLAR, 1, 0)
                )
        );
    }

    public static RepeatingPattern fromPresetId(String presetId) {
        if (presetId == null || presetId.isBlank()) {
            return null;
        }
        String id = presetId.trim().toUpperCase(java.util.Locale.ROOT);
        if (PRESET_CLASSICAL_PILASTER_BAY.equals(id)
                || "VERTICAL_BAY".equals(id)
                || "PILASTER_BAY".equals(id)
                || "CLASSICAL_BAY".equals(id)) {
            return classicalPilasterBay();
        }
        return null;
    }
}
