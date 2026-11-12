package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
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
        // Attic flat roofs are often host_id'd to the primary arch body while authored Y sits on
        // MASS_SECONDARY attic — prefer that storey instead of snapping the roof down.
        Component snapHost = preferAtticHost(roof, body, masses, slots);
        String snapHostId = String.valueOf(snapHost.params() == null
                ? hostId : snapHost.params().getOrDefault("component_id", hostId));
        String partId = String.valueOf(roof.params().getOrDefault("host_part_id",
                snapHost == body ? hostId : snapHostId));
        int hostOffsetY = slotOffsetY(slots, snapHost.slotId());
        int roofOffsetY = slotOffsetY(slots, roof.slotId());
        var parts = ResolvedMassPart.resolve(snapHost);
        for (var part : parts) {
            boolean partMatch = part.partId().equals(partId)
                    || (snapHost != body && parts.size() == 1);
            if (!partMatch) continue;
            boolean open = OpenFramePolicy.isOpen(snapHost.params());
            int hostTop = open ? part.bounds().maxY() : part.bounds().maxY() - 1;
            // Match FlatRoofCoverageValidator: compare in plan space, write back local Y.
            int expected = hostTop + hostOffsetY - roofOffsetY;
            int actual = roof.relativePosition().y();
            if (actual == expected && snapHost == body) return roof;
            if (actual == expected && snapHost != body) {
                // Height already correct — still sync host identity when attic was preferred.
                Map<String, Object> params = new HashMap<>(roof.params());
                params.put("host_id", snapHostId);
                params.put("host_part_id", snapHostId);
                params.put("anchor_mode", "min_corner");
                return new Component(
                        roof.componentType(), roof.slotId(), roof.relativePosition(),
                        roof.dimensions(), roof.features(), params);
            }
            if (actual > expected + 1 && snapHost == body
                    && hasUpperStoreyAbove(body, masses, slots, expected)) {
                return roof;
            }
            FormacraftMod.LOGGER.info(
                    "FlatRoofAttachmentRepair: snap {} Y {}→{} host={} open={} hostSlotY={} roofSlotY={}",
                    roof.params().get("component_id"), actual, expected, snapHostId, open,
                    hostOffsetY, roofOffsetY);
            Map<String, Object> params = new HashMap<>(roof.params());
            params.put("anchor_mode", "min_corner");
            if (snapHost != body) {
                params.put("host_id", snapHostId);
                params.put("host_part_id", snapHostId);
            }
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

    /**
     * Prefer an attic / upper storey only when it actually covers the roof in plan XZ
     * (slot-unified). Name/Y cues alone must not rebind to a tall neighbor.
     */
    private static Component preferAtticHost(
            Component roof, Component body, Map<String, Component> masses, Map<String, Slot> slots) {
        var roofBounds = planBounds(roof, slots);
        if (roofBounds == null) return body;
        int roofPlanY = roof.relativePosition().y() + slotOffsetY(slots, roof.slotId());
        Component best = body;
        int bestTop = Integer.MIN_VALUE;
        String bodyId = body.params() == null ? ""
                : String.valueOf(body.params().getOrDefault("component_id", ""));
        for (Component m : masses.values()) {
            if (m == null || m == body || m.dimensions() == null || m.relativePosition() == null) continue;
            if (!"MASS_SECONDARY".equalsIgnoreCase(m.componentType())
                    && !"MASS_ANCILLARY".equalsIgnoreCase(m.componentType())) {
                continue;
            }
            String role = String.valueOf(m.params() == null ? "" : m.params().getOrDefault("role", ""));
            String id = String.valueOf(m.params() == null ? "" : m.params().getOrDefault("component_id", ""));
            String idLower = id.toLowerCase(java.util.Locale.ROOT);
            boolean atticCue = "attic".equalsIgnoreCase(role)
                    || idLower.contains("attic")
                    || idLower.contains("storey");
            Object host = m.params() == null ? null : m.params().get("host_id");
            if (host == null && m.params() != null) host = m.params().get("hostId");
            boolean bound = host != null && String.valueOf(host).equals(bodyId);
            if (!atticCue && !bound) continue;
            var hostBounds = planBounds(m, slots);
            if (hostBounds == null || !xzCovers(hostBounds, roofBounds)) continue;
            int top = m.relativePosition().y() + slotOffsetY(slots, m.slotId())
                    + Math.max(1, m.dimensions().height()) - 1;
            if (top < roofPlanY && top > bestTop) {
                bestTop = top;
                best = m;
            }
        }
        return best;
    }

    private static ComponentFootprintUtil.Bounds planBounds(Component c, Map<String, Slot> slots) {
        var local = ComponentFootprintUtil.bounds(c);
        if (local == null) return null;
        int ox = slotOffsetX(slots, c.slotId());
        int oy = slotOffsetY(slots, c.slotId());
        int oz = slotOffsetZ(slots, c.slotId());
        if (ox == 0 && oy == 0 && oz == 0) return local;
        return new ComponentFootprintUtil.Bounds(
                local.minX() + ox, local.minY() + oy, local.minZ() + oz,
                local.maxX() + ox, local.maxY() + oy, local.maxZ() + oz);
    }

    /** Host XZ must cover the majority of the roof footprint (not a distant tall annex). */
    private static boolean xzCovers(
            ComponentFootprintUtil.Bounds host, ComponentFootprintUtil.Bounds roof) {
        int overlapMinX = Math.max(host.minX(), roof.minX());
        int overlapMaxX = Math.min(host.maxX(), roof.maxX());
        int overlapMinZ = Math.max(host.minZ(), roof.minZ());
        int overlapMaxZ = Math.min(host.maxZ(), roof.maxZ());
        int overlapW = Math.max(0, overlapMaxX - overlapMinX);
        int overlapD = Math.max(0, overlapMaxZ - overlapMinZ);
        int roofArea = Math.max(1, roof.width() * roof.depth());
        return overlapW * overlapD * 2 >= roofArea;
    }

    private static boolean hasUpperStoreyAbove(
            Component body, Map<String, Component> masses, Map<String, Slot> slots, int bodyTopLocal) {
        String bodyId = body.params() == null ? ""
                : String.valueOf(body.params().getOrDefault("component_id", ""));
        int bodyTopPlan = bodyTopLocal + slotOffsetY(slots, body.slotId());
        for (Component m : masses.values()) {
            if (m == null || m == body || m.relativePosition() == null) continue;
            Object host = m.params() == null ? null : m.params().get("host_id");
            if (host == null && m.params() != null) host = m.params().get("hostId");
            if (host == null || !String.valueOf(host).equals(bodyId)) continue;
            int my = m.relativePosition().y() + slotOffsetY(slots, m.slotId());
            if (my >= bodyTopPlan) return true;
        }
        return false;
    }

    private static int slotOffsetX(Map<String, Slot> slots, String slotId) {
        if (slots == null || slots.isEmpty() || slotId == null || slotId.isBlank()) return 0;
        Slot slot = slots.get(slotId);
        if (slot == null || slot.anchor() == null) return 0;
        return slot.anchor().x();
    }

    private static int slotOffsetY(Map<String, Slot> slots, String slotId) {
        if (slots == null || slots.isEmpty() || slotId == null || slotId.isBlank()) return 0;
        Slot slot = slots.get(slotId);
        if (slot == null || slot.anchor() == null) return 0;
        return slot.anchor().y();
    }

    private static int slotOffsetZ(Map<String, Slot> slots, String slotId) {
        if (slots == null || slots.isEmpty() || slotId == null || slotId.isBlank()) return 0;
        Slot slot = slots.get(slotId);
        if (slot == null || slot.anchor() == null) return 0;
        return slot.anchor().z();
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
