package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Promote authored stepped/tier cues from plan distinguishing features / genome topology
 * onto MASS bodies that already declare contiguous nested {@code masses[]} without an
 * explicit {@code stepped} / {@code stack_mode} param (Phase 02.1 stack-cue contract).
 * <p>
 * Classification: FORMAT_REPAIR — copies plan-level AI/USER cues into params; never invents
 * a stepped ziggurat from geometry alone.
 */
public final class SteppedMassCueRepair {
    private SteppedMassCueRepair() {}

    public static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || !planAsksStepped(plan)) {
            return plan;
        }
        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        for (Component c : plan.components()) {
            if (c == null || c.params() == null) {
                out.add(c);
                continue;
            }
            if (!isMassBody(c) || !(c.params().get("masses") instanceof List<?> masses) || masses.size() < 2) {
                out.add(c);
                continue;
            }
            // Local opt-out / authored stack design wins over plan-text cue (P1-03).
            if (refusesSteppedCue(c.params()) || hasStackCue(c.params()) || hasAuthoredEccentricOffsets(masses)) {
                out.add(c);
                continue;
            }
            Map<String, Object> params = new HashMap<>(c.params());
            params.put("stepped", true);
            params.put("stepped_cue_source", "plan_distinguishing_features");
            out.add(new Component(
                    c.componentType(), c.slotId(), c.relativePosition(),
                    c.dimensions(), c.features(), params));
            changed = true;
        }
        return changed ? PlanComponentRewrite.withComponents(plan, out) : plan;
    }

    /** Explicit local lock: never promote stepped from plan text. */
    private static boolean refusesSteppedCue(Map<String, Object> params) {
        if (params == null) return false;
        if (Boolean.FALSE.equals(params.get("stepped"))
                || Boolean.FALSE.equals(params.get("stepped_mass"))
                || Boolean.FALSE.equals(params.get("steppedMass"))) {
            return true;
        }
        Object stackMode = params.get("stack_mode");
        if (stackMode == null) stackMode = params.get("stackMode");
        if (stackMode != null) {
            String mode = String.valueOf(stackMode).trim().toLowerCase(Locale.ROOT);
            // Any explicit stack_mode (including eccentric / none) is a local design lock.
            if (!mode.isEmpty()) return true;
        }
        return false;
    }

    /**
     * Non-zero nested offsets mean the model already authored tier placement —
     * do not force centered {@code stepped=true} alignment (P1-03).
     */
    private static boolean hasAuthoredEccentricOffsets(List<?> masses) {
        for (Object item : masses) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            Object offset = raw.get("offset");
            if (!(offset instanceof Map<?, ?> om)) continue;
            int ox = intOrZero(om.get("x"));
            int oy = intOrZero(om.get("y"));
            int oz = intOrZero(om.get("z"));
            // Pure vertical stack (y only) still allows stepped cue; XZ eccentricity locks.
            if (ox != 0 || oz != 0) return true;
            // Ignore y — vertical stacking is what stepped cue targets.
            if (oy != 0 && (ox != 0 || oz != 0)) return true;
        }
        return false;
    }

    private static int intOrZero(Object raw) {
        if (raw instanceof Number n) return n.intValue();
        if (raw == null) return 0;
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static boolean planAsksStepped(LlmPlan plan) {
        if (plan == null) return false;
        StringBuilder sb = new StringBuilder();
        if (plan.distinguishingFeatures() != null) {
            for (String f : plan.distinguishingFeatures()) {
                if (f != null) sb.append(f).append(' ');
            }
        }
        if (plan.playerFidelityNoticeZh() != null) sb.append(plan.playerFidelityNoticeZh()).append(' ');
        if (plan.genome() != null && plan.genome().topology != null) {
            var topology = plan.genome().topology;
            if (topology.layout != null) sb.append(topology.layout).append(' ');
            if (topology.composition != null) sb.append(topology.composition).append(' ');
            if (topology.levels != null) sb.append(topology.levels).append(' ');
            if (plan.genome().form != null && plan.genome().form.progression != null) {
                sb.append(plan.genome().form.progression).append(' ');
            }
        }
        String blob = sb.toString().toLowerCase(Locale.ROOT);
        return blob.contains("stepped") || blob.contains("ziggurat") || blob.contains("terrace tier")
                || blob.contains("diminishing") || blob.contains("tiered") || blob.contains("mass tier")
                || blob.contains("stacked") && (blob.contains("tier") || blob.contains("层"))
                || blob.contains("逐层") || blob.contains("退台") || blob.contains("台地");
    }

    private static boolean isMassBody(Component c) {
        String t = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
        return "MASS_MAIN".equals(t) || "MAIN_MASS".equals(t) || "MASS_SECONDARY".equals(t)
                || "TOWER".equals(t);
    }

    private static boolean hasStackCue(Map<String, Object> params) {
        if (Boolean.TRUE.equals(params.get("stepped"))
                || Boolean.TRUE.equals(params.get("stepped_mass"))
                || Boolean.TRUE.equals(params.get("steppedMass"))
                || Boolean.TRUE.equals(params.get("replace_envelope"))
                || Boolean.TRUE.equals(params.get("replaceEnvelope"))) {
            return true;
        }
        Object stackMode = params.get("stack_mode");
        if (stackMode == null) stackMode = params.get("stackMode");
        if (stackMode != null) {
            String mode = String.valueOf(stackMode).trim().toLowerCase(Locale.ROOT);
            if ("centered".equals(mode) || "centre".equals(mode) || "center".equals(mode)) {
                return true;
            }
        }
        return params.containsKey("tier_footprints") || params.containsKey("tierFootprints")
                || params.containsKey("setback_per_tier") || params.containsKey("setbackPerTier");
    }
}
