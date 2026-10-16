package com.formacraft.common.generation.component.util;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Thin read-only aggregation of shared mass geometry authorities.
 * Generators should consume this (or its nested records) instead of re-deriving plan topology.
 */
public record ResolvedBuildingGeometry(
        Component host,
        ResolvedFootprint footprint,
        ResolvedFloorLayout floors,
        List<ProportionalFacadeCalculator.LayerConfig> layers
) {
    public ResolvedBuildingGeometry {
        layers = layers == null ? List.of() : List.copyOf(layers);
    }

    public static ResolvedBuildingGeometry resolve(Component component) {
        if (component == null || component.dimensions() == null) {
            return null;
        }
        SemanticComponent semantic = new SemanticComponent(
                component.componentType(), null, component, null, null, null);
        return resolve(semantic);
    }

    public static ResolvedBuildingGeometry resolve(SemanticComponent semantic) {
        if (semantic == null || semantic.source() == null || semantic.source().dimensions() == null) {
            return null;
        }
        Dimensions d = semantic.source().dimensions();
        return resolve(semantic, d.width(), d.depth(), d.height());
    }

    /** Resolve against an explicit envelope (e.g. multi-mass part size). */
    public static ResolvedBuildingGeometry resolve(SemanticComponent semantic, int width, int depth, int height) {
        if (semantic == null) {
            return null;
        }
        Component host = semantic.source();
        Map<String, Object> params = host == null ? null : host.params();
        int w = Math.max(1, width);
        int d = Math.max(1, depth);
        int h = Math.max(1, height);
        ResolvedFootprint footprint = ResolvedFootprint.resolve(semantic, params, w, d);
        ResolvedFloorLayout floors = ResolvedFloorLayout.from(host, h);
        ProportionalFacadeCalculator.LayerConfig[] layerArr =
                ResolvedFacadeLayers.resolve(semantic, w, d, h);
        List<ProportionalFacadeCalculator.LayerConfig> layers =
                layerArr == null ? List.of() : List.copyOf(Arrays.asList(layerArr));
        return new ResolvedBuildingGeometry(host, footprint, floors, layers);
    }
}
