package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.generation.component.util.HostedPlateOccupancy;
import com.formacraft.common.generation.component.util.MassComponentKinds;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayList;
import java.util.List;

/**
 * Clamp authored hosted-plate Y into the host envelope before design validation,
 * so slight LLM off-by-one / basement slips do not hard-fail the plan.
 */
final class HostedPlateLevelRepair {
    private HostedPlateLevelRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) return plan;
        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        for (Component c : plan.components()) {
            Component repaired = repair(c, plan.components());
            if (repaired != c) changed = true;
            out.add(repaired);
        }
        if (!changed) return plan;
        return new LlmPlan(
                plan.mode(), plan.styleProfile(), plan.anchor(), plan.globalConstraints(), plan.layout(),
                out, plan.genome(), plan.styleAttributes(), plan.proportionHints(),
                plan.alignmentAndSymmetry(), plan.targetSlotId(), plan.allowedArea(), plan.patch(),
                plan.planProgram(), plan.planSkeleton(), plan.planStatus(), plan.error(),
                plan.capabilityGap(), plan.playerFidelityNoticeZh(), plan.distinguishingFeatures(),
                plan.enrichmentGuard(), plan.planMeta(), plan.designSources());
    }

    private static Component repair(Component plate, List<Component> components) {
        if (plate == null || plate.params() == null || plate.relativePosition() == null) return plate;
        if (!MassComponentKinds.isHostMass(plate)
                || !"plate".equalsIgnoreCase(String.valueOf(plate.params().get("extrude_mode")))) {
            return plate;
        }
        HostedPlateOccupancy.PlateRole role;
        try {
            role = HostedPlateOccupancy.resolveRole(plate.params());
        } catch (RuntimeException ignored) {
            return plate;
        }
        if (role == HostedPlateOccupancy.PlateRole.BRIDGE) return plate;
        Object hostId = plate.params().get("host_id");
        if (hostId == null) hostId = plate.params().get("hostId");
        if (hostId == null) return plate;
        Component host = null;
        for (Component c : components) {
            if (c == null || c.params() == null || c.dimensions() == null) continue;
            if (hostId.equals(c.params().get("component_id"))) {
                host = c;
                break;
            }
        }
        if (host == null) return plate;
        Vec3i origin = ComponentFootprintUtil.resolveMinCornerOrigin(host);
        if (origin == null) return plate;
        var envelope = HostedPlateOccupancy.levelEnvelope(
                role, origin.y(), HostedPlateDesignValidator.hostedEnvelopeHeight(host, components));
        if (envelope == null || envelope.contains(plate.relativePosition().y())) return plate;
        int y = plate.relativePosition().y();
        int clamped = Math.max(envelope.minInclusive(), Math.min(envelope.maxInclusive(), y));
        FormacraftMod.LOGGER.info(
                "HostedPlateLevelRepair: Y={} → {} ({} envelope [{},{}])",
                y, clamped, role, envelope.minInclusive(), envelope.maxInclusive());
        Vec3i p = plate.relativePosition();
        return new Component(plate.componentType(), plate.slotId(),
                new Vec3i(p.x(), clamped, p.z()),
                plate.dimensions(), plate.features(), plate.params());
    }
}
