package com.formacraft.common.patch.filter;

import com.formacraft.client.tool.OutlineTool;
import com.formacraft.client.tool.ProtectedZoneTool;
import com.formacraft.client.tool.SelectionTool;
import com.formacraft.client.tool.SymmetryTool;

/**
 * PatchFilterContext（工具状态快照）
 * 
 * 非常关键：这是 ToolState → Patch 的唯一入口
 * 
 * 核心职责：
 * - 封装所有工具的状态
 * - 提供便捷的检查方法
 * - 确保 Filter 可以安全访问工具状态
 */
public class PatchFilterContext {

    public final SelectionTool selection;
    public final OutlineTool outline;
    public final SymmetryTool symmetry;
    public final ProtectedZoneTool forbidden;

    public PatchFilterContext(
            SelectionTool selection,
            OutlineTool outline,
            SymmetryTool symmetry,
            ProtectedZoneTool forbidden
    ) {
        this.selection = selection;
        this.outline = outline;
        this.symmetry = symmetry;
        this.forbidden = forbidden;
    }

    /**
     * 是否有选区
     */
    public boolean hasSelection() {
        return selection != null && selection.hasSelection();
    }

    /**
     * 是否有轮廓
     */
    public boolean hasOutline() {
        return outline != null && outline.hasShape();
    }

    /**
     * 是否有禁区
     */
    public boolean hasForbiddenZone() {
        return forbidden != null && forbidden.hasZones();
    }

    /**
     * 是否启用对称
     */
    public boolean hasSymmetry() {
        return symmetry != null && symmetry.getMode() != null 
                && symmetry.getMode() != com.formacraft.client.tool.SymmetryMode.NONE;
    }
}

