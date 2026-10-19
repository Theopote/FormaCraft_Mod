package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Existing nested masses are parts of one component, expressed relative to its min corner. */
public record ResolvedMassPart(String partId, Vec3i offset, Vec3i origin, Dimensions dimensions,
                               Map<String, Object> sourceParams) {
    public static List<ResolvedMassPart> resolve(Component component) {
        var geometry = ResolvedComponentGeometry.resolve(component);
        if (geometry == null) return List.of();
        var params = component.params();
        String id = params != null && params.get("component_id") != null ? String.valueOf(params.get("component_id"))
                : "body@" + geometry.origin().x() + ":" + geometry.origin().y() + ":" + geometry.origin().z();
        var result = new ArrayList<ResolvedMassPart>();
        result.add(new ResolvedMassPart(id, new Vec3i(0, 0, 0), geometry.origin(), geometry.dimensions(),
                params == null ? Map.of() : params));
        if (params == null || !(params.get("masses") instanceof List<?> masses)) return List.copyOf(result);
        for (int index = 0; index < masses.size(); index++) {
            if (!(masses.get(index) instanceof Map<?, ?> raw)) continue;
            @SuppressWarnings("unchecked") var part = (Map<String, Object>) raw;
            if (!(part.get("dimensions") instanceof Map<?, ?> rawDimensions)) continue;
            @SuppressWarnings("unchecked") var dims = (Map<String, Object>) rawDimensions;
            Dimensions base = geometry.dimensions();
            int width = ComponentParamParsers.intParam(dims, base.width(), "width");
            int depth = ComponentParamParsers.intParam(dims, base.depth(), "depth");
            int height = ComponentParamParsers.intParam(dims, base.height(), "height");
            if (width <= 0 || depth <= 0 || height <= 0) continue;
            @SuppressWarnings("unchecked") Map<String, Object> offset = part.get("offset") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
            var delta = new Vec3i(ComponentParamParsers.intParam(offset, 0, "x"),
                    ComponentParamParsers.intParam(offset, 0, "y"), ComponentParamParsers.intParam(offset, 0, "z"));
            var origin = new Vec3i(geometry.origin().x() + delta.x(), geometry.origin().y() + delta.y(),
                    geometry.origin().z() + delta.z());
            result.add(new ResolvedMassPart(id + "#mass_" + (index+1), delta, origin,
                    new Dimensions(width, depth, height), part));
        }
        // Contiguous centered tiers describe the envelope itself, not annexes
        // to another full-height box.
        if (result.size() >= 3) {
            var tiers = new ArrayList<>(result.subList(1,result.size()));
            tiers.sort(java.util.Comparator.comparingInt(p -> p.offset().y()));
            boolean stack = tiers.getFirst().offset().y() == 0;
            int nextY = 0;
            for (var tier : tiers) {
                if (tier.offset().x()!=0 || tier.offset().z()!=0 || tier.offset().y()!=nextY
                        || tier.dimensions().width()>geometry.dimensions().width()
                        || tier.dimensions().depth()>geometry.dimensions().depth()) stack=false;
                nextY=tier.offset().y()+tier.dimensions().height();
            }
            if (stack && Math.abs(nextY-geometry.dimensions().height())<=1) {
                var centered = new ArrayList<ResolvedMassPart>();
                for (var tier : tiers) {
                    var delta = new Vec3i((geometry.dimensions().width()-tier.dimensions().width())/2,
                            tier.offset().y(),(geometry.dimensions().depth()-tier.dimensions().depth())/2);
                    centered.add(new ResolvedMassPart(tier.partId(),delta,
                            new Vec3i(geometry.origin().x()+delta.x(),geometry.origin().y()+delta.y(),geometry.origin().z()+delta.z()),
                            tier.dimensions(),tier.sourceParams()));
                }
                return List.copyOf(centered);
            }
        }
        return List.copyOf(result);
    }

    public ComponentFootprintUtil.Bounds bounds() {
        return new ComponentFootprintUtil.Bounds(origin.x(), origin.y(), origin.z(),
                origin.x()+dimensions.width(), origin.y()+dimensions.height(), origin.z()+dimensions.depth());
    }
}
