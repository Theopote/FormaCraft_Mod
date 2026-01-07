package com.formacraft.common.patch.filter;

import com.formacraft.client.tool.OutlineTool;
import com.formacraft.client.tool.ProtectedZoneTool;
import com.formacraft.client.tool.SelectionTool;
import com.formacraft.client.tool.SymmetryTool;

/**
 * PatchFilterContextBuilder（工具状态快照构建器）
 * 
 * 便捷方法：从工具实例创建 PatchFilterContext
 */
public final class PatchFilterContextBuilder {

    private PatchFilterContextBuilder() {}

    /**
     * 从工具实例创建 PatchFilterContext
     */
    public static PatchFilterContext fromTools(
            SelectionTool selection,
            OutlineTool outline,
            SymmetryTool symmetry,
            ProtectedZoneTool forbidden
    ) {
        return new PatchFilterContext(selection, outline, symmetry, forbidden);
    }

    /**
     * 从默认工具实例创建 PatchFilterContext（推荐）
     */
    public static PatchFilterContext fromDefaultTools() {
        return new PatchFilterContext(
                SelectionTool.INSTANCE,
                OutlineTool.INSTANCE,
                SymmetryTool.INSTANCE,
                ProtectedZoneTool.INSTANCE
        );
    }
}

