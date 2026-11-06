package com.formacraft.common.generation.component.util;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Shared layer rectangles used by body emission and roof coverage, before footprint masking. */
public final class ResolvedFacadeLayers {
    private ResolvedFacadeLayers() {}
    public static ProportionalFacadeCalculator.LayerConfig[] resolve(SemanticComponent semantic, int width, int depth, int height) {
        var source = semantic.source();
        List<String> features = source == null ? null : source.features();
        String progression = semantic.genome() == null || semantic.genome().form == null ? null : semantic.genome().form.progression;
        boolean stepped = wantsSteppedFacade(features, progression);
        if (stepped && height >= 3) {
            Double ratio = ComponentParamParsers.doubleOrNull(source.params(), "setback_ratio", "setbackRatio");
            if (ratio != null) ratio = Math.max(0, Math.min(1, ratio));
            else if ("stepping".equalsIgnoreCase(progression)) ratio = 0.07;
            else if ("tapering".equalsIgnoreCase(progression)) ratio = 0.05;
            double effective = ratio == null ? ProportionalFacadeCalculator.extractSetbackRatioFromFeatures(features) : ratio;
            return ProportionalFacadeCalculator.calculateSteppedFacade(width,depth,height,
                    ResolvedComponentGeometry.explicitFloorHeight(source),effective);
        }
        var layers = new ProportionalFacadeCalculator.LayerConfig[height];
        for (int y = 0; y < height; y++) layers[y] = new ProportionalFacadeCalculator.LayerConfig(width,depth,0,0,0);
        return layers;
    }

    /**
     * True facade setback only. Horse-head / ma-tou gable tokens often contain the substring
     * {@code stepped} ({@code horse_head_stepped_gables}) but must NOT inset upper walls —
     * that leaves lattice windows floating one block off the façade.
     */
    static boolean wantsSteppedFacade(List<String> features, String progression) {
        boolean horseHead = hasHorseHeadToken(features);
        boolean explicit = hasExplicitFacadeSetback(features);
        if (explicit) return true;
        if (horseHead) return false;
        if ("stepping".equalsIgnoreCase(progression) || "tapering".equalsIgnoreCase(progression)) {
            return true;
        }
        if (features == null) return false;
        return features.stream().filter(java.util.Objects::nonNull)
                .map(f -> f.toLowerCase(Locale.ROOT))
                .anyMatch(f -> Stream.of(
                        "stepped_facade", "facade_setback", "setback_ratio",
                        "setbacks", "进退", "进退关系", "tiered"
                ).anyMatch(f::contains)
                        || "stepped".equals(f)
                        || "setback".equals(f));
    }

    private static boolean hasExplicitFacadeSetback(List<String> features) {
        if (features == null) return false;
        return features.stream().filter(java.util.Objects::nonNull)
                .map(f -> f.toLowerCase(Locale.ROOT))
                .anyMatch(f -> f.contains("stepped_facade")
                        || f.contains("facade_setback")
                        || f.contains("进退")
                        || f.startsWith("setback_ratio")
                        || f.contains("setback_ratio="));
    }

    private static boolean hasHorseHeadToken(List<String> features) {
        if (features == null) return false;
        return features.stream().filter(java.util.Objects::nonNull)
                .anyMatch(f -> {
                    String lower = f.toLowerCase(Locale.ROOT);
                    return lower.contains("horse_head")
                            || lower.contains("ma_tou")
                            || lower.contains("matou")
                            || f.contains("马头");
                });
    }

    public static boolean contains(ProportionalFacadeCalculator.LayerConfig layer, int x, int z) {
        return x >= layer.xOffset && x < layer.xOffset+layer.width && z >= layer.zOffset && z < layer.zOffset+layer.depth;
    }
    /** Exposed shoulder only; callers must still apply the original footprint/void mask. */
    public static boolean exposedAbove(ProportionalFacadeCalculator.LayerConfig[] layers, int y, int x, int z) {
        return y >= 0 && y + 1 < layers.length && contains(layers[y], x, z)
                && !contains(layers[y + 1], x, z);
    }

    public static boolean exposedAbove(java.util.List<ProportionalFacadeCalculator.LayerConfig> layers, int y, int x, int z) {
        return layers != null && y >= 0 && y + 1 < layers.size()
                && contains(layers.get(y), x, z)
                && !contains(layers.get(y + 1), x, z);
    }
}
