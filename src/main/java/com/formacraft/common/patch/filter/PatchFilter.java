package com.formacraft.common.patch.filter;

import com.formacraft.common.patch.BlockPatch;

import java.util.ArrayList;
import java.util.List;

/**
 * Patch 过滤器（规则引擎）：按规则裁剪 accepted/rejected，并生成 warnings。
 */
public class PatchFilter {
    private final List<PatchRule> rules = new ArrayList<>();

    public PatchFilter addRule(PatchRule rule) {
        if (rule != null) rules.add(rule);
        return this;
    }

    public PatchFilterResult filter(List<BlockPatch> input, PatchRuleContext ctx) {
        PatchFilterResult result = new PatchFilterResult();
        if (input == null || input.isEmpty()) return result;
        if (ctx == null) ctx = new PatchRuleContext(null);

        for (BlockPatch patch : input) {
            if (patch == null) continue;
            boolean allowed = true;

            for (PatchRule rule : rules) {
                if (rule == null) continue;
                if (!rule.allow(patch, ctx)) {
                    allowed = false;
                    result.rejected.add(patch);
                    String reason = rule.reason();
                    if (reason != null && !reason.isBlank()) result.warnings.add(reason);
                    break;
                }
            }

            if (allowed) {
                result.accepted.add(patch);
            }
        }

        return result;
    }
}


