package com.formacraft.server.patch;

import com.formacraft.common.buildcontext.OutlineShape;
import com.formacraft.common.model.constraint.ProtectedZone;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.filter.PatchFilterResult;
import com.formacraft.server.build.BuildConstraints;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * 服务端 Patch 过滤：在签发 PreviewTicket 前应用禁区、选区与轮廓约束。
 * <p>
 * 与客户端 {@link com.formacraft.client.patch.filter.ToolPatchFilter} 对齐：
 * MODIFY_REGION 时仅限制选区，不叠加轮廓；否则轮廓与禁区同时生效。
 */
public final class ServerPatchFilter {
    private ServerPatchFilter() {}

    public static PatchFilterResult filter(
            BlockPos origin,
            List<BlockPatch> patches,
            List<ProtectedZone> protectedZones,
            OutlineShape outline,
            boolean restrictToSelection,
            BlockPos selectionMin,
            BlockPos selectionMax
    ) {
        PatchFilterResult result = new PatchFilterResult();
        if (origin == null || patches == null || patches.isEmpty()) {
            return result;
        }

        BlockPos selMin = null;
        BlockPos selMax = null;
        if (restrictToSelection && selectionMin != null && selectionMax != null) {
            selMin = new BlockPos(
                    Math.min(selectionMin.getX(), selectionMax.getX()),
                    Math.min(selectionMin.getY(), selectionMax.getY()),
                    Math.min(selectionMin.getZ(), selectionMax.getZ())
            );
            selMax = new BlockPos(
                    Math.max(selectionMin.getX(), selectionMax.getX()),
                    Math.max(selectionMin.getY(), selectionMax.getY()),
                    Math.max(selectionMin.getZ(), selectionMax.getZ())
            );
        }

        // 与 BuildContextResolver 一致：MODIFY_REGION 时不使用轮廓主约束
        OutlineShape effectiveOutline = restrictToSelection ? null : outline;
        List<ProtectedZone> zones = protectedZones != null ? protectedZones : List.of();
        BuildConstraints constraints = new BuildConstraints(selMin, selMax, effectiveOutline, zones);

        for (BlockPatch patch : patches) {
            if (patch == null) continue;
            BlockPos abs = origin.add(patch.dx(), patch.dy(), patch.dz());
            if (constraints.allow(abs)) {
                result.accepted.add(patch);
            } else {
                result.rejected.add(patch);
            }
        }

        if (restrictToSelection && selMin == null) {
            result.warnings.add("MODIFY_REGION 已启用，但当前没有选区：无法限制到选区内");
        }
        return result;
    }
}
