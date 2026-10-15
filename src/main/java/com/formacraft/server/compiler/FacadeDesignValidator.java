package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.impl.WindowAspect;
import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.generation.component.util.ResolvedComponentGeometry;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.style.ExplicitDesignPolicy;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Compile-time fidelity gates for facade / floor layout design params. */
final class FacadeDesignValidator {
    private FacadeDesignValidator() {}

    static Optional<CapabilityGap> check(LlmPlan plan, List<Component> components) {
        if (components == null) {
            return Optional.empty();
        }
        for (Component component : components) {
            if (component == null || component.componentType() == null) {
                continue;
            }
            String type = component.componentType().toUpperCase(Locale.ROOT);
            if ("FACADE_WINDOWS".equals(type) || "WINDOW".equals(type) || "WINDOWS".equals(type)) {
                Optional<CapabilityGap> aspect = checkWindowAspect(component);
                if (aspect.isPresent()) {
                    return aspect;
                }
            }
            if ("MASS_MAIN".equals(type) || "MAIN_MASS".equals(type)
                    || "FACADE_WINDOWS".equals(type) || "MASS_SECONDARY".equals(type)
                    || "MASS_WING".equals(type) || "SIDE_WING".equals(type)) {
                Optional<CapabilityGap> floors = checkFloorLayout(component);
                if (floors.isPresent()) {
                    return floors;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<CapabilityGap> checkWindowAspect(Component component) {
        if (ExplicitDesignPolicy.windowsDisabled(component)) {
            return Optional.empty();
        }
        Map<String, Object> params = component.params() == null ? Map.of() : component.params();
        String aspectRaw = firstString(params, "window_aspect", "windowAspect");
        if (aspectRaw == null) {
            return Optional.empty();
        }
        WindowAspect aspect = WindowAspect.parse(aspectRaw);
        String windowStyle = firstString(params, "window_style", "windowStyle");
        if (WindowAspect.conflictsWithWindowStyle(aspect, windowStyle)) {
            return Optional.of(new CapabilityGap(
                    "E_WINDOW_ASPECT_CONFLICT",
                    "window_aspect=" + aspectRaw + " conflicts with window_style=" + windowStyle,
                    "components[]",
                    List.of("Align window_aspect with window_style, or omit one of them.")));
        }
        return Optional.empty();
    }

    private static Optional<CapabilityGap> checkFloorLayout(Component component) {
        Dimensions dimensions = component.dimensions();
        if (dimensions == null) {
            return Optional.empty();
        }
        Map<String, Object> params = component.params() == null ? Map.of() : component.params();
        // Slabs and connection shells inherit building metadata but do not
        // contain that building's occupied storeys.
        if ("plate".equalsIgnoreCase(String.valueOf(params.get("extrude_mode")))
                || Boolean.TRUE.equals(params.get("compiler_connector_body"))) {
            return Optional.empty();
        }
        int floorCount = ComponentParamParsers.intParam(params, "floor_count", "floorCount");
        if (floorCount <= 0) {
            return Optional.empty();
        }
        int floorHeight = ResolvedComponentGeometry.explicitFloorHeight(component);
        if (floorHeight <= 0) {
            floorHeight = Math.max(1, dimensions.height() / floorCount);
        }
        if ((long) floorHeight * floorCount > dimensions.height()) {
            return Optional.of(new CapabilityGap(
                    "E_FLOOR_LAYOUT_OVERFLOW",
                    "floor_count=" + floorCount + " with floor_height=" + floorHeight
                            + " exceeds envelope height=" + dimensions.height(),
                    "components[]",
                    List.of("Reduce floor_count, lower floor_height, or increase dimensions.height.")));
        }
        ResolvedComponentGeometry resolved = ResolvedComponentGeometry.resolve(component);
        if (resolved != null && !resolved.floorLayoutFits()) {
            return Optional.of(new CapabilityGap(
                    "E_FLOOR_LAYOUT_OVERFLOW",
                    "floor layout does not fit envelope height=" + dimensions.height(),
                    "components[]",
                    List.of("Reduce floor_count, lower floor_height, or increase dimensions.height.")));
        }
        return Optional.empty();
    }

    private static String firstString(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            Object v = params.get(key);
            if (v == null) {
                continue;
            }
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return null;
    }
}
