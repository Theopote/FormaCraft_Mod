package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Final-pass scan of the compiled patch list for residual multi-PLACE conflicts
 * (e.g. introduced by post-process). Same-material duplicates are allowed;
 * different materials at one cell without prior index authorization surface as a gap.
 */
public final class PatchConflictValidator {
    private PatchConflictValidator() {}

    public record Finding(BlockPos position, String firstBlock, String secondBlock) {}

    public static Optional<Finding> inspect(List<BlockPatch> patches) {
        if (patches == null || patches.isEmpty()) return Optional.empty();
        Map<Long, String> lastPlace = new HashMap<>();
        for (BlockPatch p : patches) {
            if (p == null) continue;
            long key = pack(p.dx(), p.dy(), p.dz());
            if (BlockPatch.REMOVE.equals(p.action())) {
                lastPlace.remove(key);
                continue;
            }
            if (!BlockPatch.PLACE.equals(p.action())) continue;
            String canon = com.formacraft.common.palette.dynamic.ExplicitMaterialPolicy.canonical(p.targetBlock());
            String prev = lastPlace.get(key);
            if (prev != null && canon != null && !prev.equals(canon)) {
                // Final list usually keeps only the last write; detect when both still present
                // by scanning forward — record first conflict pair found via two-pass:
                return Optional.of(new Finding(
                        new BlockPos(p.dx(), p.dy(), p.dz()), prev, canon));
            }
            if (canon != null) lastPlace.put(key, canon);
        }
        return Optional.empty();
    }

    /**
     * Detects when the same cell appears with two different PLACE materials still both in the list
     * (unordered scan collecting all place materials per cell).
     */
    public static Optional<Finding> inspectMultiPlace(List<BlockPatch> patches) {
        if (patches == null || patches.isEmpty()) return Optional.empty();
        Map<Long, String> first = new HashMap<>();
        Map<Long, Boolean> removed = new HashMap<>();
        for (BlockPatch p : patches) {
            if (p == null) continue;
            long key = pack(p.dx(), p.dy(), p.dz());
            if (BlockPatch.REMOVE.equals(p.action())) {
                removed.put(key, Boolean.TRUE);
                first.remove(key);
                continue;
            }
            if (!BlockPatch.PLACE.equals(p.action())) continue;
            removed.remove(key);
            String canon = com.formacraft.common.palette.dynamic.ExplicitMaterialPolicy.canonical(p.targetBlock());
            if (canon == null) continue;
            String prev = first.get(key);
            if (prev == null) {
                first.put(key, canon);
            } else if (!prev.equals(canon)) {
                return Optional.of(new Finding(new BlockPos(p.dx(), p.dy(), p.dz()), prev, canon));
            }
        }
        return Optional.empty();
    }

    public static Optional<CapabilityGap> toCapabilityGap(Finding f) {
        if (f == null) return Optional.empty();
        return Optional.of(new CapabilityGap(
                "E_PATCH_CONFLICT",
                "未授权 Patch 覆盖：" + f.position().toShortString()
                        + " " + f.firstBlock() + " -> " + f.secondBlock(),
                "components[]",
                List.of("为开口类构件授权覆盖", "或设置 allow_patch_overwrite=true", "禁止无关构件互相覆盖同坐标")
        ));
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF)) | (((long) (y & 0x3FF)) << 21) | (((long) (z & 0x1FFFFF)) << 31);
    }
}
