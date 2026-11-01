package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.Layout;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrator sometimes leaves a non-zero plan-relative slot.anchor on a single primary building
 * (e.g. x=-8). Components are already footprint-centered on plan origin (0,0), so emitting with
 * that slot offset slides the whole building away from the player cursor.
 * <p>
 * When there is only one layout slot, force its XZ anchor to the plan origin (preserve Y).
 * Multi-slot compounds keep relative slot spacing by shifting so the primary (first) slot lands at 0,0.
 */
final class SlotPrimaryAnchorRepair {
    private SlotPrimaryAnchorRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.layout() == null || plan.layout().slots() == null) return plan;
        List<Slot> slots = plan.layout().slots().stream().filter(s -> s != null && s.anchor() != null).toList();
        if (slots.isEmpty()) return plan;

        Slot primary = slots.getFirst();
        Vec3i a = primary.anchor();
        if (a.x() == 0 && a.z() == 0) return plan;

        int dx = a.x();
        int dz = a.z();
        List<Slot> fixed = new ArrayList<>(plan.layout().slots().size());
        for (Slot slot : plan.layout().slots()) {
            if (slot == null || slot.anchor() == null) {
                fixed.add(slot);
                continue;
            }
            Vec3i sa = slot.anchor();
            fixed.add(new Slot(
                    slot.slotId(),
                    new Vec3i(sa.x() - dx, sa.y(), sa.z() - dz),
                    slot.facing(),
                    slot.program(),
                    slot.componentPresetId(),
                    slot.componentPreset(),
                    slot.size()));
        }
        FormacraftMod.LOGGER.info(
                "SlotPrimaryAnchorRepair: shifted {} slot anchor(s) by ({},0,{}) so primary sits on plan origin",
                fixed.size(), dx, dz);
        Layout layout = new Layout(plan.layout().skeletonType(), plan.layout().pathBased(), fixed);
        return PlanComponentRewrite.withLayout(plan, layout);
    }
}
