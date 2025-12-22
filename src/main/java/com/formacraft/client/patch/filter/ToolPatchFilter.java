package com.formacraft.client.patch.filter;

import com.formacraft.client.patch.filter.rules.OutlineRule;
import com.formacraft.client.patch.filter.rules.ProtectedZoneRule;
import com.formacraft.client.patch.filter.rules.SelectionOnlyRule;
import com.formacraft.client.tool.OutlineTool;
import com.formacraft.client.tool.ProtectedZoneTool;
import com.formacraft.client.tool.SelectionTool;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.filter.PatchFilter;
import com.formacraft.common.patch.filter.PatchFilterResult;
import com.formacraft.common.patch.filter.PatchRuleContext;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * 客户端：把“工具约束”组装为 PatchFilter，并对 AI patches 进行过滤。
 */
public final class ToolPatchFilter {
    private ToolPatchFilter() {}

    public static PatchFilterResult filter(BlockPos origin, List<BlockPatch> patches, boolean restrictToSelection) {
        PatchFilter filter = new PatchFilter();
        PatchRuleContext ctx = new PatchRuleContext(origin);
        ctx.restrictToSelection = restrictToSelection;

        // 禁区：永远生效（只要存在）
        if (ProtectedZoneTool.INSTANCE.hasZones()) {
            filter.addRule(new ProtectedZoneRule());
        }

        // 轮廓：存在 outline 时启用
        if (OutlineTool.INSTANCE.hasShape()) {
            filter.addRule(new OutlineRule());
        }

        // 选区：只有在“强制限制选区”时启用（典型 MODIFY_REGION）
        if (restrictToSelection && SelectionTool.INSTANCE.hasSelection()) {
            filter.addRule(new SelectionOnlyRule());
        }

        PatchFilterResult r = filter.filter(patches, ctx);
        if (restrictToSelection && !SelectionTool.INSTANCE.hasSelection()) {
            r.warnings.add("MODIFY_REGION 已启用，但当前没有选区：无法限制到选区内");
        }
        return r;
    }
}


