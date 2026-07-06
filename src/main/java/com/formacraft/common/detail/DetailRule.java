package com.formacraft.common.detail;

import com.formacraft.common.semantic.SemanticPart;

/**
 * Declarative perimeter/detail rule: {@code when} + {@code action}.
 */
public record DetailRule(
        DetailRuleWhen when,
        DetailRuleAction action,
        String presetId
) {
    public boolean isValid() {
        return when != null && action != null && action.isValid();
    }

    public record DetailRuleWhen(
            DetailRuleRegion region,
            DetailRuleYAnchor yAnchor,
            int yOffset,
            String blockFilter
    ) {}

    public record DetailRuleAction(
            DetailRuleActionType type,
            SemanticPart semanticPart,
            String blockId,
            DetailRuleFacing facing
    ) {
        public boolean isValid() {
            return type != null;
        }
    }
}
