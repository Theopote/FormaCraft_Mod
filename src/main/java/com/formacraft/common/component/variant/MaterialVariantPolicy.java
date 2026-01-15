package com.formacraft.common.component.variant;

/**
 * MaterialVariantPolicy（材质替换策略）：定义材质如何替换。
 * <p>
 * 核心思想：
 * - 不应该让 AI 直接说"把石头换成深色石头"
 * - 而是使用 MaterialSemantic（WALL_PRIMARY, ACCENT, FRAME）
 * - 然后由 MaterialVariantPolicy 自动映射为同族材质
 */
public enum MaterialVariantPolicy {
    /** 不允许材质替换 */
    NONE,

    /** 同族材质替换（stone_bricks → cracked / mossy） */
    SAME_FAMILY,

    /** 风格内替换（根据 StyleProfile 映射） */
    STYLE_BASED
}
