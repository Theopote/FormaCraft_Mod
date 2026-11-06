package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Remap unknown {@code host_id} values (e.g. literal {@code courtyard}) onto a real
 * hostable component before authored validation / emit. Mirrors the Python stage-B repair.
 */
public final class HostIdRemapRepair {
    private static final Set<String> HOSTABLE = Set.of(
            "MASS_MAIN", "TOWER", "MASS_WING", "SIDE_WING", "MASS_ANCILLARY", "MASS_SECONDARY",
            "TERRACE", "COURTYARD", "COURTYARD_SPACE", "GATE", "GATE_STRUCTURE",
            "STRUCTURE", "MODULE", "FOUNDATION", "PLAZA", "PAVING", "PATH", "ROAD", "KEEP",
            "WALL");

    private HostIdRemapRepair() {}

    public static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) return plan;
        Map<String, Component> byId = new HashMap<>();
        for (Component c : plan.components()) {
            if (c == null || c.params() == null) continue;
            Object id = c.params().get("component_id");
            if (id != null) byId.put(String.valueOf(id), c);
        }
        if (byId.isEmpty()) return plan;

        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        for (Component c : plan.components()) {
            if (c == null || c.params() == null || c.params().get("host_id") == null) {
                out.add(c);
                continue;
            }
            String host = String.valueOf(c.params().get("host_id"));
            Component hostComp = byId.get(host);
            // Known + hostable (incl. WALL for garden 漏窗) — leave alone.
            if (hostComp != null && isHostable(hostComp)) {
                out.add(c);
                continue;
            }
            String remapped = pick(host, byId);
            if (remapped == null || remapped.equals(host)) {
                out.add(c);
                continue;
            }
            Map<String, Object> params = new HashMap<>(c.params());
            params.put("host_id_mapped_from", host);
            params.put("host_id", remapped);
            FormacraftMod.LOGGER.info(
                    "HostIdRemapRepair: {} host_id {} → {}",
                    c.params().get("component_id"), host, remapped);
            out.add(new Component(c.componentType(), c.slotId(), c.relativePosition(),
                    c.dimensions(), c.features(), params));
            changed = true;
        }
        LlmPlan rewritten = changed ? PlanComponentRewrite.withComponents(plan, out) : plan;
        if (plan.hasCapabilityGap() && isUnknownHostGap(plan) && allHostsHostable(rewritten)) {
            FormacraftMod.LOGGER.info("HostIdRemapRepair: clearing remapped host capability_gap");
            return clearCapabilityGap(rewritten);
        }
        return rewritten;
    }

    private static boolean allHostsHostable(LlmPlan plan) {
        if (plan.components() == null) return true;
        Map<String, Component> byId = new HashMap<>();
        for (Component c : plan.components()) {
            if (c == null || c.params() == null) continue;
            Object id = c.params().get("component_id");
            if (id != null) byId.put(String.valueOf(id), c);
        }
        for (Component c : plan.components()) {
            if (c == null || c.params() == null) continue;
            Object host = c.params().get("host_id");
            if (host == null) continue;
            Component hostComp = byId.get(String.valueOf(host));
            if (hostComp == null || !isHostable(hostComp)) return false;
        }
        return true;
    }

    private static boolean isUnknownHostGap(LlmPlan plan) {
        String msg = "";
        if (plan.capabilityGap() != null && plan.capabilityGap().message() != null) {
            msg = plan.capabilityGap().message();
        } else if (plan.error() != null) {
            msg = plan.error();
        }
        String lower = msg.toLowerCase(Locale.ROOT);
        if (!(lower.contains("unknown") || lower.contains("not hostable"))) return false;
        return lower.contains("host_id") || lower.contains("host '") || lower.contains("host \"");
    }

    private static LlmPlan clearCapabilityGap(LlmPlan plan) {
        return new LlmPlan(
                plan.mode(),
                plan.styleProfile(),
                plan.anchor(),
                plan.globalConstraints(),
                plan.layout(),
                plan.components(),
                plan.genome(),
                plan.styleAttributes(),
                plan.proportionHints(),
                plan.alignmentAndSymmetry(),
                plan.targetSlotId(),
                plan.allowedArea(),
                plan.patch(),
                plan.planProgram(),
                plan.planSkeleton(),
                "ok",
                null,
                null,
                plan.playerFidelityNoticeZh(),
                plan.distinguishingFeatures(),
                plan.enrichmentGuard(),
                plan.planMeta(),
                plan.designSources());
    }

    private static String pick(String wanted, Map<String, Component> byId) {
        String key = wanted == null ? "" : wanted.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return null;
        for (String cid : byId.keySet()) {
            if (cid.equalsIgnoreCase(key) && isHostable(byId.get(cid))) return cid;
        }
        String best = null;
        for (Map.Entry<String, Component> e : byId.entrySet()) {
            if (!isHostable(e.getValue())) continue;
            if (e.getKey().toLowerCase(Locale.ROOT).contains(key)) {
                if (best == null || e.getKey().length() < best.length()) best = e.getKey();
            }
        }
        if (best != null) return best;
        if (key.contains("court") || key.contains("yard") || key.contains("庭") || key.contains("院")) {
            for (String prefer : List.of("COURTYARD", "COURTYARD_SPACE", "PATH", "PLAZA",
                    "MASS_MAIN", "MASS_ANCILLARY")) {
                for (Map.Entry<String, Component> e : byId.entrySet()) {
                    if (prefer.equalsIgnoreCase(e.getValue().componentType())) return e.getKey();
                }
            }
        }
        for (String prefer : List.of("MASS_MAIN", "MASS_ANCILLARY", "GATE", "STRUCTURE")) {
            for (Map.Entry<String, Component> e : byId.entrySet()) {
                if (prefer.equalsIgnoreCase(e.getValue().componentType())) return e.getKey();
            }
        }
        return null;
    }

    private static boolean isHostable(Component c) {
        if (c == null || c.componentType() == null) return false;
        return HOSTABLE.contains(c.componentType().trim().toUpperCase(Locale.ROOT));
    }
}
