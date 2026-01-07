package com.formacraft.client.patch.filter;

import com.formacraft.common.buildcontext.BuildContext;
import com.formacraft.client.patch.filter.rules.OutlineRule;
import com.formacraft.client.patch.filter.rules.ProtectedZoneRule;
import com.formacraft.client.patch.filter.rules.SelectionOnlyRule;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.filter.PatchFilterResult;
import com.formacraft.common.patch.filter.RuleBasedPatchFilter;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * 客户端：把"工具约束"组装为 PatchFilter，并对 AI patches 进行过滤。
 */
public final class ToolPatchFilter {
    private ToolPatchFilter() {}

    /**
     * 只读 BuildContext 的 PatchFilter：规则从 BuildContext 派生，不直接读任何 Tool 状态。
     */
    public static PatchFilterResult filter(BuildContext bc, BlockPos origin, List<BlockPatch> patches) {
        RuleBasedPatchFilter filter = new RuleBasedPatchFilter();

        // 禁区：永远叠加
        filter.addRule(new ProtectedZoneRule(bc));

        // Outline/Selection：主约束来自 BuildContext
        if (bc != null && bc.outline != null) {
            filter.addRule(new OutlineRule(bc));
        }
        if (bc != null && (bc.restrictToSelection || bc.mode == BuildContext.Mode.SELECTION) && bc.selection != null) {
            filter.addRule(new SelectionOnlyRule(bc));
        }

        PatchFilterResult r = filter.filterWithResult(patches, origin);
        if (bc != null && bc.restrictToSelection && bc.selection == null) {
            r.warnings.add("MODIFY_REGION 已启用，但当前没有选区：无法限制到选区内");
        }
        return r;
    }
}


