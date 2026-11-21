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

    private static final List<String> EDITABLE_FIELDS = List.of(
            "dimensions.width", "dimensions.depth", "relative_position.x", "relative_position.z");

    public static boolean canRequest(LlmPlan plan, CapabilityGap gap) {
        if (!eligible(plan, gap) || !(gap.details().get("roof_sources") instanceof List<?> sources)) return false;
        for (Object source : sources) {
            if (!(source instanceof String id)) continue;
            int index = targetIndex(plan, gap, id);
            if (index >= 0 && EDITABLE_FIELDS.stream().anyMatch(field -> !fieldLocked(plan, plan.components().get(index), field)))
                return true;
        }
        return false;
    }

    private static boolean eligible(LlmPlan plan, CapabilityGap gap) {
        return plan != null && plan.mode() == LlmPlan.Mode.build && !plan.hasCapabilityGap()
                && plan.planProgram() == null && plan.components() != null && gap != null
                && "E_FLAT_ROOF_HOST_COVERAGE".equals(gap.code()) && gap.details() != null;
    }

    private static int targetIndex(LlmPlan plan, CapabilityGap gap, String id) {
        var details = gap.details();
        if (id.isBlank() || !(details.get("host_id") instanceof String host) || host.isBlank()
                || !(details.get("roof_sources") instanceof List<?> roofs) || !roofs.contains(id))
            return -1;
        var components = plan.components();
        int index = -1;
        for (int i = 0; i < components.size(); i++) {
            var component = components.get(i);
            if (component == null || component.params() == null
                    || !id.equals(component.params().get("component_id"))) continue;
            if (index >= 0) return -1;
            index = i;
        }
        if (index < 0) return -1;
        var roof = components.get(index);
        var params = roof.params();
        if (!"ROOF".equals(roof.componentType()) || !host.equals(params.get("host_id"))
                || !Objects.equals(params.getOrDefault("host_part_id", host), details.get("host_part_id"))
                || !"flat".equals(params.getOrDefault("roof_type", params.get("roofType")))
                || roof.dimensions() == null || roof.relativePosition() == null) return -1;
        return index;
    }

    private static boolean fieldLocked(LlmPlan plan, Component roof, String field) {
        int separator = field.indexOf('.');
        String section = field.substring(0, separator);
        String key = field.substring(separator + 1);
        return locked(plan.designSources(), field, section, key)
                || locked(plan.planMeta() == null ? null : plan.planMeta().get("design_sources"), field, section, key)
                || locked(roof.params().get("design_sources"), field, section, key);
    }

    public static Optional<LlmPlan> candidate(LlmPlan plan, CapabilityGap gap, Proposal edit) {
        if (!eligible(plan, gap) || edit == null || edit.componentId() == null || edit.field() == null
                || edit.value() == null || !EDITABLE_FIELDS.contains(edit.field())) return Optional.empty();
        int index = targetIndex(plan, gap, edit.componentId());
        if (index < 0) return Optional.empty();
        var roof = plan.components().get(index);
        if (fieldLocked(plan, roof, edit.field())) return Optional.empty();
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
        var components = new ArrayList<>(plan.components());
        components.set(index, new Component(roof.componentType(), roof.slotId(), pos, size, roof.features(), roof.params()));
        return Optional.of(PlanComponentRewrite.withComponents(plan, components));
    }

    private static boolean locked(Object sources, String... fields) {
        if (sources == null) return false;
        if (!(sources instanceof Map<?, ?> map)) return true;
        for (String field : fields) if ("USER_EXPLICIT".equals(map.get(field))) return true;
        return false;
    }
}
