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
    private final List<BlockChange> appliedChanges = new ArrayList<>();
    private int index = 0;
    private boolean finished = false;

    public BuildTask(GeneratedStructure structure, ServerWorld world) {
        this.structure = structure;
        this.world = world;
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

    /**
     * 执行最多 maxPerTick 个方块修改。
     * 返回本 tick 实际执行的数量。
     */
    public int tick(int maxPerTick) {
        if (finished) return 0;

        int count = 0;
        int skippedOutOfBounds = 0;
        int skippedSameState = 0;
        var blocks = structure.getBlocks();

        while (index < blocks.size() && count < maxPerTick) {
            PlannedBlock planned = blocks.get(index++);

            var pos = planned.getPos();
            var fromState = world.getBlockState(pos);
            var toState = planned.getTargetState();

            // 检查位置是否有效（世界构建高度范围）
            // 不能用 Heightmap.WORLD_SURFACE：那是"地表高度"，并不是可建造高度上限；
            // 如果锚点/建筑在地表之上（常见于山坡/平台），会导致全部方块被跳过，表现为"确认后啥都没建出来"。
            int topYExclusive = world.getBottomY() + world.getHeight();
            if (pos.getY() < world.getBottomY() || pos.getY() >= topYExclusive) {
                skippedOutOfBounds++;
                continue;
            }

            if (!fromState.equals(toState)) {
                world.setBlockState(pos, toState, 3);
                appliedChanges.add(new BlockChange(pos, fromState, toState));
            } else {
                skippedSameState++;
            }

            count++;
        }

        if (index >= blocks.size()) {
            finished = true;
            if (skippedOutOfBounds > 0 || skippedSameState > 0) {
                com.formacraft.FormacraftMod.LOGGER.info("BuildTask: finished. Total blocks: {}, placed: {}, skipped (out of bounds): {}, skipped (same state): {}", 
                        blocks.size(), appliedChanges.size(), skippedOutOfBounds, skippedSameState);
            }
        }

        return count;
    }
}

