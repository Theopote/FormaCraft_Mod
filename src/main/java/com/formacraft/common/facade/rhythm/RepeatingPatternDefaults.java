package com.formacraft.common.facade.rhythm;

import com.formacraft.common.proportion.ProportionCardRegistry;

import java.util.Locale;
import java.util.Map;

/**
 * Curated repeating-pattern defaults for typologies where AI did not specify a custom unit.
 */
public final class RepeatingPatternDefaults {

    private RepeatingPatternDefaults() {}

    public static RepeatingPattern suggestFromHints(Map<String, Object> hints, ProportionCardRegistry.ProportionCard card) {
        String typology = resolveTypology(hints, card);
        if (typology == null || typology.isBlank()) {
            return null;
        }
        String t = typology.toLowerCase(Locale.ROOT);
        if (containsAny(t, "classical", "monument", "palace", "gothic", "cathedral", "civic", "temple", "baroque")) {
            return RepeatingPattern.classicalPilasterBay();
        }
        if (containsAny(t, "cottage", "residential", "townhouse", "villa")) {
            return residentialBay();
        }
        return null;
    }

    /** P(1) + W(2) + P(1), unit width 4 — lighter residential cadence. */
    public static RepeatingPattern residentialBay() {
        return new RepeatingPattern(
                4,
                java.util.List.of(
                        new RepeatingPatternElement(RepeatingPatternElement.Type.PILLAR, 1, 0),
                        new RepeatingPatternElement(RepeatingPatternElement.Type.WINDOW, 2, 0),
                        new RepeatingPatternElement(RepeatingPatternElement.Type.PILLAR, 1, 0)
                )
        );
    }

    public static boolean hasPillarElements(RepeatingPattern pattern) {
        if (pattern == null) {
            return false;
        }
        for (RepeatingPatternElement element : pattern.elements()) {
            if (element != null && element.isPillar()) {
                return true;
            }
        }
        return false;
    }

    private static String resolveTypology(Map<String, Object> hints, ProportionCardRegistry.ProportionCard card) {
        if (hints != null) {
            Object typology = hints.get("typology");
            if (typology instanceof String s && !s.isBlank()) {
                return s.trim();
            }
        }
        if (card != null) {
            if (card.typology() != null && !card.typology().isBlank()) {
                return card.typology().trim();
            }
            if (card.id() != null && !card.id().isBlank()) {
                return card.id().trim();
            }
        }
        return null;
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
