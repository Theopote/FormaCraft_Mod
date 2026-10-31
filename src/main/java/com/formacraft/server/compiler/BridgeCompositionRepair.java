package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
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
 * Compositional LLM bridges often arrive as solid MASS walls for "piers" and opaque railings.
 * Soften pier shells to open frames / thin posts and prefer wood deck + fence rails.
 */
final class BridgeCompositionRepair {
    private BridgeCompositionRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) return plan;
        if (!looksBridge(plan)) return plan;
        Set<String> hostedEndpoints = hostedPlateEndpoints(plan);
        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        for (Component c : plan.components()) {
            Component repaired = repair(c, hostedEndpoints);
            if (repaired != c) changed = true;
            out.add(repaired);
        }
        return changed ? PlanComponentRewrite.withComponents(plan, out) : plan;
    }

    private static Set<String> hostedPlateEndpoints(LlmPlan plan) {
        Set<String> ids = new HashSet<>();
        for (Component c : plan.components()) {
            if (c == null || c.params() == null) continue;
            Object role = c.params().get("plate_role");
            if (role == null) role = c.params().get("plateRole");
            boolean plate = "plate".equalsIgnoreCase(String.valueOf(c.params().get("extrude_mode")))
                    || (role != null && String.valueOf(role).toLowerCase(Locale.ROOT).contains("bridge"));
            if (!plate) continue;
            Object host = c.params().get("host_id");
            if (host == null) host = c.params().get("hostId");
            Object target = c.params().get("target_host_id");
            if (target == null) target = c.params().get("targetHostId");
            if (host != null) ids.add(String.valueOf(host).toLowerCase(Locale.ROOT));
            if (target != null) ids.add(String.valueOf(target).toLowerCase(Locale.ROOT));
        }
        return ids;
    }

    private static Component repair(Component c, Set<String> hostedEndpoints) {
        if (c == null || c.params() == null) return c;
        String id = c.params().get("component_id") == null ? ""
                : String.valueOf(c.params().get("component_id")).toLowerCase(Locale.ROOT);
        String type = c.componentType() == null ? "" : c.componentType().toUpperCase(Locale.ROOT);
        Map<String, Object> params = new HashMap<>(c.params());
        boolean changed = false;

        boolean pier = id.contains("pier") || id.contains("abutment") || id.contains("桥墩")
                || (id.contains("bridge") && type.startsWith("MASS") && !id.contains("deck"));
        boolean deck = id.contains("deck") || id.contains("bridge_deck") || id.contains("桥面")
                || id.contains("span");
        boolean rail = id.contains("rail") || id.contains("parapet") || id.contains("护栏");

        // Hosted-plate endpoints must stay solid so portal carve / walkability works.
        if (pier && type.startsWith("MASS") && !hostedEndpoints.contains(id)) {
            params.put("open", true);
            params.put("wall_type", "none");
            params.put("wall_style", "none");
            params.put("suppress_windows", true);
            if (!params.containsKey("foundation_type")) {
                params.put("foundation_type", "piles");
            }
            // Prefer stone pier posts, not filled boxes.
            if (!params.containsKey("wall_block") && !params.containsKey("material")) {
                params.put("wall_block", "minecraft:stone_bricks");
            }
            changed = true;
        }
        if (deck) {
            Object floor = params.get("floor_block");
            if (floor == null) floor = params.get("material");
            String floorId = floor == null ? "" : String.valueOf(floor).toLowerCase(Locale.ROOT);
            if (floorId.isBlank() || floorId.contains("stone") || floorId.contains("terracotta")
                    || floorId.contains("concrete") || floorId.contains("wool")) {
                params.put("floor_block", "minecraft:oak_planks");
                params.put("material", "minecraft:oak_planks");
                changed = true;
            }
        }
        if (rail) {
            params.put("wall_type", "none");
            params.put("material", "minecraft:oak_fence");
            params.put("block", "minecraft:oak_fence");
            changed = true;
        }
        if (!changed) return c;
        FormacraftMod.LOGGER.info(
                "BridgeCompositionRepair: soften {} type={} pier={} deck={} rail={}",
                id, type, pier, deck, rail);
        return new Component(c.componentType(), c.slotId(), c.relativePosition(),
                c.dimensions(), c.features(), params);
    }

    private static boolean looksBridge(LlmPlan plan) {
        if (plan.distinguishingFeatures() != null) {
            for (String f : plan.distinguishingFeatures()) {
                if (f != null && looksBridgeToken(f)) return true;
            }
        }
        if (plan.playerFidelityNoticeZh() != null && looksBridgeToken(plan.playerFidelityNoticeZh())) {
            return true;
        }
        for (Component c : plan.components()) {
            if (c == null) continue;
            if (c.params() != null && c.params().get("component_id") != null
                    && looksBridgeToken(String.valueOf(c.params().get("component_id")))) {
                return true;
            }
            if (c.features() != null) {
                for (String f : c.features()) {
                    if (f != null && looksBridgeToken(f)) return true;
                }
            }
            if ("STRUCTURE".equalsIgnoreCase(c.componentType()) && c.params() != null) {
                Object typ = c.params().get("typology");
                if (typ != null && String.valueOf(typ).toLowerCase(Locale.ROOT).contains("bridge")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean looksBridgeToken(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.contains("bridge") || lower.contains("桥") || lower.contains("pier")
                || lower.contains("span") || lower.contains("arch_bridge");
    }
}
