package com.formacraft.server.patch;

import com.formacraft.common.buildcontext.OutlineShape;
import com.formacraft.common.buildcontext.SelectionBox;
import com.formacraft.common.model.constraint.ProtectedZone;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.filter.PatchFilterContext;
import com.formacraft.common.patch.filter.PatchFilterPipeline;
import com.formacraft.common.patch.filter.PatchFilterResult;
import com.formacraft.common.tool.ToolConstraintSnapshot;
import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 服务端 Patch 过滤：在签发 PreviewTicket 前应用禁区、选区与轮廓约束。
 * <p>
 * 委托 {@link PatchFilterPipeline#createDefault()}，保证预览过滤与建造过滤同一套规则。
 * 与客户端 {@link com.formacraft.client.patch.filter.ToolPatchFilter} 语义对齐：
 * MODIFY_REGION 时仅限制选区，不叠加轮廓；否则轮廓与禁区同时生效。
 */
public final class ServerPatchFilter {
    private ServerPatchFilter() {}

    private static final PatchFilterPipeline PIPELINE = PatchFilterPipeline.createDefault();

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

        SelectionBox selection = null;
        if (restrictToSelection && selectionMin != null && selectionMax != null) {
            selection = new SelectionBox(selectionMin, selectionMax).normalized();
        }

        // MODIFY_REGION：不使用轮廓主约束（与 BuildContextResolver / 旧 BuildConstraints 一致）
        OutlineShape effectiveOutline = restrictToSelection ? null : outline;
        List<ProtectedZone> zones = protectedZones != null ? protectedZones : List.of();

        ToolConstraintSnapshot snapshot = new ToolConstraintSnapshot(
                selection,
                effectiveOutline,
                List.of(),
                zones,
                ToolConstraintSnapshot.SymmetryConstraint.none()
        );
        PatchFilterContext context = new PatchFilterContext(snapshot);

        List<BlockPatch> accepted = PIPELINE.apply(patches, origin, context);
        Set<BlockPatch> acceptedSet = new HashSet<>(accepted);

        for (BlockPatch patch : patches) {
            if (patch == null) continue;
            if (acceptedSet.contains(patch)) {
                result.accepted.add(patch);
            } else {
                result.rejected.add(patch);
            }
        }

        if (restrictToSelection && selection == null) {
            result.warnings.add("MODIFY_REGION 已启用，但当前没有选区：无法限制到选区内");
        }
        return result;
    }
}
