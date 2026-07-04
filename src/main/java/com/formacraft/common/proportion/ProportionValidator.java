package com.formacraft.common.proportion;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Validates LlmPlan proportion_hints and component dimensions against proportion cards.
 * Used by eval and optional Compiler post-check (SOFT warnings).
 */
public final class ProportionValidator {

    public record CheckResult(String name, boolean passed, String detail) {}

    private ProportionValidator() {}

    @SuppressWarnings("unchecked")
    public static List<CheckResult> validatePlan(
            Map<String, Object> plan,
            ProportionCardRegistry.ProportionCard card
    ) {
        if (plan == null || card == null) {
            return List.of();
        }
        Object hintsObj = plan.get("proportion_hints");
        Map<String, Object> hints = hintsObj instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();

        List<Map<String, Object>> comps = extractComponents(plan);
        Map<String, Object> mainMass = findMainMass(comps);

        List<CheckResult> out = new java.util.ArrayList<>();

        out.add(checkHasProportionHints(hints, card));
        out.add(checkVoidRatio(comps, card, hints));
        out.add(checkEnclosureComponents(comps, card));
        out.addAll(checkRatioHints(hints, card));
        if (mainMass != null) {
            out.addAll(checkDerivedRatios(mainMass, card));
        }
        return out;
    }

    private static CheckResult checkHasProportionHints(
            Map<String, Object> hints,
            ProportionCardRegistry.ProportionCard card
    ) {
        boolean ok = !hints.isEmpty() || card.typology() == null;
        return new CheckResult(
                "has_proportion_hints",
                ok,
                ok ? "proportion_hints present" : "missing proportion_hints for " + card.typology()
        );
    }

    private static CheckResult checkVoidRatio(
            List<Map<String, Object>> comps,
            ProportionCardRegistry.ProportionCard card,
            Map<String, Object> hints
    ) {
        double maxVoid = card.openingGrammar() != null
                ? card.openingGrammar().maxVoidRatio() : 0.35;
        for (Map<String, Object> c : comps) {
            Map<String, Object> params = params(c);
            double vr = paramDouble(params, "void_ratio", "voidRatio");
            if (vr > maxVoid + 0.05) {
                return new CheckResult(
                        "void_ratio_within_typology",
                        false,
                        String.format(Locale.ROOT, "void_ratio=%.2f exceeds max %.2f for %s",
                                vr, maxVoid, card.typology())
                );
            }
        }
        Object hintVoid = hints.get("max_void_ratio");
        if (hintVoid instanceof Number n && n.doubleValue() > maxVoid + 0.05) {
            return new CheckResult(
                    "void_ratio_within_typology",
                    false,
                    "proportion_hints.max_void_ratio exceeds card max"
            );
        }
        return new CheckResult("void_ratio_within_typology", true, "void_ratio ok");
    }

    private static CheckResult checkEnclosureComponents(
            List<Map<String, Object>> comps,
            ProportionCardRegistry.ProportionCard card
    ) {
        if (card.openingGrammar() == null) {
            return new CheckResult("enclosure_components", true, "n/a");
        }
        double minCov = card.openingGrammar().minEnclosureCoverage();
        long enclosure = comps.stream()
                .filter(c -> isEnclosureType(type(c)))
                .count();
        boolean ok = enclosure >= 1 || comps.size() >= 2;
        return new CheckResult(
                "enclosure_components",
                ok,
                ok ? "enclosure types=" + enclosure : "need WALL/MASS/TOWER for min_coverage " + minCov
        );
    }

    private static List<CheckResult> checkRatioHints(
            Map<String, Object> hints,
            ProportionCardRegistry.ProportionCard card
    ) {
        List<CheckResult> out = new java.util.ArrayList<>();
        if (card.ratios() == null) {
            return out;
        }
        for (Map.Entry<String, ProportionCardRegistry.RatioSpec> e : card.ratios().entrySet()) {
            Object v = hints.get(e.getKey());
            if (!(v instanceof Number n)) {
                continue;
            }
            ProportionCardRegistry.RatioSpec spec = e.getValue();
            double val = n.doubleValue();
            boolean ok = val >= spec.min() - 1e-6 && val <= spec.max() + 1e-6;
            out.add(new CheckResult(
                    "ratio_hint_" + e.getKey(),
                    ok,
                    String.format(Locale.ROOT, "%s=%.3f range [%.3f,%.3f]", e.getKey(), val, spec.min(), spec.max())
            ));
        }
        return out;
    }

    private static List<CheckResult> checkDerivedRatios(
            Map<String, Object> mainMass,
            ProportionCardRegistry.ProportionCard card
    ) {
        List<CheckResult> out = new java.util.ArrayList<>();
        Map<String, Object> dims = dimensions(mainMass);
        int w = intDim(dims, "width");
        int d = intDim(dims, "depth");
        int h = intDim(dims, "height");
        if (w <= 0 || h <= 0) {
            return out;
        }

        ProportionCardRegistry.RatioSpec htw = card.ratios().get("height_to_width");
        if (htw != null && w > 0) {
            double ratio = h / (double) w;
            boolean ok = ratio >= htw.min() && ratio <= htw.max();
            out.add(new CheckResult(
                    "derived_height_to_width",
                    ok,
                    String.format(Locale.ROOT, "h/w=%.3f range [%.2f,%.2f]", ratio, htw.min(), htw.max())
            ));
        }

        ProportionCardRegistry.RatioSpec dtw = card.ratios().get("depth_to_width");
        if (dtw != null && w > 0 && d > 0) {
            double ratio = d / (double) w;
            boolean ok = ratio >= dtw.min() && ratio <= dtw.max();
            out.add(new CheckResult(
                    "derived_depth_to_width",
                    ok,
                    String.format(Locale.ROOT, "d/w=%.3f range [%.2f,%.2f]", ratio, dtw.min(), dtw.max())
            ));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractComponents(Map<String, Object> plan) {
        Object comps = plan.get("components");
        if (!(comps instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(o -> o instanceof Map<?, ?>)
                .map(o -> (Map<String, Object>) o)
                .toList();
    }

    private static Map<String, Object> findMainMass(List<Map<String, Object>> comps) {
        for (Map<String, Object> c : comps) {
            String t = type(c);
            if ("MASS_MAIN".equals(t) || "HOUSE".equals(t) || "BUILDING".equals(t)) {
                return c;
            }
        }
        return comps.isEmpty() ? null : comps.get(0);
    }

    private static boolean isEnclosureType(String t) {
        if (t == null) return false;
        return t.startsWith("MASS_") || "WALL".equals(t) || t.startsWith("TOWER")
                || "HOUSE".equals(t) || "BUILDING".equals(t) || "CASTLE".equals(t);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> params(Map<String, Object> c) {
        Object p = c.get("params");
        return p instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dimensions(Map<String, Object> c) {
        Object d = c.get("dimensions");
        return d instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String type(Map<String, Object> c) {
        Object t = c.get("component_type");
        return t instanceof String s ? s.trim().toUpperCase(Locale.ROOT) : "";
    }

    private static int intDim(Map<String, Object> dims, String key) {
        Object v = dims.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }

    private static double paramDouble(Map<String, Object> params, String... keys) {
        for (String k : keys) {
            Object v = params.get(k);
            if (v instanceof Number n) {
                return n.doubleValue();
            }
        }
        return 0;
    }
}
