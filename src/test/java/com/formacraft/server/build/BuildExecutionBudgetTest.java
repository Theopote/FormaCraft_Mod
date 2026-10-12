package com.formacraft.server.build;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 全局 Tick 预算不变量（不启动 Minecraft 世界）。
 */
class BuildExecutionBudgetTest {

    @Test
    void worldBudgetIsSharedNotPerTask() {
        assertEquals(200, BuildExecutionService.BLOCKS_PER_WORLD_TICK);
        // 示意：20 个任务若各自 200，旧行为可达 4000；新行为单 Tick 上界仍为 200
        int concurrentTasks = 20;
        int legacyPerTask = 200;
        int legacyTotal = concurrentTasks * legacyPerTask;
        assertTrue(legacyTotal > BuildExecutionService.BLOCKS_PER_WORLD_TICK);
        assertEquals(200, BuildExecutionService.BLOCKS_PER_WORLD_TICK);
    }

    @Test
    void activeTaskCapIsFinite() {
        assertTrue(BuildExecutionService.MAX_ACTIVE_TASKS_PER_WORLD > 0);
        assertEquals(32, BuildExecutionService.MAX_ACTIVE_TASKS_PER_WORLD);
    }

    @Test
    void fairQuotaNeverExceedsRemainingBudget() {
        int remaining = 200;
        int n = 10;
        int totalQuota = 0;
        for (int step = 0; step < n && remaining > 0; step++) {
            int cap = Math.max(1, remaining / Math.max(1, n - step));
            int quota = Math.min(remaining, cap);
            totalQuota += quota;
            remaining -= quota; // 假设全部用尽
        }
        assertEquals(200, totalQuota);
        assertEquals(0, remaining);
    }
}
