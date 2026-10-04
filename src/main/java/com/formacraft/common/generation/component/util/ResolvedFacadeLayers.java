package com.formacraft.common.generation.component.util;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import java.util.Locale;

/** Shared layer rectangles used by body emission and roof coverage, before footprint masking. */
public final class ResolvedFacadeLayers {
    private ResolvedFacadeLayers() {}
    public static ProportionalFacadeCalculator.LayerConfig[] resolve(SemanticComponent semantic, int width, int depth, int height) {
        var source = semantic.source();
        boolean stepped = source.features() != null && source.features().stream().filter(java.util.Objects::nonNull)
                .map(f -> f.toLowerCase(Locale.ROOT)).anyMatch(f -> java.util.List.of("stepped_facade", "stepped", "setback",
                        "setbacks", "进退", "进退关系", "立面", "facade_setback", "tiered").stream().anyMatch(f::contains));
        String progression = semantic.genome() == null || semantic.genome().form == null ? null : semantic.genome().form.progression;
        stepped |= "stepping".equalsIgnoreCase(progression) || "tapering".equalsIgnoreCase(progression);
        if (stepped && height >= 3) {
            Double ratio = ComponentParamParsers.doubleOrNull(source.params(), "setback_ratio", "setbackRatio");
            if (ratio != null) ratio = Math.max(0, Math.min(1, ratio));
            else if ("stepping".equalsIgnoreCase(progression)) ratio = 0.07;
            else if ("tapering".equalsIgnoreCase(progression)) ratio = 0.05;
            double effective = ratio == null ? ProportionalFacadeCalculator.extractSetbackRatioFromFeatures(source.features()) : ratio;
            return ProportionalFacadeCalculator.calculateSteppedFacade(width,depth,height,
                    ResolvedComponentGeometry.explicitFloorHeight(source),effective);
        }
        var layers = new ProportionalFacadeCalculator.LayerConfig[height];
        for (int y = 0; y < height; y++) layers[y] = new ProportionalFacadeCalculator.LayerConfig(width,depth,0,0,0);
        return layers;
    }
    public static boolean contains(ProportionalFacadeCalculator.LayerConfig layer, int x, int z) {
        return x >= layer.xOffset && x < layer.xOffset+layer.width && z >= layer.zOffset && z < layer.zOffset+layer.depth;
    }
}
