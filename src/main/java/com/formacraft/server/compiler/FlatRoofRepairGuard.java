package com.formacraft.server.compiler;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.formacraft.common.llm.dto.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;

/** Applies one proposal to a copy. The candidate still needs full preview compilation. */
public final class FlatRoofRepairGuard {
    private static final int MAX_REPAIR_SPAN = 256;
    private FlatRoofRepairGuard() {}

    public record Proposal(@JsonProperty("component_id") String componentId, String field, Integer value) {}

    public static Optional<LlmPlan> candidate(LlmPlan plan, CapabilityGap gap, Proposal edit) {
        if (plan == null || plan.mode() != LlmPlan.Mode.build || plan.hasCapabilityGap()
                || plan.planProgram() != null || plan.components() == null
                || gap == null || !"E_FLAT_ROOF_HOST_COVERAGE".equals(gap.code()) || gap.details() == null
                || edit == null || edit.componentId() == null || edit.field() == null || edit.value() == null)
            return Optional.empty();
        var details = gap.details();
        if (!(details.get("host_id") instanceof String host) || host.isBlank()
                || !(details.get("roof_sources") instanceof List<?> roofs) || !roofs.contains(edit.componentId()))
            return Optional.empty();
        var components = new ArrayList<>(plan.components());
        int index = -1;
        for (int i = 0; i < components.size(); i++) {
            var component = components.get(i);
            if (component == null || component.params() == null
                    || !edit.componentId().equals(component.params().get("component_id"))) continue;
            if (index >= 0) return Optional.empty();
            index = i;
        }
        if (index < 0) return Optional.empty();
        var roof = components.get(index);
        var params = roof.params();
        if (!"ROOF".equals(roof.componentType()) || !host.equals(params.get("host_id"))
                || !Objects.equals(params.getOrDefault("host_part_id", host), details.get("host_part_id"))
                || !"flat".equals(params.getOrDefault("roof_type", params.get("roofType")))
                || roof.dimensions() == null || roof.relativePosition() == null) return Optional.empty();

        int separator = edit.field().indexOf('.');
        if (separator < 0) return Optional.empty();
        String section = edit.field().substring(0, separator);
        String key = edit.field().substring(separator + 1);
        if (locked(plan.designSources(), edit.field(), section, key)
                || locked(plan.planMeta() == null ? null : plan.planMeta().get("design_sources"), edit.field(), section, key)
                || locked(params.get("design_sources"), edit.field(), section, key)) return Optional.empty();

        var size = roof.dimensions();
        var pos = roof.relativePosition();
        int value = edit.value();
        switch (edit.field()) {
            case "dimensions.width" -> {
                if (value <= 0 || value > MAX_REPAIR_SPAN || value == size.width()) return Optional.empty();
                size = new Dimensions(value, size.depth(), size.height());
            }
            case "dimensions.depth" -> {
                if (value <= 0 || value > MAX_REPAIR_SPAN || value == size.depth()) return Optional.empty();
                size = new Dimensions(size.width(), value, size.height());
            }
            case "relative_position.x" -> {
                if (value == pos.x() || Math.abs((long) value - pos.x()) > MAX_REPAIR_SPAN) return Optional.empty();
                pos = new Vec3i(value, pos.y(), pos.z());
            }
            case "relative_position.z" -> {
                if (value == pos.z() || Math.abs((long) value - pos.z()) > MAX_REPAIR_SPAN) return Optional.empty();
                pos = new Vec3i(pos.x(), pos.y(), value);
            }
            default -> { return Optional.empty(); }
        }
        components.set(index, new Component(roof.componentType(), roof.slotId(), pos, size, roof.features(), params));
        return Optional.of(PlanComponentRewrite.withComponents(plan, components));
    }

    private static boolean locked(Object sources, String... fields) {
        if (sources == null) return false;
        if (!(sources instanceof Map<?, ?> map)) return true;
        for (String field : fields) if ("USER_EXPLICIT".equals(map.get(field))) return true;
        return false;
    }
}
