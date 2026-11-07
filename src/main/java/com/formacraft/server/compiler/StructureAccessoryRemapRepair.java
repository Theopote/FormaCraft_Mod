package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * LLM plans for shrine / temple halls often emit plinths and approach steps as
 * {@code STRUCTURE}. Those have no component generator and typology returns empty →
 * {@code required_component_empty}. Remap foundation-like accessories to
 * {@code FOUNDATION} so {@link com.formacraft.common.generation.component.impl.FoundationGenerator}
 * can emit solid pads.
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

    private static boolean looksFoundationAccessory(Component c) {
        String id = "";
        if (c.params() != null) {
            Object raw = c.params().get("component_id");
            if (raw == null) raw = c.params().get("componentId");
            if (raw != null) id = String.valueOf(raw);
        }
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.contains("plinth") || lower.contains("foundation") || lower.contains("台基")
                || lower.contains("steps") || lower.contains("台阶")
                || (lower.contains("base") && (lower.contains("stone") || lower.contains("platform")))) {
            return true;
        }
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f == null) continue;
                String fl = f.toLowerCase(Locale.ROOT);
                if (fl.contains("plinth") || f.contains("台基") || fl.contains("foundation")
                        || fl.contains("steps") || f.contains("台阶")) {
                    return true;
                }
            }
        }
        return false;
    }
}
