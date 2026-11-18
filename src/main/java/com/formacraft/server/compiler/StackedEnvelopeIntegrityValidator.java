package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.typology.UserConstraintContract;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * After FloorLayoutOverflowRepair and other grows, re-check that a sealed stacked shaft
 * still matches the player whole-building height envelope.
 * Classification: HARD_CONSTRAINT geometry integrity.
 */
final class StackedEnvelopeIntegrityValidator {
    private StackedEnvelopeIntegrityValidator() {}

    static Optional<CapabilityGap> check(LlmPlan plan, List<Component> components) {
        if (plan == null || components == null || components.isEmpty()) return Optional.empty();
        Integer target = explicitWholeHeight(plan);
        if (target == null || target <= 0) return Optional.empty();

        Map<String, List<Component>> families = new HashMap<>();
        for (Component c : components) {
            if (c == null || c.relativePosition() == null || c.dimensions() == null) continue;
            String mid = BuildingHostResolver.massId(c);
            if (mid == null) continue;
            String family = RequestIntentInjector.stackedFamilyKey(mid);
            if (family == null) continue;
            // Only audit shafts that carry a server height seal (envelope authority).
            if (!UserConstraintContract.isServerVerified(c.params(), "height")
                    && !UserConstraintContract.isServerVerified(c.params(), "towerHeight")) {
                continue;
            }
            families.computeIfAbsent(family, k -> new ArrayList<>()).add(c);
        }
        for (List<Component> members : families.values()) {
            if (members.size() < 2) continue;
            int minY = Integer.MAX_VALUE;
            int maxTop = Integer.MIN_VALUE;
            for (Component c : members) {
                int y = c.relativePosition().y();
                int h = Math.max(1, c.dimensions().height());
                minY = Math.min(minY, y);
                maxTop = Math.max(maxTop, y + h);
            }
            int envelope = maxTop - minY;
            if (envelope != target) {
                return Optional.of(new CapabilityGap(
                        "E_STACKED_HEIGHT_UNSUPPORTED",
                        "堆叠塔楼最终高度包络 " + envelope + " 与玩家要求 " + target + " 不一致",
                        "StackedEnvelopeIntegrityValidator",
                        List.of("Clarify whole-building height", "Reduce floor_count/floor_height")));
            }
        }
        return Optional.empty();
    }

    private static Integer explicitWholeHeight(LlmPlan plan) {
        if (plan.planMeta() == null) return null;
        Object contract = plan.planMeta().get(UserConstraintContract.PLAN_META_KEY);
        if (contract instanceof Map<?, ?> meta) {
            Object hb = meta.get("height_blocks");
            if (hb instanceof Number n && n.intValue() > 0) return n.intValue();
        }
        Object intent = plan.planMeta().get("request_intent");
        if (intent instanceof Map<?, ?> ri) {
            Object hb = ri.get("explicit_height_blocks");
            if (hb instanceof Number n && n.intValue() > 0) return n.intValue();
        }
        Object text = plan.planMeta().get("request_intent_text");
        if (text != null) {
            int parsed = UserConstraintContract.parseExplicitHeightBlocks(String.valueOf(text));
            return parsed > 0 ? parsed : null;
        }
        return null;
    }
}
