package com.formacraft.common.palette.component;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import java.util.Random;

/** Per-component streams isolate material choices from shared palette state and component order. */
public final class PaletteSelectionScope implements AutoCloseable {
    private static final ThreadLocal<Random> CURRENT = new ThreadLocal<>();
    private final Random previous;
    private PaletteSelectionScope(long seed) {
        previous = CURRENT.get();
        CURRENT.set(new Random(seed));
    }
    static Random current() { return CURRENT.get(); }
    public static PaletteSelectionScope open(LlmPlan plan, Component component) {
        String identity = component.params() == null ? null : String.valueOf(component.params().get("component_id"));
        if (identity == null || "null".equals(identity)) identity = component.slotId() + ":" + component.relativePosition();
        return open(plan, component.componentType() + ":" + identity);
    }
    public static PaletteSelectionScope open(LlmPlan plan, String stage) {
        Object seed = plan != null && plan.proportionHints() != null ? plan.proportionHints().get("design_seed") : null;
        if (seed == null && plan != null && plan.proportionHints() != null) seed = plan.proportionHints().get("seed");
        String key = String.valueOf(seed == null ? 0 : seed) + ":" + (plan == null ? "" : plan.styleProfile()) + ":" + stage;
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < key.length(); i++) hash = (hash ^ key.charAt(i)) * 0x100000001b3L;
        return new PaletteSelectionScope(hash);
    }
    @Override public void close() {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}
