package com.formacraft.server.build;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.List;

/**
 * 建造任务
 * 一个正在施工中的任务
 */
public class BuildTask {
    private final GeneratedStructure structure;
    private final ServerWorld world;
    private final com.formacraft.common.world.BlockMutationAccess access;
    private final List<BlockChange> appliedChanges = new ArrayList<>();
    private int index = 0;
    private boolean finished = false;
    private int skippedOutOfBounds = 0;
    private int skippedSameState = 0;
    private int skippedUnloaded = 0;
    private int skippedIllegal = 0;
    private int failedWrites = 0;

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
        return finished;
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
                skippedSameState, skippedUnloaded, skippedIllegal, failedWrites
        );
    }

    /**
     * 执行最多 maxPerTick 个方块修改。
     * 返回本 tick 实际处理的数量（含跳过）。
     */
    public int tick(int maxPerTick) {
        if (finished) return 0;

        int count = 0;
        var blocks = structure.getBlocks();

        while (index < blocks.size() && count < maxPerTick) {
            PlannedBlock planned = blocks.get(index++);

            if (planned == null || planned.getPos() == null || planned.getTargetState() == null) {
                skippedIllegal++;
                count++;
                continue;
            }
            var pos = planned.getPos();
            if (!access.isInsideHeight(pos)) {
                skippedOutOfBounds++;
                count++;
                continue;
            }
            if (!access.isChunkReady(pos)) {
                skippedUnloaded++;
                count++;
                continue;
            }
            var fromState = access.getState(pos);
            var toState = planned.getTargetState();
            if (fromState.equals(toState)) {
                skippedSameState++;
            } else if (access.setState(pos, toState)) {
                appliedChanges.add(new BlockChange(pos.toImmutable(), fromState, access.getState(pos)));
            } else {
                failedWrites++;
            }

            count++;
        }

        if (index >= blocks.size()) {
            finished = true;
            BuildApplyResult result = result();
            com.formacraft.FormacraftMod.LOGGER.info(
                    "BuildTask: finished. {}",
                    result.summaryZh()
            );
        }

        return count;
    }

    /**
     * 分帧建造的应用结果（累计整个任务，而非单个 tick 批次）。
     */
    public record BuildApplyResult(
            int totalPlanned,
            int placed,
            int skippedOutOfBounds,
            int skippedSameState,
            int skippedUnloaded,
            int skippedIllegal,
            int failedWrites
    ) {
        public BuildApplyResult(int totalPlanned, int placed, int skippedOutOfBounds, int skippedSameState) {
            this(totalPlanned, placed, skippedOutOfBounds, skippedSameState, 0, 0, 0);
        }
        public boolean isCompletePlacement() {
            return skippedOutOfBounds + skippedUnloaded + skippedIllegal + failedWrites == 0
                    && totalPlanned == placed + skippedSameState;
        }
        public int skippedTotal() {
            return skippedOutOfBounds + skippedSameState + skippedUnloaded + skippedIllegal;
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
            sb.append(isCompletePlacement() ? "建造完成：计划 " : "建造结束（部分未放置）：计划 ").append(totalPlanned).append(" 个方块");
            sb.append("，实际放置 ").append(placed).append(" 个");
            if (skippedSameState > 0) {
                sb.append("，与地形相同跳过 ").append(skippedSameState).append(" 个");
            }
            if (skippedOutOfBounds > 0) {
                sb.append("，越界跳过 ").append(skippedOutOfBounds).append(" 个");
            }
            if (skippedUnloaded > 0) sb.append("，未加载跳过 ").append(skippedUnloaded).append(" 个");
            if (skippedIllegal > 0) sb.append("，非法目标跳过 ").append(skippedIllegal).append(" 个");
            if (failedWrites > 0) sb.append("，写入失败 ").append(failedWrites).append(" 个");
            if (placed < totalPlanned - skippedTotal() - failedWrites) {
                sb.append("（未归类 ").append(totalPlanned - placed - skippedTotal() - failedWrites).append(" 个）");
            }
            sb.append(String.format("（放置率 %.1f%%）", placedPercent()));
            return sb.toString();
        }
    }
}
