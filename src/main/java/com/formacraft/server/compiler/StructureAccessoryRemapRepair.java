package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Remap plinth / foundation pads authored as STRUCTURE → FOUNDATION so
 * {@link com.formacraft.common.generation.component.impl.FoundationGenerator} can emit solid pads.
 * <p>
 * Approach steps / 台阶 are remapped to {@code STAIR_SYSTEM} (not FOUNDATION) so they keep a
 * walkable stepped profile instead of becoming a solid rectangular plinth.
 * Columns / porch posts are left as STRUCTURE.
 */
final class StructureAccessoryRemapRepair {
    private StructureAccessoryRemapRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) {
            return plan;
        }
        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        for (Component c : plan.components()) {
            Component repaired = repair(c);
            if (repaired != c) {
                changed = true;
            }
            out.add(repaired);
        }
        return changed ? PlanComponentRewrite.withComponents(plan, out) : plan;
    }

    private static Component repair(Component component) {
        if (component == null || component.componentType() == null) {
            return component;
        }
        if (!"STRUCTURE".equalsIgnoreCase(component.componentType().trim())) {
            return component;
        }
        // Keep primary typology STRUCTURE carriers (whole-building japanese_haiden, etc.).
        if (hasTypologyMarker(component)) {
            return component;
        }
        if (looksApproachSteps(component)) {
            return remapToStair(component);
        }
        if (!looksFoundationAccessory(component)) {
            return component;
        }
        FormacraftMod.LOGGER.info(
                "StructureAccessoryRemapRepair: STRUCTURE → FOUNDATION id={}",
                component.params() == null ? component.componentType() : component.params().get("component_id"));
        return new Component(
                "FOUNDATION",
                component.slotId(),
                component.relativePosition(),
                component.dimensions(),
                component.features(),
                component.params());
    }

    private static Component remapToStair(Component component) {
        Map<String, Object> params = new HashMap<>();
        if (component.params() != null) {
            params.putAll(component.params());
        }
        params.putIfAbsent("exterior", true);
        List<String> features = new ArrayList<>();
        if (component.features() != null) {
            features.addAll(component.features());
        }
        if (features.stream().noneMatch(f -> f != null && f.startsWith("stair:"))) {
            features.add("stair:straight_single_run");
            features.add("ascends_from_north_low_end_to_south_high_end");
        }
        FormacraftMod.LOGGER.info(
                "StructureAccessoryRemapRepair: STRUCTURE → STAIR_SYSTEM id={}",
                params.get("component_id"));
        return new Component(
                "STAIR_SYSTEM",
                component.slotId(),
                component.relativePosition(),
                component.dimensions(),
                List.copyOf(features),
                params);
    }

    private static boolean hasTypologyMarker(Component c) {
        if (c.params() != null) {
            for (String key : List.of("typology_id", "typology", "structural_typology")) {
                Object v = c.params().get(key);
                if (v != null && !String.valueOf(v).isBlank()) {
                    return true;
                }
            }
        }
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f != null && f.toLowerCase(Locale.ROOT).startsWith("typology:")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Entrance steps / 台阶 — never treat as a solid foundation pad. */
    private static boolean looksApproachSteps(Component c) {
        String id = identity(c);
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.contains("steps") || lower.contains("step") || id.contains("台阶")
                || lower.contains("stair") || id.contains("踏步")) {
            // "plinth_steps" still steps; pure plinth handled below.
            if (lower.contains("plinth") && !lower.contains("step") && !id.contains("台阶")) {
                return false;
            }
            return true;
        }
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f == null) continue;
                String fl = f.toLowerCase(Locale.ROOT);
                if (fl.contains("steps") || f.contains("台阶") || fl.contains("stair")
                        || fl.startsWith("stair:")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean looksFoundationAccessory(Component c) {
        if (looksApproachSteps(c)) {
            return false;
        }
        String id = identity(c);
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.contains("plinth") || lower.contains("foundation") || id.contains("台基")
                || (lower.contains("base") && (lower.contains("stone") || lower.contains("platform")))) {
            return true;
        }
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f == null) continue;
                String fl = f.toLowerCase(Locale.ROOT);
                if (fl.contains("plinth") || f.contains("台基") || fl.contains("foundation")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String identity(Component c) {
        if (c.params() != null) {
            Object raw = c.params().get("component_id");
            if (raw == null) raw = c.params().get("componentId");
            if (raw != null) return String.valueOf(raw);
        }
        return "";
    }
}
