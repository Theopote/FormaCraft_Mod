package com.formacraft.common.mass.facade;

/**
 * FacadeDetailLevel（立面细节级别）
 * <p>
 * 🎯 核心定义：
 * AI 不能随便加窗套、线脚，只能选择 detailLevel 和是否启用某类构件。
 * <p>
 * 系统再决定：
 * - 加在哪
 * - 加多少
 * - 哪些窗能加
 */
public enum FacadeDetailLevel {
    /**
     * 低细节级别
     * <p>
     * 规则：
     * - 只有 STRUCTURAL 构件（柱距）
     * - 没有 ARTICULATION 构件（窗套、线脚）
     * - 没有 DECORATION 构件
     */
    LOW,

    /**
     * 中等细节级别（推荐）
     * <p>
     * 规则：
     * - STRUCTURAL 构件（柱距）
     * - 部分 ARTICULATION 构件（窗套、线脚）
     * - 没有 DECORATION 构件
     */
    MEDIUM,

    /**
     * 高细节级别
     * <p>
     * 规则：
     * - STRUCTURAL 构件（柱距）
     * - ARTICULATION 构件（窗套、线脚）
     * - DECORATION 构件（可选装饰）
     */
    HIGH
}
