package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.MassComponentKinds;
import com.formacraft.common.generation.component.util.OpenFramePolicy;
import com.formacraft.common.generation.component.util.ResolvedMassPart;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Flat roofs with an explicit {@code host_id} must sit on the host top plane that
 * {@link FlatRoofCoverageValidator} checks (enclosed = maxY-1, open-frame = maxY),
 * including cross-slot Y offsets:
 * {@code expectedRoofLocalY = hostPartTopY + hostSlotOffsetY - roofSlotOffsetY}.
 */
final class FlatRoofAttachmentRepair {
    private FlatRoofAttachmentRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) return plan;
        List<Component> out = new ArrayList<>(plan.components());
        Map<String, Slot> slots = indexSlots(plan);
        if (!applyInPlace(out, slots)) return plan;
        return PlanComponentRewrite.withComponents(plan, out);
    }

    /** Post-realign snap on the mutable prepared component list (no slot map). */
    static boolean applyInPlace(List<Component> parts) {
        return applyInPlace(parts, Map.of());
    }

    /** Post-realign snap using layout slot anchors for cross-slot Y correction. */
    static boolean applyInPlace(List<Component> parts, Map<String, Slot> slots) {
        if (parts == null || parts.isEmpty()) return false;
        Map<String, Component> masses = new HashMap<>();
        for (Component c : parts) {
            if (c == null || c.params() == null) continue;
            if (!MassComponentKinds.isHostMass(c)) continue;
            if ("plate".equalsIgnoreCase(String.valueOf(c.params().get("extrude_mode")))) continue;
            Object id = c.params().get("component_id");
            if (id != null) masses.put(String.valueOf(id), c);
        }
        if (masses.isEmpty()) return false;
        boolean changed = false;
        for (int i = 0; i < parts.size(); i++) {
            Component next = snap(parts.get(i), masses, slots);
            if (next != parts.get(i)) {
                parts.set(i, next);
                changed = true;
            }
        }
        return changed;
    }

    private static Component snap(Component roof, Map<String, Component> masses, Map<String, Slot> slots) {
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
        int hostOffsetY = slotOffsetY(slots, body.slotId());
        int roofOffsetY = slotOffsetY(slots, roof.slotId());
        for (var part : ResolvedMassPart.resolve(body)) {
            if (!part.partId().equals(partId)) continue;
            boolean open = OpenFramePolicy.isOpen(body.params());
            int hostTop = open ? part.bounds().maxY() : part.bounds().maxY() - 1;
            // Match FlatRoofCoverageValidator: compare in plan space, write back local Y.
            int expected = hostTop + hostOffsetY - roofOffsetY;
            int actual = roof.relativePosition().y();
            if (actual == expected) return roof;
            FormacraftMod.LOGGER.info(
                    "FlatRoofAttachmentRepair: snap {} Y {}→{} host={} open={} hostSlotY={} roofSlotY={}",
                    roof.params().get("component_id"), actual, expected, hostId, open,
                    hostOffsetY, roofOffsetY);
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

    private static int slotOffsetY(Map<String, Slot> slots, String slotId) {
        if (slots == null || slots.isEmpty() || slotId == null || slotId.isBlank()) return 0;
        Slot slot = slots.get(slotId);
        if (slot == null || slot.anchor() == null) return 0;
        return slot.anchor().y();
    }

    private static Map<String, Slot> indexSlots(LlmPlan plan) {
        Map<String, Slot> slots = new HashMap<>();
        if (plan == null || plan.layout() == null || plan.layout().slots() == null) {
            return slots;
        }
        for (Slot slot : plan.layout().slots()) {
            if (slot != null && slot.slotId() != null && !slot.slotId().isBlank()) {
                slots.put(slot.slotId(), slot);
            }
        }
        return slots;
    }
}
