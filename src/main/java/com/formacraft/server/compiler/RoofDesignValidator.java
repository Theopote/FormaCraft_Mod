package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.RoofPitchGeometry;
import com.formacraft.common.generation.component.util.RoofTypeCatalog;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.style.ExplicitDesignPolicy;

import java.util.List;
import java.util.Map;
import java.util.Optional;

final class RoofDesignValidator {
    private RoofDesignValidator() {}

    static Optional<CapabilityGap> check(LlmPlan plan, List<Component> components) {
        if (components == null) {
            return Optional.empty();
        }
        for (Component component : components) {
            if (component == null || component.componentType() == null) {
                continue;
            }
            String type = component.componentType().toUpperCase();
            if (!type.startsWith("ROOF")) {
                continue;
            }
            if (ExplicitDesignPolicy.roofDisabled(component)) {
                continue;
            }
            Map<String, Object> params = component.params() == null ? Map.of() : component.params();
            Optional<String> explicitType = RoofTypeCatalog.extractExplicitType(component, params);
            if (explicitType.isPresent() && !RoofTypeCatalog.isSupportedExplicit(explicitType.get())) {
                return Optional.of(new CapabilityGap(
                        "E_ROOF_TYPE_UNSUPPORTED",
                        "Unsupported roof_type: " + explicitType.get(),
                        "components[]",
                        List.of("Use a supported roof_type token or omit roof_type for style defaults.")));
            }
            Dimensions dimensions = component.dimensions();
            if (dimensions != null) {
                Optional<String> geometryConflict = RoofPitchGeometry.validateConsistency(
                        dimensions.width(), dimensions.depth(), params);
                if (geometryConflict.isPresent()) {
                    return Optional.of(new CapabilityGap(
                            "E_ROOF_GEOMETRY_CONFLICT",
                            geometryConflict.get(),
                            "components[]",
                            List.of("Align roof_height with roof_pitch_degrees or specify only one.")));
                }
            }
        }
        return Optional.empty();
    }
}
