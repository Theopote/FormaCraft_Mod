package com.formacraft.common.patch;

import com.formacraft.common.world.BlockMutationAccess;
import com.formacraft.common.world.BlockSnapshot;
import com.formacraft.common.world.SnapshotWriteResult;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Transactional wrapper over {@link PatchExecutor}: preflight → apply → recover.
 * Preserves compiler patch order (no global REMOVE→PLACE reorder).
 */
public final class WorldPatchTransaction {
    private WorldPatchTransaction() {}

    public record Result(
            BuildExecutionStatus status,
            PatchExecutor.ApplyResult apply,
            int recoveredFull,
            int recoveredStateOnly,
            int recoveryFailed
    ) {
        public static Result preflightRejected(PatchExecutor.ApplyResult apply) {
            return new Result(BuildExecutionStatus.PREFLIGHT_REJECTED,
                    apply == null ? emptyApply() : apply, 0, 0, 0);
        }

        public String summaryZh() {
            StringBuilder sb = new StringBuilder(status.labelZh());
            if (apply != null && status == BuildExecutionStatus.SUCCESS) {
                sb.append("：").append(apply.summaryZh());
            } else if (apply != null && status == BuildExecutionStatus.PREFLIGHT_REJECTED) {
                if (apply.skippedWorldHeight() > 0) {
                    sb.append("：越界 ").append(apply.skippedWorldHeight());
                }
                if (apply.skippedUnloaded() > 0) {
                    sb.append(sb.charAt(sb.length() - 1) == '：' ? "" : "，")
                            .append("未加载区块 ").append(apply.skippedUnloaded());
                }
                if (apply.skippedIllegal() > 0) {
                    sb.append(sb.charAt(sb.length() - 1) == '：' ? "" : "，")
                            .append("非法目标 ").append(apply.skippedIllegal());
                }
                if (apply.attempted() > 0 && apply.skippedTotal() == 0 && apply.failedWrites() == 0) {
                    sb.append("：无法取得完整原始快照");
                }
            } else if (status == BuildExecutionStatus.ROLLED_BACK
                    || status == BuildExecutionStatus.ROLLBACK_INCOMPLETE) {
                sb.append("：已恢复 FULL=").append(recoveredFull)
                        .append("，STATE_ONLY=").append(recoveredStateOnly)
                        .append("，失败=").append(recoveryFailed);
                if (apply != null && apply.failedWrites() > 0) {
                    sb.append("；原写入失败 ").append(apply.failedWrites());
                }
            } else if (apply != null) {
                sb.append("：").append(apply.summaryZh());
            }
            return sb.toString();
        }

        public int applied() {
            return apply == null ? 0 : apply.applied();
        }

        public int skippedWorldHeight() {
            return apply == null ? 0 : apply.skippedWorldHeight();
        }

        public int skippedUnloaded() {
            return apply == null ? 0 : apply.skippedUnloaded();
        }

        public int skippedIllegal() {
            return apply == null ? 0 : apply.skippedIllegal();
        }
    }

    public static Result execute(BlockMutationAccess access, BlockPos origin, List<BlockPatch> patches) {
        if (access == null || origin == null || patches == null || patches.isEmpty()) {
            return Result.preflightRejected(emptyApply());
        }

        Set<BlockPos> affected = collectAffected(origin, patches);
        PreflightStats pre = preflight(access, origin, patches, affected);
        if (!pre.ok()) {
            return Result.preflightRejected(new PatchExecutor.ApplyResult(
                    patches.size(), 0, 0,
                    pre.height, pre.unloaded, pre.illegal, 0, 0));
        }

        Map<BlockPos, BlockSnapshot> before = new HashMap<>(affected.size());
        for (BlockPos pos : affected) {
            before.put(pos, access.getSnapshot(pos));
        }
        if (before.size() != affected.size()) {
            return Result.preflightRejected(new PatchExecutor.ApplyResult(
                    patches.size(), 0, 0, 0, 0, 0, 0, 0));
        }

        PatchExecutor.ApplyResult apply = PatchExecutor.applyToAccess(access, origin, patches);
        boolean applyOk = apply.failedWrites() == 0
                && apply.skippedWorldHeight() == 0
                && apply.skippedUnloaded() == 0
                && apply.skippedIllegal() == 0;
        if (applyOk) {
            return new Result(BuildExecutionStatus.SUCCESS, apply, 0, 0, 0);
        }

        int full = 0;
        int stateOnly = 0;
        int failed = 0;
        for (var e : before.entrySet()) {
            BlockPos pos = e.getKey();
            BlockSnapshot expected = e.getValue();
            // Unchanged cells (e.g. skipped/rejected writes) need no restore.
            if (access.getSnapshot(pos).equals(expected)) {
                full++;
                continue;
            }
            SnapshotWriteResult wr = access.writeSnapshot(pos, expected);
            switch (wr) {
                case FULL -> full++;
                case STATE_ONLY -> stateOnly++;
                case REJECTED, ERROR -> failed++;
            }
        }
        BuildExecutionStatus status = (stateOnly == 0 && failed == 0)
                ? BuildExecutionStatus.ROLLED_BACK
                : BuildExecutionStatus.ROLLBACK_INCOMPLETE;
        return new Result(status, apply, full, stateOnly, failed);
    }

    private static PreflightStats preflight(
            BlockMutationAccess access,
            BlockPos origin,
            List<BlockPatch> patches,
            Set<BlockPos> affected
    ) {
        int height = 0;
        int unloaded = 0;
        int illegal = 0;
        for (BlockPatch p : patches) {
            if (p == null) {
                illegal++;
                continue;
            }
            BlockPos pos = origin.add(p.dx(), p.dy(), p.dz());
            if (!access.isInsideHeight(pos)) {
                height++;
                continue;
            }
            if (!access.isChunkReady(pos)) {
                unloaded++;
                continue;
            }
            if (BlockPatchTargetResolver.resolve(p) == null) {
                illegal++;
            }
        }
        if (height + unloaded + illegal > 0) {
            return new PreflightStats(false, height, unloaded, illegal);
        }
        // Snapshot completeness: every affected cell must be readable now.
        for (BlockPos pos : affected) {
            if (!access.isInsideHeight(pos) || !access.isChunkReady(pos)) {
                return new PreflightStats(false, height, unloaded + 1, illegal);
            }
        }
        return new PreflightStats(true, 0, 0, 0);
    }

    private static Set<BlockPos> collectAffected(BlockPos origin, List<BlockPatch> patches) {
        Set<BlockPos> set = new HashSet<>(patches.size());
        for (BlockPatch p : patches) {
            if (p == null) continue;
            set.add(origin.add(p.dx(), p.dy(), p.dz()).toImmutable());
        }
        return set;
    }

    private static PatchExecutor.ApplyResult emptyApply() {
        return new PatchExecutor.ApplyResult(0, 0, 0, 0);
    }

    private record PreflightStats(boolean ok, int height, int unloaded, int illegal) {}
}
