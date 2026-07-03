package com.formacraft.common.patch.filter;

import com.formacraft.common.tool.ToolConstraintSnapshot;

/**
 * Patch 过滤上下文：封装工具约束快照，供 Filter 安全读取。
 */
public class PatchFilterContext {

    public final ToolConstraintSnapshot snapshot;

    public PatchFilterContext(ToolConstraintSnapshot snapshot) {
        this.snapshot = snapshot != null ? snapshot : ToolConstraintSnapshot.empty();
    }

    public boolean hasSelection() {
        return snapshot.hasSelection();
    }

    public boolean hasOutline() {
        return snapshot.hasOutline();
    }

    public boolean hasForbiddenZone() {
        return snapshot.hasForbiddenZone();
    }

    public boolean hasSymmetry() {
        return snapshot.hasSymmetry();
    }
}
