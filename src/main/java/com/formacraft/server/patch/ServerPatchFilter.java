package com.formacraft.server.patch;

import com.formacraft.common.model.constraint.ProtectedZone;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.filter.PatchFilterResult;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 Patch 过滤：在签发 PreviewTicket 前应用禁区与选区约束。
 */
public final class ServerPatchFilter {
    private ServerPatchFilter() {}

    public static PatchFilterResult filter(
            BlockPos origin,
            List<BlockPatch> patches,
            List<ProtectedZone> protectedZones,
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

        List<ProtectedZone> zones = protectedZones != null ? protectedZones : List.of();

        for (BlockPatch patch : patches) {
            if (patch == null) continue;
            BlockPos abs = origin.add(patch.dx(), patch.dy(), patch.dz());

            boolean allowed = true;
            for (ProtectedZone z : zones) {
                if (z != null && z.contains(abs)) {
                    allowed = false;
                    result.rejected.add(patch);
                    break;
                }
            }
            if (!allowed) continue;

            if (selMin != null && selMax != null) {
                if (abs.getX() < selMin.getX() || abs.getX() > selMax.getX()
                        || abs.getY() < selMin.getY() || abs.getY() > selMax.getY()
                        || abs.getZ() < selMin.getZ() || abs.getZ() > selMax.getZ()) {
                    result.rejected.add(patch);
                    continue;
                }
            }

            result.accepted.add(patch);
        }

        if (restrictToSelection && selMin == null) {
            result.warnings.add("MODIFY_REGION 已启用，但当前没有选区：无法限制到选区内");
        }
        return result;
    }
}
