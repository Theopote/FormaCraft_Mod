package com.formacraft.common.llm.parser;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.Layout;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 有时会把 slot.anchor 写成世界坐标；编译器期望相对 plan.anchor 的偏移。
 */
public final class LlmPlanAnchorNormalizer {

    private LlmPlanAnchorNormalizer() {}

    public static LlmPlan normalize(LlmPlan plan) {
        if (plan == null || plan.anchor() == null || plan.layout() == null) {
            return plan;
        }
        List<Slot> slots = plan.layout().slots();
        if (slots == null || slots.isEmpty()) {
            return plan;
        }

        Vec3i planAnchor = plan.anchor();
        if (!shouldRelativizeSlots(slots, planAnchor)) {
            return plan;
        }

        List<Slot> normalizedSlots = new ArrayList<>(slots.size());
        for (Slot slot : slots) {
            if (slot == null || slot.anchor() == null) {
                normalizedSlots.add(slot);
                continue;
            }
            Vec3i anchor = slot.anchor();
            Vec3i relative = new Vec3i(
                    anchor.x() - planAnchor.x(),
                    anchor.y() - planAnchor.y(),
                    anchor.z() - planAnchor.z()
            );
            normalizedSlots.add(new Slot(
                    slot.slotId(),
                    relative,
                    slot.facing(),
                    slot.program(),
                    slot.componentPresetId(),
                    slot.componentPreset()
            ));
        }

        FormacraftMod.LOGGER.info(
                "LlmPlanAnchorNormalizer: converted {} slot anchor(s) to plan-relative offsets (plan anchor {})",
                normalizedSlots.size(),
                planAnchor
        );

        Layout layout = new Layout(
                plan.layout().skeletonType(),
                plan.layout().pathBased(),
                normalizedSlots
        );

        return new LlmPlan(
                plan.mode(),
                plan.styleProfile(),
                plan.anchor(),
                plan.globalConstraints(),
                layout,
                plan.components(),
                plan.genome(),
                plan.styleAttributes(),
                plan.proportionHints(),
                plan.targetSlotId(),
                plan.allowedArea(),
                plan.patch(),
                plan.planProgram(),
                plan.planSkeleton(),
                plan.planStatus(),
                plan.error(),
                plan.capabilityGap()
        );
    }

    private static boolean shouldRelativizeSlots(List<Slot> slots, Vec3i planAnchor) {
        for (Slot slot : slots) {
            if (slot == null || slot.anchor() == null) {
                continue;
            }
            if (looksAbsoluteSlotAnchor(slot.anchor(), planAnchor)) {
                return true;
            }
        }
        return false;
    }

    static boolean looksAbsoluteSlotAnchor(Vec3i slotAnchor, Vec3i planAnchor) {
        if (slotAnchor == null || planAnchor == null) {
            return false;
        }
        if (planAnchor.y() != 0 && slotAnchor.y() == planAnchor.y()) {
            return true;
        }
        return slotAnchor.x() == planAnchor.x()
                && slotAnchor.y() == planAnchor.y()
                && slotAnchor.z() == planAnchor.z();
    }
}
