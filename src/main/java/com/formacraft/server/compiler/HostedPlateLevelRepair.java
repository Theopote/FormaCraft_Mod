package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.generation.component.util.HostedPlateOccupancy;
import com.formacraft.common.generation.component.util.MassComponentKinds;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayList;
import java.util.List;

/**
 * Clamp authored hosted-plate Y into the host envelope before design validation,
 * so slight LLM off-by-one / basement slips do not hard-fail the plan.
 * <p>
 * Bridge plates are also stretched in XZ after mass separation so they still touch
 * both {@code host_id} and {@code target_host_id} (avoids {@code E_BRIDGE_ENDPOINT_NO_CONTACT}).
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
        if (role == HostedPlateOccupancy.PlateRole.BRIDGE) {
            return stretchBridgeToEndpoints(plate, components);
        }
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

    /**
     * After overlapping halls are separated, authored bridge stubs often sit on only one
     * endpoint. Extend the plate AABB along the host↔target axis until both footprints are
     * within one cell (matches {@link HostedPlateOccupancy#touchesEndpoint}).
     */
    private static Component stretchBridgeToEndpoints(Component plate, List<Component> components) {
        String hostId = plate.params().get("host_id") == null ? null
                : String.valueOf(plate.params().get("host_id"));
        if (hostId == null && plate.params().get("hostId") != null) {
            hostId = String.valueOf(plate.params().get("hostId"));
        }
        var targetId = HostedPlateOccupancy.targetHostId(plate.params());
        if (hostId == null || targetId.isEmpty() || plate.dimensions() == null) return plate;
        var host = HostedPlateDesignValidator.findBridgeEndpoint(hostId, components);
        var target = HostedPlateDesignValidator.findBridgeEndpoint(targetId.get(), components);
        if (host.isEmpty() || target.isEmpty()) return plate;
        var pb = ComponentFootprintUtil.bounds(plate);
        var hb = ComponentFootprintUtil.bounds(host.get());
        var tb = ComponentFootprintUtil.bounds(target.get());
        if (pb == null || hb == null || tb == null) return plate;
        boolean hostTouch = HostedPlateOccupancy.touchesEndpoint(pb, hb);
        boolean targetTouch = HostedPlateOccupancy.touchesEndpoint(pb, tb);
        if (hostTouch && targetTouch) {
            return plate;
        }
        // Only close a separation gap when the plate already meets one endpoint.
        // Intentional no-contact fixtures (touches neither) must keep failing.
        if (!hostTouch && !targetTouch) {
            return plate;
        }

        int hcx = (hb.minX() + hb.maxX()) / 2;
        int hcz = (hb.minZ() + hb.maxZ()) / 2;
        int tcx = (tb.minX() + tb.maxX()) / 2;
        int tcz = (tb.minZ() + tb.maxZ()) / 2;
        boolean alongZ = Math.abs(tcz - hcz) >= Math.abs(tcx - hcx);
        // MassFootprintSeparation leaves small gaps; distant authored misses stay hard errors.
        int hostGap = alongZ
                ? Math.max(0, Math.max(hb.minZ(), tb.minZ()) - Math.min(hb.maxZ(), tb.maxZ()))
                : Math.max(0, Math.max(hb.minX(), tb.minX()) - Math.min(hb.maxX(), tb.maxX()));
        if (hostGap > 8) {
            return plate;
        }

        int minX = pb.minX();
        int maxX = pb.maxX();
        int minZ = pb.minZ();
        int maxZ = pb.maxZ();
        if (alongZ) {
            int lo = Math.min(hb.maxZ(), tb.maxZ());
            int hi = Math.max(hb.minZ(), tb.minZ());
            minZ = Math.min(minZ, lo - 1);
            maxZ = Math.max(maxZ, hi + 1);
            int overlapMinX = Math.max(hb.minX(), tb.minX());
            int overlapMaxX = Math.min(hb.maxX(), tb.maxX());
            if (overlapMaxX > overlapMinX) {
                int w = Math.max(1, maxX - minX);
                int mid = (overlapMinX + overlapMaxX) / 2;
                minX = mid - w / 2;
                maxX = minX + w;
            }
        } else {
            int lo = Math.min(hb.maxX(), tb.maxX());
            int hi = Math.max(hb.minX(), tb.minX());
            minX = Math.min(minX, lo - 1);
            maxX = Math.max(maxX, hi + 1);
            int overlapMinZ = Math.max(hb.minZ(), tb.minZ());
            int overlapMaxZ = Math.min(hb.maxZ(), tb.maxZ());
            if (overlapMaxZ > overlapMinZ) {
                int d = Math.max(1, maxZ - minZ);
                int mid = (overlapMinZ + overlapMaxZ) / 2;
                minZ = mid - d / 2;
                maxZ = minZ + d;
            }
        }
        int width = Math.max(1, maxX - minX);
        int depth = Math.max(1, maxZ - minZ);
        Vec3i p = plate.relativePosition();
        FormacraftMod.LOGGER.info(
                "HostedPlateLevelRepair: stretch bridge id={} {}x{}@{},{} → {}x{}@{},{}",
                plate.params().get("component_id"),
                plate.dimensions().width(), plate.dimensions().depth(), p.x(), p.z(),
                width, depth, minX, minZ);
        return new Component(
                plate.componentType(),
                plate.slotId(),
                new Vec3i(minX, p.y(), minZ),
                new Dimensions(width, depth, Math.max(1, plate.dimensions().height())),
                plate.features(),
                plate.params());
    }
}
