package com.formacraft.client.tool;

import net.minecraft.text.Text;

/**
 * FormaCraft 工具抽象：
 * - Tool = 空间语义输入（选区/刷子/采样等）
 * - 不依赖 Screen，完全走 HUD + InputRouter
 */
public interface FormacraftTool {
    String getId();

    Text getDisplayName();

    default void onActivate() {}

    default void onDeactivate() {}

    /** Cancel an unfinished gesture without removing committed constraints. */
    default void cancelDraft() {}

    /** Enter completes a draft; Backspace removes its last point. */
    default boolean finishDraft() { return false; }
    default boolean removeLastDraftPoint() { return false; }

    /** Committed constraints can remain visible while another tool is active. */
    default boolean hasPersistentOverlay() { return false; }

    /** Local tool history must never fall through to world mutation history. */
    default boolean hasLocalHistory() { return false; }
    default boolean undo() { return false; }
    default boolean redo() { return false; }

    /**
     * 鼠标点击（通常用于世界交互）。
     * @return true 表示已消费本次点击（应阻止游戏处理）
     */
    default boolean onMouseClick(double mx, double my, int button) { return false; }

    /** 每 tick 更新（用于实时预览等）。 */
    default void tick() {}

    /**
     * 世界渲染阶段回调（用于绘制 3D 选框/刷子预览等）。
     * <p>这里不使用 Fabric 的 WorldRenderEvents（当前工程环境不可用），由 Mixin 注入点驱动。</p>
     */
    default void renderWorld(ToolWorldRenderContext ctx) {}
}

