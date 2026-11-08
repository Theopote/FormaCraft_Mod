package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.generation.component.util.MassComponentKinds;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Uniquify duplicate hostable {@code params.component_id} values before
 * {@link HostedPlateDesignValidator} hard-fails with {@code E_DUPLICATE_COMPONENT_ID}.
 * <p>
 * Renames alone are unsafe: roofs / entrances still reference the old id. This repair
 * only uniquifies when every {@code host_id} / {@code target_host_id} pointing at a
 * duplicated id can be rebound to exactly one surviving mass via slot, footprint, or
 * id-family cues. Ambiguous ownership leaves the plan unchanged so authored validation
 * can surface {@code E_DUPLICATE_COMPONENT_ID} instead of silently rebinding the wrong host.
 */
final class DuplicateComponentIdRepair {
    private static final String[] HOST_REF_KEYS = {
            "host_id", "hostId", "target_host_id", "targetHostId"
    };

    private DuplicateComponentIdRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) {
            return plan;
        }
        List<Component> components = plan.components();
        Map<String, List<Integer>> hostableById = new HashMap<>();
        for (int i = 0; i < components.size(); i++) {
            Component c = components.get(i);
            if (!isHostableMass(c)) continue;
            String id = componentId(c);
            if (id == null) continue;
            hostableById.computeIfAbsent(id, k -> new ArrayList<>()).add(i);
        }

        Map<Integer, String> renames = new HashMap<>();
        Set<String> reserved = new HashSet<>();
        for (Component c : components) {
            String id = componentId(c);
            if (id != null) reserved.add(id);
        }
        int dupIndex = 0;
        for (Map.Entry<String, List<Integer>> e : hostableById.entrySet()) {
            List<Integer> idxs = e.getValue();
            if (idxs.size() < 2) continue;
            String id = e.getKey();
            // Keep the first occurrence; rename subsequent hostable masses.
            for (int n = 1; n < idxs.size(); n++) {
                String unique;
                do {
                    dupIndex++;
                    unique = id + "_" + dupIndex;
                } while (reserved.contains(unique));
                reserved.add(unique);
                renames.put(idxs.get(n), unique);
            }
        }
        if (renames.isEmpty()) {
            return plan;
        }

        // Candidates that share a duplicated original id: keeper (no rename) + renamed siblings.
        Map<String, List<HostCandidate>> candidatesByOldId = new HashMap<>();
        for (Map.Entry<String, List<Integer>> e : hostableById.entrySet()) {
            if (e.getValue().size() < 2) continue;
            String oldId = e.getKey();
            List<HostCandidate> cands = new ArrayList<>();
            for (int idx : e.getValue()) {
                String finalId = renames.getOrDefault(idx, oldId);
                cands.add(new HostCandidate(idx, oldId, finalId, components.get(idx)));
            }
            candidatesByOldId.put(oldId, cands);
        }

        // Resolve every satellite reference that still names a duplicated id.
        Map<Integer, Map<String, String>> refUpdates = new HashMap<>();
        for (int i = 0; i < components.size(); i++) {
            if (renames.containsKey(i)) continue; // masses themselves are rewritten below
            Component c = components.get(i);
            if (c == null || c.params() == null) continue;
            for (String key : HOST_REF_KEYS) {
                Object raw = c.params().get(key);
                if (raw == null) continue;
                String wanted = String.valueOf(raw).trim();
                if (wanted.isEmpty() || "null".equalsIgnoreCase(wanted)) continue;
                List<HostCandidate> cands = candidatesByOldId.get(wanted);
                if (cands == null || cands.isEmpty()) continue;
                HostCandidate chosen = resolveHost(c, cands);
                if (chosen == null) {
                    FormacraftMod.LOGGER.warn(
                            "DuplicateComponentIdRepair: ambiguous host_id='{}' on {} — leave duplicates for E_DUPLICATE_COMPONENT_ID",
                            wanted, componentId(c));
                    return plan;
                }
                if (!chosen.finalId.equals(wanted)) {
                    refUpdates.computeIfAbsent(i, k -> new HashMap<>()).put(key, chosen.finalId);
                }
            }
        }

        List<Component> out = new ArrayList<>(components.size());
        boolean changed = false;
        for (int i = 0; i < components.size(); i++) {
            Component c = components.get(i);
            String newId = renames.get(i);
            Map<String, String> refs = refUpdates.get(i);
            if (newId == null && (refs == null || refs.isEmpty())) {
                out.add(c);
                continue;
            }
            Map<String, Object> params = new HashMap<>(c.params());
            if (newId != null) {
                String oldId = componentId(c);
                params.put("component_id", newId);
                params.put("component_id_mapped_from", oldId);
                FormacraftMod.LOGGER.info(
                        "DuplicateComponentIdRepair: component_id {} → {}", oldId, newId);
            }
            if (refs != null) {
                for (Map.Entry<String, String> ref : refs.entrySet()) {
                    Object prev = params.get(ref.getKey());
                    params.put(ref.getKey(), ref.getValue());
                    // Keep host_part_id in sync when it still names the old mass id.
                    if (("host_id".equals(ref.getKey()) || "hostId".equals(ref.getKey()))
                            && prev != null
                            && String.valueOf(prev).equals(String.valueOf(params.get("host_part_id")))) {
                        params.put("host_part_id", ref.getValue());
                    }
                    FormacraftMod.LOGGER.info(
                            "DuplicateComponentIdRepair: rebind {}.{} {} → {}",
                            componentId(c), ref.getKey(), prev, ref.getValue());
                }
            }
            out.add(new Component(
                    c.componentType(), c.slotId(), c.relativePosition(),
                    c.dimensions(), c.features(), params));
            changed = true;
        }
        return changed ? PlanComponentRewrite.withComponents(plan, out) : plan;
    }

    /**
     * Pick the unique best host among duplicate candidates. Returns null when scores tie
     * or no candidate has a positive ownership cue.
     */
    private static HostCandidate resolveHost(Component sat, List<HostCandidate> cands) {
        HostCandidate best = null;
        int bestScore = Integer.MIN_VALUE;
        boolean tie = false;
        for (HostCandidate cand : cands) {
            int score = ownershipScore(sat, cand);
            if (score > bestScore) {
                bestScore = score;
                best = cand;
                tie = false;
            } else if (score == bestScore) {
                tie = true;
            }
        }
        if (tie || best == null || bestScore <= 0) {
            return null;
        }
        return best;
    }

    private static int ownershipScore(Component sat, HostCandidate cand) {
        int score = 0;
        Component host = cand.component;
        String satSlot = sat.slotId() == null ? "" : sat.slotId();
        String hostSlot = host.slotId() == null ? "" : host.slotId();
        if (!satSlot.isBlank() && satSlot.equals(hostSlot)) {
            score += 100;
        }

        var satBounds = ComponentFootprintUtil.bounds(sat);
        var hostBounds = ComponentFootprintUtil.bounds(host);
        if (satBounds != null && hostBounds != null) {
            if (xzOverlap(satBounds, hostBounds)) {
                score += 50;
            }
            int dx = Math.abs(centerX(satBounds) - centerX(hostBounds));
            int dz = Math.abs(centerZ(satBounds) - centerZ(hostBounds));
            score += Math.max(0, 40 - (dx + dz));
        }

        String satId = componentId(sat);
        if (satId != null) {
            String lowerSat = satId.toLowerCase(Locale.ROOT);
            String lowerOld = cand.oldId.toLowerCase(Locale.ROOT);
            if (!lowerOld.isBlank() && (lowerSat.startsWith(lowerOld) || lowerSat.contains(lowerOld + "_"))) {
                score += 30;
            }
        }
        return score;
    }

    private static boolean xzOverlap(
            ComponentFootprintUtil.Bounds a, ComponentFootprintUtil.Bounds b) {
        return a.minX() < b.maxX() && b.minX() < a.maxX()
                && a.minZ() < b.maxZ() && b.minZ() < a.maxZ();
    }

    private static int centerX(ComponentFootprintUtil.Bounds b) {
        return (b.minX() + b.maxX()) / 2;
    }

    private static int centerZ(ComponentFootprintUtil.Bounds b) {
        return (b.minZ() + b.maxZ()) / 2;
    }

    private static String componentId(Component c) {
        if (c == null || c.params() == null) return null;
        Object raw = c.params().get("component_id");
        if (raw == null) raw = c.params().get("componentId");
        if (raw == null) return null;
        String id = String.valueOf(raw).trim();
        return id.isEmpty() ? null : id;
    }

    private static boolean isHostableMass(Component c) {
        if (c == null || c.params() == null) return false;
        if ("plate".equalsIgnoreCase(String.valueOf(c.params().get("extrude_mode")))) {
            return false;
        }
        return MassComponentKinds.isHostMass(c);
    }

    private record HostCandidate(int index, String oldId, String finalId, Component component) {}
}
