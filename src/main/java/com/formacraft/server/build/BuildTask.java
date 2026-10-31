package com.formacraft.server.build;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.patch.BuildExecutionStatus;
import com.formacraft.common.patch.WorldMutationOutcome;
import com.formacraft.common.world.BlockSnapshot;
import com.formacraft.common.world.SnapshotWriteResult;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 建造任务：分 Tick 施工，按 Chunk 分桶延迟重试，单方块异常隔离，写后校验。
 * <p>
 * Incomplete terminals (permanent fail / cancel) attempt full-batch rollback of
 * {@link #appliedChanges} so Build aligns with {@link com.formacraft.common.patch.WorldPatchTransaction}
 * history rules: only a clean success records undo/Memory.
 */
public class BuildTask {
    public enum Status {
        RUNNING,
        WAITING_CHUNKS,
        /** 正在分 Tick 回滚已落地方块（未完成，不算终态） */
        ROLLING_BACK,
        /** 全部按预期完成（无永久失败） */
        COMPLETED,
        /** 有落地且回滚不完整（残留世界改动） */
        PARTIAL,
        /** 无有效落地或已完整回滚后的失败 */
        FAILED,
        /** 中途取消；若曾写入则已尝试回滚 */
        CANCELLED,
        /** 不完整建造后已完整回滚到施工前 */
        ROLLED_BACK
    }

    private static final int DEFAULT_MAX_DEFER_ATTEMPTS = 120;
    private static final long DEFAULT_DEFER_TIMEOUT_MS = 30_000L;
    private static final int DEFER_RETRY_INTERVAL_TICKS = 20;

    /** 测试可覆盖：未加载重试上限。 */
    static int maxDeferAttempts = DEFAULT_MAX_DEFER_ATTEMPTS;
    /** 测试可覆盖：未加载超时（毫秒）。 */
    static long deferTimeoutMs = DEFAULT_DEFER_TIMEOUT_MS;
    static int deferRetryIntervalTicks = DEFER_RETRY_INTERVAL_TICKS;

    private final GeneratedStructure structure;
    private final ServerWorld world;
    private final com.formacraft.common.world.BlockMutationAccess access;
    /** Optional sanitized FBD source plan JSON attached at enqueue. */
    private final String fbdSourcePlanJson;
    private final List<BlockChange> appliedChanges = new ArrayList<>();
    /** ChunkPos 长键 → 该区块内待重试方块 */
    private final LinkedHashMap<Long, ArrayDeque<DeferredBlock>> deferredByChunk = new LinkedHashMap<>();
    private final long startedAtMs = System.currentTimeMillis();

    private int index = 0;
    private long tickCounter = 0;
    private Status status = Status.RUNNING;
    private int skippedOutOfBounds = 0;
    private int skippedSameState = 0;
    private int skippedIllegal = 0;
    private int failedWrites = 0;
    private int failedUnloaded = 0;
    private int deferredUnloadedPeak = 0;
    private BuildExecutionStatus mutationStatus = null;
    private int recoveredFull = 0;
    private int recoveredStateOnly = 0;
    private int recoveryFailed = 0;
    /** When set, {@link #tick} drains rollback instead of forward placement. */
    private boolean rollbackActive = false;
    /** Index into {@link #appliedChanges} for reverse-order rollback (inclusive). */
    private int rollbackIndex = -1;
    /** Cells that failed to restore; kept as residual undo after rollback finishes. */
    private final List<BlockChange> rollbackResiduals = new ArrayList<>();
    /** True if rollback was started by {@link #cancel()} (terminal becomes CANCELLED). */
    private boolean rollbackFromCancel = false;

    private record DeferredBlock(PlannedBlock planned, int attempts, long firstDeferredMs, long nextRetryTick) {}

    public BuildTask(GeneratedStructure structure, ServerWorld world) {
        this(structure, world, (String) null);
    }

    public BuildTask(GeneratedStructure structure, ServerWorld world, String fbdSourcePlanJson) {
        this(structure, world, com.formacraft.common.world.BlockMutationAccess.forWorld(world), fbdSourcePlanJson);
    }

    BuildTask(GeneratedStructure structure, ServerWorld world,
              com.formacraft.common.world.BlockMutationAccess access) {
        this(structure, world, access, null);
    }

    BuildTask(GeneratedStructure structure, ServerWorld world,
              com.formacraft.common.world.BlockMutationAccess access, String fbdSourcePlanJson) {
        this.structure = java.util.Objects.requireNonNull(structure, "structure");
        this.world = world;
        this.access = java.util.Objects.requireNonNull(access, "access");
        this.fbdSourcePlanJson = fbdSourcePlanJson;
    }

    public String fbdSourcePlanJson() {
        return fbdSourcePlanJson;
    }

    public boolean isFinished() {
        return status == Status.COMPLETED || status == Status.PARTIAL
                || status == Status.FAILED || status == Status.CANCELLED
                || status == Status.ROLLED_BACK;
    }

    public Status status() {
        return status;
    }

    /** Unified mutation terminal (null while still running). */
    public BuildExecutionStatus mutationStatus() {
        return mutationStatus;
    }

    public WorldMutationOutcome mutationOutcome() {
        BuildExecutionStatus st = mutationStatus != null
                ? mutationStatus
                : (status == Status.COMPLETED ? BuildExecutionStatus.SUCCESS : null);
        if (st == null) {
            return null;
        }
        return WorldMutationOutcome.build(st, appliedChanges.size(), recoveredFull, recoveredStateOnly, recoveryFailed);
    }

    public GeneratedStructure getStructure() {
        return structure;
    }

    public ServerWorld getWorld() {
        return world;
    }

    public List<BlockChange> getAppliedChanges() {
        return appliedChanges;
    }

    /** 取消任务：停止主队列与延迟队列；已写入方块分 Tick 全批回滚（与 Patch 事务语义对齐）。 */
    public void cancel() {
        if (isFinished()) return;
        if (rollbackActive) {
            rollbackFromCancel = true;
            return;
        }
        deferredByChunk.clear();
        index = structure.getBlocks().size();
        if (!appliedChanges.isEmpty()) {
            beginRollback(true);
            com.formacraft.FormacraftMod.LOGGER.info(
                    "BuildTask: cancel → rolling back {} cells. {}",
                    appliedChanges.size(), result().summaryZh());
        } else {
            mutationStatus = BuildExecutionStatus.PREFLIGHT_REJECTED;
            status = Status.CANCELLED;
            com.formacraft.FormacraftMod.LOGGER.info("BuildTask: cancelled. {}", result().summaryZh());
        }
    }

    public BuildApplyResult result() {
        int total = structure != null ? structure.size() : 0;
        return new BuildApplyResult(
                total,
                appliedChanges.size(),
                skippedOutOfBounds,
                skippedSameState,
                failedUnloaded,
                skippedIllegal,
                failedWrites,
                deferredUnloadedPeak,
                deferredCount(),
                status
        );
    }

    /**
     * 执行最多 maxPerTick 个方块修改（含主队列与待重试）；回滚中则只做恢复写入。
     */
    public int tick(int maxPerTick) {
        if (isFinished() || maxPerTick <= 0) return 0;
        tickCounter++;
        if (rollbackActive) {
            return tickRollback(maxPerTick);
        }

        int count = 0;
        count += drainDeferred(maxPerTick);
        if (count >= maxPerTick) {
            updateStatusAfterTick();
            return count;
        }

        var blocks = structure.getBlocks();
        while (index < blocks.size() && count < maxPerTick) {
            PlannedBlock planned = blocks.get(index++);
            count += processOne(planned);
        }

        updateStatusAfterTick();
        return count;
    }

    private int drainDeferred(int maxPerTick) {
        if (deferredByChunk.isEmpty()) return 0;
        int count = 0;
        // 轮询各 chunk 桶，避免每 Tick 全量复制排序
        List<Long> keys = new ArrayList<>(deferredByChunk.keySet());
        for (Long key : keys) {
            if (count >= maxPerTick) break;
            ArrayDeque<DeferredBlock> bucket = deferredByChunk.get(key);
            if (bucket == null || bucket.isEmpty()) {
                deferredByChunk.remove(key);
                continue;
            }
            int budget = Math.max(1, (maxPerTick - count + keys.size() - 1) / Math.max(1, keys.size()));
            ArrayDeque<DeferredBlock> remain = new ArrayDeque<>();
            while (!bucket.isEmpty() && count < maxPerTick && budget > 0) {
                DeferredBlock item = bucket.pollFirst();
                if (item.nextRetryTick() > tickCounter) {
                    remain.addLast(item);
                    continue;
                }
                count += processDeferred(item, remain);
                budget--;
            }
            while (!bucket.isEmpty()) remain.addLast(bucket.pollFirst());
            if (remain.isEmpty()) {
                deferredByChunk.remove(key);
            } else {
                deferredByChunk.put(key, remain);
            }
        }
        return count;
    }

    private int processDeferred(DeferredBlock item, ArrayDeque<DeferredBlock> requeueInto) {
        PlannedBlock planned = item.planned();
        if (planned == null || planned.getPos() == null) {
            skippedIllegal++;
            return 1;
        }
        BlockPos pos = planned.getPos();
        try {
            if (!access.isInsideHeight(pos)) {
                skippedOutOfBounds++;
                return 1;
            }
            if (!access.isChunkReady(pos)) {
                return requeueOrFail(item, requeueInto);
            }
            return applyPlanned(planned);
        } catch (RuntimeException e) {
            failedWrites++;
            com.formacraft.FormacraftMod.LOGGER.warn(
                    "BuildTask deferred apply failed at {}; continuing", pos, e);
            return 1;
        }
    }

    private int processOne(PlannedBlock planned) {
        if (planned == null || planned.getPos() == null || planned.getTargetState() == null) {
            skippedIllegal++;
            return 1;
        }
        BlockPos pos = planned.getPos();
        try {
            if (!access.isInsideHeight(pos)) {
                skippedOutOfBounds++;
                return 1;
            }
            if (!access.isChunkReady(pos)) {
                enqueueDeferred(new DeferredBlock(planned, 1, System.currentTimeMillis(), tickCounter + 1));
                return 1;
            }
            return applyPlanned(planned);
        } catch (RuntimeException e) {
            failedWrites++;
            com.formacraft.FormacraftMod.LOGGER.warn(
                    "BuildTask apply failed at {}; continuing", pos, e);
            return 1;
        }
    }

    private int requeueOrFail(DeferredBlock item, ArrayDeque<DeferredBlock> localRemain) {
        long now = System.currentTimeMillis();
        int nextAttempts = item.attempts() + 1;
        boolean timedOut = (now - item.firstDeferredMs()) > deferTimeoutMs;
        boolean tooMany = nextAttempts > maxDeferAttempts;
        if (timedOut || tooMany) {
            failedUnloaded++;
            return 1;
        }
        DeferredBlock next = new DeferredBlock(
                item.planned(), nextAttempts, item.firstDeferredMs(),
                tickCounter + Math.max(1, deferRetryIntervalTicks));
        if (localRemain != null) {
            localRemain.addLast(next);
            deferredUnloadedPeak = Math.max(deferredUnloadedPeak, deferredCount() + localRemain.size());
        } else {
            enqueueDeferred(next);
        }
        return 1;
    }

    private void enqueueDeferred(DeferredBlock item) {
        long key = chunkKey(item.planned().getPos());
        deferredByChunk.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(item);
        deferredUnloadedPeak = Math.max(deferredUnloadedPeak, deferredCount());
    }

    private int deferredCount() {
        int n = 0;
        for (ArrayDeque<DeferredBlock> q : deferredByChunk.values()) n += q.size();
        return n;
    }

    private int applyPlanned(PlannedBlock planned) {
        BlockPos pos = planned.getPos();
        BlockSnapshot before = access.getSnapshot(pos);
        var toState = planned.getTargetState();
        if (before.state().equals(toState)) {
            skippedSameState++;
            return 1;
        }
        BlockSnapshot target = BlockSnapshot.of(toState);
        SnapshotWriteResult writeResult = access.writeSnapshot(pos, target);
        BlockSnapshot after;
        try {
            after = access.getSnapshot(pos);
        } catch (RuntimeException e) {
            failedWrites++;
            com.formacraft.FormacraftMod.LOGGER.warn(
                    "BuildTask post-write read failed at {}; change not recorded for undo", pos, e);
            return 1;
        }
        if (!before.equals(after)) {
            appliedChanges.add(new BlockChange(pos.toImmutable(), before, after));
        }
        if (!writeResult.changedWorldPossibly() || !matchesForwardTarget(target, after)) {
            failedWrites++;
            if (failedWrites <= 5) com.formacraft.FormacraftMod.LOGGER.warn(
                    "BuildTask verification mismatch at {}: result={}, target={}, actual={}",
                    pos, writeResult, toState, after.state());
        }
        return 1;
    }

    /** Forward plans specify block state, not the automatically created block entity NBT. */
    static boolean matchesForwardTarget(BlockSnapshot target, BlockSnapshot after) {
        if (target.blockEntityNbt() != null) return target.equals(after);
        var desired = target.state();
        var actual = after.state();
        if (desired.getBlock() != actual.getBlock()) return false;
        boolean stairs = desired.getBlock() instanceof net.minecraft.block.StairsBlock;
        boolean connected = desired.getBlock() instanceof net.minecraft.block.FenceBlock
                || desired.getBlock() instanceof net.minecraft.block.PaneBlock
                || desired.getBlock() instanceof net.minecraft.block.WallBlock;
        for (var property : desired.getProperties()) {
            String name = property.getName();
            if (stairs && "shape".equals(name)) continue;
            if (connected && java.util.Set.of("north", "south", "east", "west", "up").contains(name)) continue;
            if (!java.util.Objects.equals(desired.get(property), actual.get(property))) return false;
        }
        return true;
    }

    private void updateStatusAfterTick() {
        if (status == Status.CANCELLED || status == Status.ROLLED_BACK
                || status == Status.ROLLING_BACK || status == Status.PARTIAL
                || status == Status.FAILED || status == Status.COMPLETED) {
            return;
        }
        boolean mainDone = index >= structure.getBlocks().size();
        if (!mainDone) {
            status = Status.RUNNING;
            return;
        }
        if (!deferredByChunk.isEmpty()) {
            status = Status.WAITING_CHUNKS;
            return;
        }
        boolean permanentFail = failedUnloaded + failedWrites + skippedIllegal + skippedOutOfBounds > 0;
        if (permanentFail) {
            if (!appliedChanges.isEmpty()) {
                beginRollback(false);
                return;
            }
            mutationStatus = BuildExecutionStatus.APPLY_FAILED;
            status = Status.FAILED;
        } else {
            mutationStatus = BuildExecutionStatus.SUCCESS;
            status = Status.COMPLETED;
        }
        com.formacraft.FormacraftMod.LOGGER.info("BuildTask: finished ({} mutation={}). {}",
                status, mutationStatus, result().summaryZh());
    }

    private void beginRollback(boolean fromCancel) {
        rollbackFromCancel = fromCancel;
        rollbackActive = true;
        rollbackResiduals.clear();
        recoveredFull = 0;
        recoveredStateOnly = 0;
        recoveryFailed = 0;
        rollbackIndex = appliedChanges.size() - 1;
        status = Status.ROLLING_BACK;
        mutationStatus = null;
    }

    private int tickRollback(int maxPerTick) {
        int n = 0;
        while (n < maxPerTick && rollbackIndex >= 0) {
            BlockChange change = appliedChanges.get(rollbackIndex);
            rollbackIndex--;
            n++;
            restoreOne(change);
        }
        if (rollbackIndex >= 0) {
            return n;
        }
        finishRollback();
        return n;
    }

    private void restoreOne(BlockChange change) {
        if (change == null || change.getPos() == null || change.getFrom() == null) {
            recoveryFailed++;
            return;
        }
        try {
            if (access.getSnapshot(change.getPos()).equals(change.getFrom())) {
                recoveredFull++;
                return;
            }
            SnapshotWriteResult wr = access.writeSnapshot(change.getPos(), change.getFrom());
            switch (wr) {
                case FULL -> recoveredFull++;
                case STATE_ONLY -> {
                    recoveredStateOnly++;
                    rollbackResiduals.add(change);
                }
                case REJECTED, ERROR -> {
                    recoveryFailed++;
                    rollbackResiduals.add(change);
                }
            }
        } catch (RuntimeException e) {
            recoveryFailed++;
            rollbackResiduals.add(change);
            com.formacraft.FormacraftMod.LOGGER.warn(
                    "BuildTask rollback failed at {}", change.getPos(), e);
        }
    }

    private void finishRollback() {
        appliedChanges.clear();
        appliedChanges.addAll(rollbackResiduals);
        rollbackResiduals.clear();
        rollbackActive = false;
        boolean clean = recoveredStateOnly == 0 && recoveryFailed == 0;
        mutationStatus = clean
                ? BuildExecutionStatus.ROLLED_BACK
                : BuildExecutionStatus.ROLLBACK_INCOMPLETE;
        if (rollbackFromCancel) {
            status = Status.CANCELLED;
        } else {
            status = clean ? Status.ROLLED_BACK : Status.PARTIAL;
        }
        com.formacraft.FormacraftMod.LOGGER.info(
                "BuildTask: rollback done ({} mutation={}) remaining={}. {}",
                status, mutationStatus, appliedChanges.size(), result().summaryZh());
    }

    /**
     * Restore all applied changes in one call (tests / sync callers).
     * Production cancel/fail paths use {@link #tickRollback} via {@link #tick}.
     */
    BuildExecutionStatus rollbackAppliedChanges() {
        if (appliedChanges.isEmpty()) {
            return BuildExecutionStatus.ROLLED_BACK;
        }
        beginRollback(false);
        while (rollbackActive) {
            tickRollback(Math.max(1, appliedChanges.size()));
        }
        return mutationStatus != null ? mutationStatus : BuildExecutionStatus.ROLLED_BACK;
    }

    private static long chunkKey(BlockPos pos) {
        if (pos == null) return 0L;
        ChunkPos c = new ChunkPos(pos);
        return (((long) c.x) << 32) ^ (c.z & 0xffffffffL);
    }

    /**
     * 分帧建造的应用结果（累计整个任务）。
     */
    public record BuildApplyResult(
            int totalPlanned,
            int placed,
            int skippedOutOfBounds,
            int skippedSameState,
            int failedUnloaded,
            int skippedIllegal,
            int failedWrites,
            int deferredUnloadedPeak,
            int stillDeferred,
            Status terminalStatus
    ) {
        public BuildApplyResult(
                int totalPlanned, int placed, int skippedOutOfBounds, int skippedSameState,
                int failedUnloaded, int skippedIllegal, int failedWrites,
                int deferredUnloadedPeak, int stillDeferred
        ) {
            this(totalPlanned, placed, skippedOutOfBounds, skippedSameState, failedUnloaded,
                    skippedIllegal, failedWrites, deferredUnloadedPeak, stillDeferred, null);
        }

        /** 兼容旧四参数构造。 */
        public BuildApplyResult(int totalPlanned, int placed, int skippedOutOfBounds, int skippedSameState) {
            this(totalPlanned, placed, skippedOutOfBounds, skippedSameState, 0, 0, 0, 0, 0, null);
        }

        /** 兼容旧七参数（skippedUnloaded 映射为 failedUnloaded）。 */
        public BuildApplyResult(
                int totalPlanned, int placed, int skippedOutOfBounds, int skippedSameState,
                int skippedUnloaded, int skippedIllegal, int failedWrites
        ) {
            this(totalPlanned, placed, skippedOutOfBounds, skippedSameState,
                    skippedUnloaded, skippedIllegal, failedWrites, 0, 0, null);
        }

        public boolean isCompletePlacement() {
            return skippedOutOfBounds + failedUnloaded + skippedIllegal + failedWrites == 0
                    && stillDeferred == 0
                    && totalPlanned == placed + skippedSameState;
        }

        public int skippedUnloaded() {
            return failedUnloaded;
        }

        public int skippedTotal() {
            return skippedOutOfBounds + skippedSameState + failedUnloaded + skippedIllegal;
        }

        public double placedPercent() {
            if (totalPlanned <= 0) return 0.0;
            return 100.0 * placed / totalPlanned;
        }

        public String summaryZh() {
            if (totalPlanned <= 0) {
                return "建造完成：无计划方块";
            }
            StringBuilder sb = new StringBuilder();
            String head = switch (terminalStatus == null ? (isCompletePlacement() ? Status.COMPLETED : Status.PARTIAL) : terminalStatus) {
                case COMPLETED -> "建造完成：计划 ";
                case PARTIAL -> "建造部分完成：计划 ";
                case FAILED -> "建造失败：计划 ";
                case CANCELLED -> "建造已取消：计划 ";
                case ROLLED_BACK -> "建造失败已回滚：计划 ";
                case ROLLING_BACK -> "建造回滚中：计划 ";
                default -> isCompletePlacement() ? "建造完成：计划 " : "建造结束（部分未放置）：计划 ";
            };
            sb.append(head).append(totalPlanned).append(" 个方块");
            sb.append("，实际放置 ").append(placed).append(" 个");
            if (skippedSameState > 0) {
                sb.append("，与地形相同跳过 ").append(skippedSameState).append(" 个");
            }
            if (skippedOutOfBounds > 0) {
                sb.append("，越界跳过 ").append(skippedOutOfBounds).append(" 个");
            }
            if (stillDeferred > 0) {
                sb.append("，等待区块 ").append(stillDeferred).append(" 个");
            }
            if (failedUnloaded > 0) {
                sb.append("，未加载最终失败 ").append(failedUnloaded).append(" 个");
            }
            if (skippedIllegal > 0) sb.append("，非法目标跳过 ").append(skippedIllegal).append(" 个");
            if (failedWrites > 0) sb.append("，写入失败 ").append(failedWrites).append(" 个");
            int unclassified = totalPlanned - placed - skippedTotal() - failedWrites - stillDeferred;
            if (unclassified > 0) {
                sb.append("，未归类 ").append(unclassified).append(" 个");
            }
            sb.append(String.format("（放置率 %.1f%%）", placedPercent()));
            return sb.toString();
        }
    }
}
