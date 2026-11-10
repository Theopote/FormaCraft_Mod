package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.impl.WindowAspect;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Soft-resolve {@code E_WINDOW_ASPECT_CONFLICT}: when window_style=arrow_slit conflicts with
 * an authored window_aspect, drop the aspect so generation can proceed.
 */
final class WindowAspectConflictRepair {
    private WindowAspectConflictRepair() {}

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

    private static Component repair(Component c) {
        if (c == null || c.params() == null || c.params().isEmpty()) {
            return c;
        }
        Map<String, Object> params = c.params();
        Object aspectObj = params.get("window_aspect");
        if (aspectObj == null) aspectObj = params.get("windowAspect");
        Object styleObj = params.get("window_style");
        if (styleObj == null) styleObj = params.get("windowStyle");
        if (aspectObj == null || styleObj == null) {
            return c;
        }
        WindowAspect aspect = WindowAspect.parse(String.valueOf(aspectObj));
        if (!WindowAspect.conflictsWithWindowStyle(aspect, String.valueOf(styleObj))) {
            return c;
        }
        Map<String, Object> next = new HashMap<>(params);
        next.remove("window_aspect");
        next.remove("windowAspect");
        FormacraftMod.LOGGER.info(
                "WindowAspectConflictRepair: drop window_aspect={} conflicting with window_style={}",
                aspectObj, styleObj);
        return new Component(c.componentType(), c.slotId(), c.relativePosition(),
                c.dimensions(), c.features(), next);
    }
}
