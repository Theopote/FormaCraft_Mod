package com.formacraft.server.build;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 建造任务：分 Tick 施工，未加载区块暂缓重试，单方块异常隔离，写后校验。
 */
public class BuildTask {
    public enum Status {
        RUNNING,
        WAITING_CHUNKS,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private static final int DEFAULT_MAX_DEFER_ATTEMPTS = 120;
    private static final long DEFAULT_DEFER_TIMEOUT_MS = 30_000L;
    /** 测试可覆盖：未加载重试上限。 */
    static int maxDeferAttempts = DEFAULT_MAX_DEFER_ATTEMPTS;
    /** 测试可覆盖：未加载超时（毫秒）。 */
    static long deferTimeoutMs = DEFAULT_DEFER_TIMEOUT_MS;

    private final GeneratedStructure structure;
    private final ServerWorld world;
    private final com.formacraft.common.world.BlockMutationAccess access;
    private final List<BlockChange> appliedChanges = new ArrayList<>();
    private final ArrayDeque<DeferredBlock> deferred = new ArrayDeque<>();
    private final long startedAtMs = System.currentTimeMillis();

    private int index = 0;
    private Status status = Status.RUNNING;
    private int skippedOutOfBounds = 0;
    private int skippedSameState = 0;
    private int skippedIllegal = 0;
    private int failedWrites = 0;
    private int failedUnloaded = 0;
    private int deferredUnloadedPeak = 0;

    private record DeferredBlock(PlannedBlock planned, int attempts, long firstDeferredMs) {}

    public BuildTask(GeneratedStructure structure, ServerWorld world) {
        this(structure, world, com.formacraft.common.world.BlockMutationAccess.forWorld(world));
    }

    BuildTask(GeneratedStructure structure, ServerWorld world,
              com.formacraft.common.world.BlockMutationAccess access) {
        this.structure = java.util.Objects.requireNonNull(structure, "structure");
        this.world = world;
        this.access = java.util.Objects.requireNonNull(access, "access");
    }

    public boolean isFinished() {
        return status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELLED;
    }

    public Status status() {
        return status;
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
                deferred.size()
        );
    }

    /**
     * 执行最多 maxPerTick 个方块修改（含主队列与待重试）。
     */
    public int tick(int maxPerTick) {
        if (isFinished() || maxPerTick <= 0) return 0;

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
        if (deferred.isEmpty()) return 0;
        // 按区块分组：同一 ChunkPos 优先连续处理
        List<DeferredBlock> batch = new ArrayList<>(deferred);
        deferred.clear();
        batch.sort(Comparator.comparingLong((DeferredBlock d) -> chunkKey(d.planned().getPos())));

        int count = 0;
        for (DeferredBlock item : batch) {
            if (count >= maxPerTick) {
                deferred.addLast(item);
                continue;
            }
            count += processDeferred(item);
        }
        return count;
    }

    private int processDeferred(DeferredBlock item) {
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
                return requeueOrFail(item);
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
                long now = System.currentTimeMillis();
                deferred.addLast(new DeferredBlock(planned, 1, now));
                deferredUnloadedPeak = Math.max(deferredUnloadedPeak, deferred.size());
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

    private int requeueOrFail(DeferredBlock item) {
        long now = System.currentTimeMillis();
        int nextAttempts = item.attempts() + 1;
        boolean timedOut = (now - item.firstDeferredMs()) > deferTimeoutMs;
        boolean tooMany = nextAttempts > maxDeferAttempts;
        if (timedOut || tooMany) {
            failedUnloaded++;
            return 1;
        }
        deferred.addLast(new DeferredBlock(item.planned(), nextAttempts, item.firstDeferredMs()));
        deferredUnloadedPeak = Math.max(deferredUnloadedPeak, deferred.size());
        return 1;
    }

    private int applyPlanned(PlannedBlock planned) {
        BlockPos pos = planned.getPos();
        var from = access.getSnapshot(pos);
        var toState = planned.getTargetState();
        if (from.state().equals(toState)) {
            skippedSameState++;
            return 1;
        }
        var target = com.formacraft.common.world.BlockSnapshot.of(toState);
        boolean accepted = access.setSnapshot(pos, target);
        var after = access.getSnapshot(pos);
        if (!accepted || !after.state().equals(toState)) {
            failedWrites++;
            return 1;
        }
        appliedChanges.add(new BlockChange(pos.toImmutable(), from, after));
        return 1;
    }

    private void updateStatusAfterTick() {
        boolean mainDone = index >= structure.getBlocks().size();
        if (!mainDone) {
            status = Status.RUNNING;
            return;
        }
        if (!deferred.isEmpty()) {
            status = Status.WAITING_CHUNKS;
            return;
        }
        status = Status.COMPLETED;
        com.formacraft.FormacraftMod.LOGGER.info("BuildTask: finished. {}", result().summaryZh());
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
            int stillDeferred
    ) {
        /** 兼容旧四参数构造。 */
        public BuildApplyResult(int totalPlanned, int placed, int skippedOutOfBounds, int skippedSameState) {
            this(totalPlanned, placed, skippedOutOfBounds, skippedSameState, 0, 0, 0, 0, 0);
        }

        /** 兼容旧七参数（skippedUnloaded 映射为 failedUnloaded）。 */
        public BuildApplyResult(
                int totalPlanned, int placed, int skippedOutOfBounds, int skippedSameState,
                int skippedUnloaded, int skippedIllegal, int failedWrites
        ) {
            this(totalPlanned, placed, skippedOutOfBounds, skippedSameState,
                    skippedUnloaded, skippedIllegal, failedWrites, 0, 0);
        }

        public boolean isCompletePlacement() {
            return skippedOutOfBounds + failedUnloaded + skippedIllegal + failedWrites == 0
                    && stillDeferred == 0
                    && totalPlanned == placed + skippedSameState;
        }

        /** 兼容旧测试/调用：最终未加载失败数。 */
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
            sb.append(isCompletePlacement() ? "建造完成：计划 " : "建造结束（部分未放置）：计划 ")
                    .append(totalPlanned).append(" 个方块");
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
