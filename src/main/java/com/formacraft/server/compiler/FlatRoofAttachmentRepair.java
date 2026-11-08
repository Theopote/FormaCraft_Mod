package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.OpenFramePolicy;
import com.formacraft.common.generation.component.util.ResolvedMassPart;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Flat roofs with an explicit {@code host_id} must sit on the host top plane that
 * {@link FlatRoofCoverageValidator} checks (enclosed = maxY-1, open-frame = maxY).
 */
final class FlatRoofAttachmentRepair {
    private FlatRoofAttachmentRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) return plan;
        List<Component> out = new ArrayList<>(plan.components());
        if (!applyInPlace(out)) return plan;
        return PlanComponentRewrite.withComponents(plan, out);
    }

    /** Post-realign snap on the mutable prepared component list. */
    static boolean applyInPlace(List<Component> parts) {
        if (parts == null || parts.isEmpty()) return false;
        Map<String, Component> masses = new HashMap<>();
        for (Component c : parts) {
            if (c == null || c.params() == null) continue;
            if (!"MASS_MAIN".equalsIgnoreCase(c.componentType())) continue;
            Object id = c.params().get("component_id");
            if (id != null) masses.put(String.valueOf(id), c);
        }
        if (masses.isEmpty()) return false;
        boolean changed = false;
        for (int i = 0; i < parts.size(); i++) {
            Component next = snap(parts.get(i), masses);
            if (next != parts.get(i)) {
                parts.set(i, next);
                changed = true;
            }
        }
        return changed;
    }

    private static Component snap(Component roof, Map<String, Component> masses) {
        if (roof == null || roof.params() == null || roof.relativePosition() == null) return roof;
        if (!"ROOF".equalsIgnoreCase(roof.componentType())) return roof;
        Object type = roof.params().getOrDefault("roof_type", roof.params().get("roofType"));
        if (!"flat".equalsIgnoreCase(String.valueOf(type))) return roof;
        Object hostId = roof.params().get("host_id");
        if (hostId == null) hostId = roof.params().get("hostId");
        if (hostId == null) return roof;
        Component body = masses.get(String.valueOf(hostId));
        if (body == null) return roof;
        String partId = String.valueOf(roof.params().getOrDefault("host_part_id", hostId));
        for (var part : ResolvedMassPart.resolve(body)) {
            if (!part.partId().equals(partId)) continue;
            boolean open = OpenFramePolicy.isOpen(body.params());
            int expected = open ? part.bounds().maxY() : part.bounds().maxY() - 1;
            int actual = roof.relativePosition().y();
            if (actual == expected) return roof;
            FormacraftMod.LOGGER.info(
                    "FlatRoofAttachmentRepair: snap {} Y {}→{} host={} open={}",
                    roof.params().get("component_id"), actual, expected, hostId, open);
            Map<String, Object> params = new HashMap<>(roof.params());
            params.put("anchor_mode", "min_corner");
            return new Component(
                    roof.componentType(),
                    roof.slotId(),
                    new Vec3i(roof.relativePosition().x(), expected, roof.relativePosition().z()),
                    roof.dimensions(),
                    roof.features(),
                    params);
        }
        return roof;
    }
}
