package com.formacraft.common.patch.filter;

import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * RuleBasedPatchFilter（基于规则的 Patch 过滤器）
 * 
 * 核心功能：使用多个 PatchRule 来过滤 BlockPatch 列表
 */
public class RuleBasedPatchFilter implements PatchFilter {
    
    private final List<PatchRule> rules = new ArrayList<>();
    
    /**
     * 添加规则
     */
    public RuleBasedPatchFilter addRule(PatchRule rule) {
        if (rule != null) {
            rules.add(rule);
        }
        return this;
    }
    
    @Override
    public List<BlockPatch> filter(
            List<BlockPatch> input,
            BlockPos origin,
            PatchFilterContext context
    ) {
        if (input == null || input.isEmpty()) {
            return new ArrayList<>();
        }
        
        PatchRuleContext ruleContext = new PatchRuleContext(origin);
        List<BlockPatch> accepted = new ArrayList<>();
        
        for (BlockPatch patch : input) {
            if (patch == null) continue;
            
            boolean allowed = true;
            for (PatchRule rule : rules) {
                if (!rule.allow(patch, ruleContext)) {
                    allowed = false;
                    break;
                }
            }
            
            if (allowed) {
                accepted.add(patch);
            }
        }
        
        return accepted;
    }
    
    /**
     * 过滤并返回详细结果（包含被拒绝的 patch 和警告）
     */
    public PatchFilterResult filterWithResult(
            List<BlockPatch> input,
            BlockPos origin
    ) {
        PatchFilterResult result = new PatchFilterResult();
        
        if (input == null || input.isEmpty()) {
            return result;
        }
        
        PatchRuleContext ruleContext = new PatchRuleContext(origin);
        
        for (BlockPatch patch : input) {
            if (patch == null) continue;
            
            boolean allowed = true;
            String rejectReason = null;
            
            for (PatchRule rule : rules) {
                if (!rule.allow(patch, ruleContext)) {
                    allowed = false;
                    rejectReason = rule.reason();
                    break;
                }
            }
            
            if (allowed) {
                result.accepted.add(patch);
            } else {
                result.rejected.add(patch);
                if (rejectReason != null && !result.warnings.contains(rejectReason)) {
                    result.warnings.add(rejectReason);
                }
            }
        }
        
        return result;
    }
}

