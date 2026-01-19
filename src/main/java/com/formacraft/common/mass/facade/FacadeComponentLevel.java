package com.formacraft.common.mass.facade;

/**
 * FacadeComponentLevel（立面构件分级）
 * <p>
 * 🎯 核心定义：
 * 立面构件分级系统 = 决定"哪些构件是结构性的、哪些是装饰性的、哪些是可省略的"的规则层。
 * <p>
 * 注意三点：
 * ❌ 不是"多放点装饰"
 * ❌ 不是"贴模型"
 * ✅ 是控制复杂度与秩序的系统
 * <p>
 * 三层等级（v1 足够）：
 * - L0：结构级（Structural）- 柱距 / 立面骨架
 * - L1：强调级（Articulation）- 窗套 / 腰线 / 分层线
 * - L2：装饰级（Decoration）- 可选装饰
 */
public enum FacadeComponentLevel {
    /**
     * L0：结构级（Structural）
     * <p>
     * 结构级构件 = 决定立面"骨架节奏"的构件
     * <p>
     * 特点：
     * - 数量少
     * - 间距大
     * - 强烈影响整体比例
     * <p>
     * 核心代表：柱距（Bay / Column Spacing）
     */
    STRUCTURAL,

    /**
     * L1：强调级（Articulation）
     * <p>
     * 强调级构件 = 帮助人"读懂立面层次"的构件
     * <p>
     * 特点：
     * - 不承重（语义上）
     * - 但非常重要
     * - 可以少，但不能乱
     * <p>
     * 核心代表：窗套、腰线、分层线
     */
    ARTICULATION,

    /**
     * L2：装饰级（Decoration）
     * <p>
     * 装饰级构件 = 即使没有，建筑也"成立"的构件
     * <p>
     * 特点：
     * - 可选、可删、可变
     * - 例如：窗楣小装饰、局部浮雕、次要线脚
     * <p>
     * 规则：如果 detailLevel < HIGH，则跳过 DECORATION 构件
     */
    DECORATION
}
