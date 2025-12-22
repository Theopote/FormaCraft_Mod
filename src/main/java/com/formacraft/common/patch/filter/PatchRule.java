package com.formacraft.common.patch.filter;

import com.formacraft.common.patch.BlockPatch;

/**
 * 单条 Patch 规则。
 */
public interface PatchRule {
    /**
     * @return true 表示允许该 patch；false 表示拒绝
     */
    boolean allow(BlockPatch patch, PatchRuleContext ctx);

    /**
     * @return 拒绝原因（用于 UI/Debug）
     */
    String reason();
}


