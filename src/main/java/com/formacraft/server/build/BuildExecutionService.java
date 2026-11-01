package com.formacraft.server.build;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.city.CitySpec;
import com.formacraft.server.city.CityBuilder;
import com.formacraft.server.generation.structure.StructureGenerator;
import com.formacraft.server.generation.structure.StructureGeneratorFactory;
import com.formacraft.server.edit.BuildingEditService;
import com.formacraft.server.edit.BuildingMemoryKeys;
import com.formacraft.server.edit.BuildingPartIndex;
import com.formacraft.server.memory.BuildUndoMemoryUpdate;
import com.formacraft.server.memory.MemoryCommitRecord;
import com.formacraft.server.memory.MemoryManager;
import com.formacraft.server.memory.ProjectMemory;
import com.formacraft.server.state.PlayerSpecRepository;
import com.formacraft.server.terrain.TerrainAdaptationEngine;
import com.formacraft.server.terrain.TerrainAdaptationMode;
import com.formacraft.server.terrain.TerrainAdaptationResolver;
import com.formacraft.server.terrain.TerrainAdaptationSpec;
import com.formacraft.server.terrain.TerrainShaper;
import com.formacraft.FormacraftMod;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 建造执行服务
 * 核心执行服务，维护全局的 BuildTask 列表
 * 每个世界 Tick 时执行一部分方块
 * 任务完成后自动将结果交给 UndoService
 */
public class BuildExecutionService {
    /** 每个世界 Tick 所有任务共享的方块处理预算（非每任务独立）。 */
    static final int BLOCKS_PER_WORLD_TICK = 200;
    /** 单世界同时进行的建造任务上限；超额拒绝排队。 */
    static final int MAX_ACTIVE_TASKS_PER_WORLD = 32;

    private final List<BuildTask> activeTasks = new ArrayList<>();
    /** Memory commits that failed to persist; retried on later ticks (world changed but Memory missing). */
    private final List<MemoryCommitRecord> pendingMemoryCommits = new ArrayList<>();
    /** Commits that exhausted their retries — kept for diagnostics / manual recovery. */
    private final List<MemoryCommitRecord> failedMemoryCommitRecords = new ArrayList<>();
    /** Commits cancelled because the building was fully undone — a redo may revive them. */
    private final List<MemoryCommitRecord> cancelledMemoryCommitRecords = new ArrayList<>();
    private final UndoService undoService = new UndoService();
    private MemoryManager memoryManager;
    /** 公平轮询下标：按世界分开，避免队首任务独占预算。 */
    private final java.util.Map<ServerWorld, Integer> nextTaskIndexByWorld = new java.util.WeakHashMap<>();

    private static BuildExecutionService INSTANCE;
    private static boolean TICK_HANDLER_REGISTERED = false;

    private BuildExecutionService() {}
    
    /**
     * 设置记忆管理器（由 ServerInitializer 调用）
     */
    public void setMemoryManager(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
        undoService.setMemoryManager(memoryManager);
    }
    
    /**
     * 获取记忆管理器（用于 RAG 功能）
     */
    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    public static BuildExecutionService getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new BuildExecutionService();
        }
        return INSTANCE;
    }

    public UndoService getUndoService() {
        return undoService;
    }

    /**
     * 提交一个建造任务（使用 BuildingSpec）
     * @param world 服务器世界
     * @param origin 建造原点
     * @param spec 建筑规格
     * @param owner 玩家 UUID（可选，用于 Undo）
     */
    public void queueBuild(ServerWorld world, BlockPos origin, BuildingSpec spec, UUID owner) {
        StructureGenerator generator = StructureGeneratorFactory.getGenerator(spec);

        // Resolve terrain adaptation (new) or fallback to legacy "always flatten" behavior when unspecified.
        TerrainAdaptationSpec ta = TerrainAdaptationResolver.resolve(spec != null ? spec.getExtra() : null);
        boolean legacyAlwaysFlatten = (spec == null || spec.getExtra() == null || spec.getExtra().isEmpty())
                && (ta.mode() == TerrainAdaptationMode.DEFAULT); // preserve old UX when no extra is provided

        // Compute bounds for terrain operations
        TerrainAdaptationEngine.Bounds bounds = TerrainAdaptationEngine.boundsFor(spec, origin);
        
        // Resolve fill material for terrain ops
        net.minecraft.block.BlockState fillMaterial = net.minecraft.block.Blocks.DIRT.getDefaultState();
        if (spec != null && spec.getMaterials() != null && spec.getMaterials().getFoundation() != null) {
            try {
                net.minecraft.util.Identifier id = net.minecraft.util.Identifier.of(spec.getMaterials().getFoundation());
                net.minecraft.block.Block block = net.minecraft.registry.Registries.BLOCK.get(id);
                fillMaterial = block.getDefaultState();
            } catch (Exception e) {
                // 使用默认材质
            }
        }

        BlockPos origin2 = origin;
        List<com.formacraft.common.build.PlannedBlock> pre = List.of();
        boolean postDrape = false;
        int drapeBaseY = origin.getY();

        if (legacyAlwaysFlatten) {
            // Old behavior: always flatten around the unit AABB before placing blocks.
            GeneratedStructure structure = generator.generate(spec, origin, world);
            structure = TerrainShaper.preprocessStructure(world, structure, bounds.min(), bounds.max(), fillMaterial);
            if (owner != null) structure = new GeneratedStructure(owner, origin, structure.getDescription(), structure.getBlocks());
            enqueueBuild(world, structure);
            return;
        }

        // New behavior: interpret mode
        TerrainAdaptationMode mode = ta.mode();
        if (mode == TerrainAdaptationMode.DEFAULT) mode = TerrainAdaptationMode.ANCHOR; // safer default than flattening

        if (mode == TerrainAdaptationMode.FLOAT) {
            // fixed altitude (best-effort)
            int y = (ta.fixedY() != null) ? ta.fixedY() : origin.getY();
            origin2 = new BlockPos(origin.getX(), y, origin.getZ());
        } else if (mode == TerrainAdaptationMode.EMBED) {
            int base = TerrainAdaptationEngine.computeBaseY(world, bounds, ta, origin.getY());
            origin2 = new BlockPos(origin.getX(), base - ta.embedDepth(), origin.getZ());
            pre = TerrainAdaptationEngine.carve(world, bounds, base - ta.embedDepth(), ta.clearHeight());
        } else if (mode == TerrainAdaptationMode.FLATTEN) {
            // keep origin as is; preprocessing will handle flatten.
        } else if (mode == TerrainAdaptationMode.ANCHOR) {
            int base = TerrainAdaptationEngine.computeBaseY(world, bounds, ta, origin.getY());
            origin2 = new BlockPos(origin.getX(), base + 1, origin.getZ());
            // shallow pad/clear (re-use TerrainFit semantics via TerrainFit-like implementation) -> here we use pillars optionally
            if (ta.anchorExtendDown()) {
                pre = TerrainAdaptationEngine.anchorPillars(world, bounds, origin2.getY(), fillMaterial, ta.anchorMaxDepth(), ta.allowWaterEdit(), ta.allowLavaEdit());
            }
        } else if (mode == TerrainAdaptationMode.DRAPE) {
            int base = TerrainAdaptationEngine.computeBaseY(world, bounds, ta, origin.getY());
            origin2 = new BlockPos(origin.getX(), base + 1, origin.getZ());
            postDrape = true;
            drapeBaseY = base + 1;
            // anti-gap: extend foundation into terrain
            pre = TerrainAdaptationEngine.drapeFoundationColumns(world, bounds, ta.foundationDepth(), fillMaterial, ta.allowWaterEdit(), ta.allowLavaEdit());
        }

        GeneratedStructure structure = generator.generate(spec, origin2, world);

        if (mode == TerrainAdaptationMode.FLATTEN) {
            structure = TerrainShaper.preprocessStructure(world, structure, bounds.min(), bounds.max(), fillMaterial);
        } else {
            // merge pre-ops if any
            if (postDrape) {
                List<com.formacraft.common.build.PlannedBlock> draped = TerrainAdaptationEngine.drape(world, structure.getBlocks(), drapeBaseY, ta.drapeMaxStep(), bounds);
                List<com.formacraft.common.build.PlannedBlock> merged = new ArrayList<>(pre.size() + draped.size());
                merged.addAll(pre);
                merged.addAll(draped);
                structure = new GeneratedStructure(structure.getOwner(), structure.getOrigin(), structure.getDescription() + " + Terrain(DRAPE)", merged);
            } else if (!pre.isEmpty()) {
                List<com.formacraft.common.build.PlannedBlock> merged = new ArrayList<>(pre.size() + structure.getBlocks().size());
                merged.addAll(pre);
                merged.addAll(structure.getBlocks());
                structure = new GeneratedStructure(structure.getOwner(), structure.getOrigin(), structure.getDescription() + " + Terrain", merged);
            }
        }
        
        // 如果提供了 owner，更新 GeneratedStructure
        if (owner != null) {
            structure = new GeneratedStructure(owner, origin2, structure.getDescription(), structure.getBlocks());
        }
        
        enqueueBuild(world, structure);
    }

    /**
     * 提交一个建造任务（使用 BuildingSpec，无 owner）
     */
    public void queueBuild(ServerWorld world, BlockPos origin, BuildingSpec spec) {
        queueBuild(world, origin, spec, null);
    }

    /**
     * 提交一个复合结构建造任务（使用 CompositeSpec）
     * @param world 服务器世界
     * @param origin 复合结构的原点
     * @param compositeSpec 复合结构规格
     * @param owner 玩家 UUID（可选，用于 Undo）
     */
    public void queueCompositeBuild(ServerWorld world, BlockPos origin, 
                                    com.formacraft.common.model.composite.CompositeSpec compositeSpec, UUID owner) {
        com.formacraft.server.generation.structure.composite.CompositeStructureGenerator generator = 
                new com.formacraft.server.generation.structure.composite.CompositeStructureGenerator();
        GeneratedStructure structure = generator.generate(compositeSpec, origin, world);
        
        // 如果提供了 owner，更新 GeneratedStructure
        if (owner != null) {
            structure = new GeneratedStructure(owner, origin, structure.getDescription(), structure.getBlocks());
        }
        
        enqueueBuild(world, structure);
    }

    /**
     * 提交一个复合结构建造任务（使用 CompositeSpec，无 owner）
     */
    public void queueCompositeBuild(ServerWorld world, BlockPos origin, 
                                    com.formacraft.common.model.composite.CompositeSpec compositeSpec) {
        queueCompositeBuild(world, origin, compositeSpec, null);
    }

    /**
     * 提交一个城市建造任务（使用 CitySpec）
     * @param world 服务器世界
     * @param origin 建造原点
     * @param citySpec 城市规格
     * @param owner 玩家 UUID（可选，用于 Undo）
     */
    public void queueCityBuild(ServerWorld world, BlockPos origin, CitySpec citySpec, UUID owner) {
        CityBuilder cityBuilder = new CityBuilder();
        GeneratedStructure structure = cityBuilder.generate(citySpec, origin, world);

        if (owner != null) {
            structure = new GeneratedStructure(owner, origin, structure.getDescription(), structure.getBlocks());
        }
        enqueueBuild(world, structure);
    }

    /**
     * 提交一个城市建造任务（使用 CitySpec，无 owner）
     */
    public void queueCityBuild(ServerWorld world, BlockPos origin, CitySpec citySpec) {
        queueCityBuild(world, origin, citySpec, null);
    }

   /**
     * 提交一个建造任务（使用 GeneratedStructure）
     * @return true 已入队；false 因同世界任务数超限被拒绝
     */
    public boolean enqueueBuild(ServerWorld world, GeneratedStructure structure) {
        return enqueueBuild(world, structure, null);
    }

    public boolean enqueueBuild(ServerWorld world, GeneratedStructure structure, String fbdSourcePlanJson) {
        if (world == null || structure == null) return false;
        int worldActive = 0;
        for (BuildTask t : activeTasks) {
            if (t != null && t.getWorld() == world) worldActive++;
        }
        if (worldActive >= MAX_ACTIVE_TASKS_PER_WORLD) {
            FormacraftMod.LOGGER.warn(
                    "Rejecting build enqueue: world already has {} active tasks (max={})",
                    worldActive, MAX_ACTIVE_TASKS_PER_WORLD);
            return false;
        }
        activeTasks.add(new BuildTask(structure, world, fbdSourcePlanJson));
        FormacraftMod.LOGGER.info("Queued build task: {} blocks at {} (owner: {})",
                structure.size(), structure.getOrigin(), structure.getOwner());
        return true;
    }

    /**
     * 取消该玩家在本世界的进行中建造任务。
     * {@link BuildTask#cancel()} 会尝试全批回滚；仅回滚不完整时的残留经 completeTask 写入 Undo。
     * @return 取消的任务数
     */
    public int cancelBuildsForPlayer(java.util.UUID playerId, ServerWorld world) {
        if (playerId == null) return 0;
        int n = 0;
        for (BuildTask t : activeTasks) {
            if (t == null || t.isFinished()) continue;
            if (world != null && t.getWorld() != world) continue;
            var structure = t.getStructure();
            if (structure == null || !playerId.equals(structure.getOwner())) continue;
            t.cancel();
            n++;
        }
        return n;
    }

    /** Server-thread guard: an unfinished build has no stable edit snapshot yet. */
    public boolean hasActiveBuildForPlayer(UUID playerId, ServerWorld world) {
        if (playerId == null) return false;
        for (BuildTask task : activeTasks) {
            if (task != null && !task.isFinished() && task.getWorld() == world
                    && task.getStructure() != null && playerId.equals(task.getStructure().getOwner())) return true;
        }
        return false;
    }

    /**
     * 每个世界 tick：全局预算公平调度本世界任务。
     */
    public void onWorldTick(ServerWorld world) {
        if (world == null) return;
        retryPendingMemoryCommits(world);
        if (activeTasks.isEmpty()) return;

        List<Integer> worldIndices = new ArrayList<>();
        for (int i = 0; i < activeTasks.size(); i++) {
            BuildTask t = activeTasks.get(i);
            if (t != null && t.getWorld() == world) {
                worldIndices.add(i);
            }
        }
        if (worldIndices.isEmpty()) return;

        int remainingBudget = BLOCKS_PER_WORLD_TICK;
        int n = worldIndices.size();
        int startOffset = Math.floorMod(nextTaskIndexByWorld.getOrDefault(world, 0), n);

        List<BuildTask> finished = new ArrayList<>();
        for (int step = 0; step < n && remainingBudget > 0; step++) {
            int local = (startOffset + step) % n;
            int globalIdx = worldIndices.get(local);
            BuildTask task = activeTasks.get(globalIdx);
            if (task == null || task.isFinished()) continue;

            int cap = Math.max(1, remainingBudget / Math.max(1, n - step));
            int quota = Math.min(remainingBudget, cap);
            int executed = task.tick(quota);
            if (executed > 0) {
                remainingBudget -= executed;
                FormacraftMod.LOGGER.debug("Executed {} blocks (budget left={})", executed, remainingBudget);
            }
            if (task.isFinished()) {
                finished.add(task);
            }
        }
        nextTaskIndexByWorld.put(world, Math.floorMod(startOffset + 1, n));

        for (BuildTask task : finished) {
            activeTasks.remove(task);
            completeTask(world, task);
        }
    }

    /** 单 Tick 内实际处理量上界（测试钩子）。 */
    int blocksPerWorldTick() {
        return BLOCKS_PER_WORLD_TICK;
    }

    int activeTaskCount(ServerWorld world) {
        int c = 0;
        for (BuildTask t : activeTasks) {
            if (t != null && t.getWorld() == world) c++;
        }
        return c;
    }

    /** Deferred Memory registration after a durable-persist failure. */
    private static final int MAX_MEMORY_COMMIT_ATTEMPTS = 5;
    private static final long MEMORY_COMMIT_RETRY_MS = 10_000L;

    /** Stable Memory identity for one build transaction (stable across retries). */
    static String stableMemoryUuid(UUID transactionId, UUID ownerUuid, String dimension) {
        String seed = transactionId != null
                ? transactionId.toString()
                : String.valueOf(ownerUuid) + "|" + dimension;
        return UUID.nameUUIDFromBytes(
                ("formacraft:build:" + seed).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    /** A pending commit may only be retried by the world that owns its dimension. */
    static boolean matchesDimension(MemoryCommitRecord record, String worldDimension) {
        if (record == null || record.dimension == null || worldDimension == null) return false;
        return record.dimension.equals(worldDimension);
    }

    /**
     * Queue a pending Memory commit.
     * <p>
     * The record is written to the durable commit log first. If that write fails the
     * record is <b>not</b> treated as reliably queued: it stays in the in-memory queue
     * for this session, but the caller is told so it can raise a high-priority error
     * instead of silently assuming restart recovery.
     *
     * @return true when the record is durably persisted (survives a restart)
     */
    boolean queuePendingMemoryCommit(MemoryCommitRecord record) {
        if (record == null || record.memoryUuid == null) return false;
        if (record.nextAttemptMs <= 0L) {
            record.nextAttemptMs = System.currentTimeMillis() + MEMORY_COMMIT_RETRY_MS;
        }
        boolean durable = memoryManager != null && memoryManager.saveCommitRecord(record);
        if (!durable) {
            FormacraftMod.LOGGER.error(
                    "Memory commit compensation record could not be persisted for {}; "
                            + "the pending commit lives in memory only and will be lost on restart",
                    record.memoryUuid);
        }
        synchronized (pendingMemoryCommits) {
            pendingMemoryCommits.removeIf(existing ->
                    existing.memoryUuid != null && existing.memoryUuid.equals(record.memoryUuid));
            pendingMemoryCommits.add(record);
        }
        return durable;
    }

    /** Number of deferred Memory commits awaiting retry (diagnostics / tests). */
    int pendingMemoryCommitCount() {
        synchronized (pendingMemoryCommits) {
            return pendingMemoryCommits.size();
        }
    }

    /** Drop all queued Memory commits (test isolation). */
    void clearPendingMemoryCommitsForTest() {
        synchronized (pendingMemoryCommits) {
            pendingMemoryCommits.clear();
            failedMemoryCommitRecords.clear();
            cancelledMemoryCommitRecords.clear();
        }
    }

    /**
     * Record undo progress for a still-pending Memory commit.
     * A fully undone building must never be resurrected by a later retry.
     * <p>
     * A redo (incomplete undo state) revives a previously cancelled commit.
     */
    void notePendingMemoryUndo(String memoryUuid, int restored, int total, boolean complete) {
        if (memoryUuid == null) return;
        synchronized (pendingMemoryCommits) {
            for (MemoryCommitRecord record : pendingMemoryCommits) {
                if (record == null || !memoryUuid.equals(record.memoryUuid)) continue;
                applyUndoState(record, restored, total, complete);
                return;
            }
            // A redo after a cancelled commit must bring the commit back.
            for (MemoryCommitRecord record : cancelledMemoryCommitRecords) {
                if (record == null || !memoryUuid.equals(record.memoryUuid)) continue;
                if (complete) {
                    applyUndoState(record, restored, total, complete);
                    return;
                }
                cancelledMemoryCommitRecords.remove(record);
                applyUndoState(record, restored, total, false);
                record.status = MemoryCommitRecord.Status.PENDING;
                record.attempts = 0;
                record.nextAttemptMs = 0L;
                pendingMemoryCommits.add(record);
                if (memoryManager != null && !memoryManager.saveCommitRecord(record)) {
                    FormacraftMod.LOGGER.error(
                            "Revived Memory commit {} could not be persisted; recovery after restart is lost",
                            record.memoryUuid);
                }
                FormacraftMod.LOGGER.info("Revived cancelled Memory commit {} after redo", memoryUuid);
                return;
            }
        }
    }

    private void applyUndoState(MemoryCommitRecord record, int restored, int total, boolean complete) {
        record.undoRestoredPositions = restored;
        record.undoTotalPositions = total;
        record.undoComplete = complete;
        if (memoryManager != null && !memoryManager.saveCommitRecord(record)) {
            FormacraftMod.LOGGER.error(
                    "Memory commit record {} could not be persisted; undo state survives only in memory",
                    record.memoryUuid);
        }
    }

    /**
     * Pending Memory commits that exhausted their retries and need manual attention.
     */
    public List<MemoryCommitRecord> failedMemoryCommits() {
        synchronized (pendingMemoryCommits) {
            return new ArrayList<>(failedMemoryCommitRecords);
        }
    }

    /**
     * Move exhausted commits back into the retry queue (manual recovery entry point).
     *
     * @return number of commits scheduled for retry
     */
    public int retryFailedMemoryCommits() {
        int scheduled = 0;
        synchronized (pendingMemoryCommits) {
            for (MemoryCommitRecord record : failedMemoryCommitRecords) {
                record.status = MemoryCommitRecord.Status.PENDING;
                record.attempts = 0;
                record.nextAttemptMs = 0L;
                record.lastError = null;
                if (memoryManager != null && !memoryManager.saveCommitRecord(record)) {
                    FormacraftMod.LOGGER.error(
                            "Memory commit record {} could not be persisted; manual recovery may be lost on restart",
                            record.memoryUuid);
                }
                pendingMemoryCommits.removeIf(existing -> existing.memoryUuid.equals(record.memoryUuid));
                pendingMemoryCommits.add(record);
                scheduled++;
            }
            failedMemoryCommitRecords.clear();
        }
        if (scheduled > 0) {
            FormacraftMod.LOGGER.warn("Re-queued {} Memory commit(s) for manual recovery", scheduled);
        }
        return scheduled;
    }

    /**
     * Reload deferred Memory commits after a restart so a commit interrupted by a
     * shutdown is not lost.
     */
    void recoverPendingMemoryCommits() {
        if (memoryManager == null) return;
        List<MemoryCommitRecord> records = memoryManager.loadCommitRecords();
        if (records.isEmpty()) return;
        int restored = 0;
        synchronized (pendingMemoryCommits) {
            for (MemoryCommitRecord record : records) {
                if (record == null || record.memoryUuid == null) continue;
                if (record.status == MemoryCommitRecord.Status.COMMITTED
                        || record.status == MemoryCommitRecord.Status.CANCELLED) {
                    memoryManager.deleteCommitRecord(record.memoryUuid);
                    if (record.status == MemoryCommitRecord.Status.CANCELLED) {
                        cancelledMemoryCommitRecords.removeIf(
                                existing -> existing.memoryUuid.equals(record.memoryUuid));
                        cancelledMemoryCommitRecords.add(record);
                    }
                    continue;
                }
                if (record.status != MemoryCommitRecord.Status.FAILED_NEEDS_ATTENTION) {
                    record.attempts = 0;
                    record.status = MemoryCommitRecord.Status.PENDING;
                    record.nextAttemptMs = 0L;
                    restored++;
                }
                pendingMemoryCommits.removeIf(existing -> existing.memoryUuid.equals(record.memoryUuid));
                if (record.status == MemoryCommitRecord.Status.FAILED_NEEDS_ATTENTION) {
                    failedMemoryCommitRecords.removeIf(existing -> existing.memoryUuid.equals(record.memoryUuid));
                    failedMemoryCommitRecords.add(record);
                    continue;
                }
                pendingMemoryCommits.add(record);
            }
        }
        if (restored > 0) {
            FormacraftMod.LOGGER.warn("Recovered {} pending Memory commit(s) after restart", restored);
        }
    }

    /**
     * Retry deferred Memory commits so "world changed but Memory missing" is not permanent.
     * Only commits whose owning dimension matches {@code world} are retried.
     */
    void retryPendingMemoryCommits(ServerWorld world) {
        if (world == null) return;
        retryPendingMemoryCommits(world.getRegistryKey().getValue().toString());
    }

    /**
     * Dimension-scoped retry. A pending commit is only ever retried by the world that
     * owns its dimension — a Nether tick can never commit an overworld building.
     */
    void retryPendingMemoryCommits(String worldDimension) {
        if (worldDimension == null || memoryManager == null) return;
        List<MemoryCommitRecord> snapshot;
        synchronized (pendingMemoryCommits) {
            if (pendingMemoryCommits.isEmpty()) return;
            snapshot = new ArrayList<>(pendingMemoryCommits);
        }
        long now = System.currentTimeMillis();
        for (MemoryCommitRecord pending : snapshot) {
            if (pending == null || pending.memoryUuid == null) continue;
            // Dimension guard: a commit belongs to exactly one world.
            if (!matchesDimension(pending, worldDimension)) {
                continue;
            }
            if (pending.nextAttemptMs > now) continue;
            if (pending.status == MemoryCommitRecord.Status.FAILED_NEEDS_ATTENTION) {
                continue;
            }
            // The building was fully undone before the Memory existed: do not resurrect it.
            if (pending.fullyUndone()) {
                pending.status = MemoryCommitRecord.Status.CANCELLED;
                memoryManager.saveCommitRecord(pending);
                memoryManager.deleteCommitRecord(pending.memoryUuid);
                synchronized (pendingMemoryCommits) {
                    pendingMemoryCommits.remove(pending);
                    cancelledMemoryCommitRecords.removeIf(
                            existing -> existing.memoryUuid.equals(pending.memoryUuid));
                    cancelledMemoryCommitRecords.add(pending);
                }
                FormacraftMod.LOGGER.info(
                        "Cancelled Memory commit {}: building was fully undone before commit", pending.memoryUuid);
                continue;
            }
            boolean done = commitMemory(pending);
            synchronized (pendingMemoryCommits) {
                if (done) {
                    pendingMemoryCommits.remove(pending);
                    memoryManager.deleteCommitRecord(pending.memoryUuid);
                } else if (pending.attempts >= MAX_MEMORY_COMMIT_ATTEMPTS) {
                    pending.status = MemoryCommitRecord.Status.FAILED_NEEDS_ATTENTION;
                    pending.lastError = "max_attempts_exceeded";
                    boolean durable = memoryManager.saveCommitRecord(pending);
                    pendingMemoryCommits.remove(pending);
                    failedMemoryCommitRecords.removeIf(existing -> existing.memoryUuid.equals(pending.memoryUuid));
                    failedMemoryCommitRecords.add(pending);
                    FormacraftMod.LOGGER.error(
                            "Memory commit needs attention for {} after {} attempts (record kept for recovery{})",
                            pending.memoryUuid, pending.attempts, durable ? "" : " — NOT persisted, lost on restart");
                } else {
                    pending.attempts++;
                    pending.nextAttemptMs = now + MEMORY_COMMIT_RETRY_MS;
                    pending.status = MemoryCommitRecord.Status.PENDING;
                    if (!memoryManager.saveCommitRecord(pending)) {
                        FormacraftMod.LOGGER.error(
                                "Memory commit record {} could not be persisted; recovery after restart is lost",
                                pending.memoryUuid);
                    }
                }
            }
        }
    }

    /**
     * Number of distinct positions actually changed in the world by the build.
     * The plan may contain more positions than were really written.
     */
    static int appliedPositionCount(List<BlockChange> changes) {
        if (changes == null || changes.isEmpty()) return 0;
        java.util.Set<net.minecraft.util.math.BlockPos> positions = new java.util.HashSet<>();
        for (BlockChange change : changes) {
            if (change == null || change.getPos() == null) continue;
            positions.add(change.getPos().toImmutable());
        }
        return positions.size();
    }

    /**
     * Full SHA-256 hex digest of the <b>final confirmed world state</b> after apply.
     * <p>
     * This is <em>not</em> an execution-log digest. For each position, only the last
     * write in the chronologically ordered {@code changes} list is kept (last-write-wins),
     * then positions are sorted for a stable payload. Distinct-position reorderings with
     * the same final per-cell state therefore collide; reordering writes to the <em>same</em>
     * cell changes which state is treated as final and can change the digest.
     */
    static String executionHash(List<BlockChange> changes) {
        if (changes == null || changes.isEmpty()) return "";
        // Final state per cell, assuming {@code changes} is chronological application order.
        java.util.TreeMap<String, String> finalByPosition = new java.util.TreeMap<>();
        for (BlockChange change : changes) {
            if (change == null || change.getPos() == null) continue;
            var pos = change.getPos();
            String key = pos.getX() + "," + pos.getY() + "," + pos.getZ();
            String state = change.getToState() == null
                    ? "" : com.formacraft.common.component.transform.BlockStateStringUtil.fromState(change.getToState());
            finalByPosition.put(key, state);
        }
        if (finalByPosition.isEmpty()) return "";
        StringBuilder payload = new StringBuilder();
        for (var entry : finalByPosition.entrySet()) {
            payload.append(entry.getKey()).append('=').append(entry.getValue()).append(';');
        }
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            return Integer.toHexString(payload.toString().hashCode());
        }
    }

    /**
     * Commit a building Memory record.
     * <p>
     * The commit has explicit phases with durable completion markers on the
     * compensation record. A retry skips phases that already finished; only when
     * <b>all</b> required phases succeeded is the record marked
     * {@link MemoryCommitRecord.Status#COMMITTED}.
     *
     * @return true when the record is fully committed
     */
    private boolean commitMemory(MemoryCommitRecord record) {
        if (record == null || record.memoryUuid == null) return false;
        try {
            net.minecraft.util.Identifier dimension = record.dimension == null
                    ? net.minecraft.util.Identifier.of("minecraft", "overworld")
                    : net.minecraft.util.Identifier.tryParse(record.dimension);
            if (dimension == null) {
                record.lastError = "invalid_dimension";
                return false;
            }

            // ---- Phase 1: building identity + plan-derived data ----
            // If a previous attempt already persisted Memory, do not redo semantic init.
            if (record.phaseMemoryPersisted && !memoryManager.hasMemory(record.memoryUuid)) {
                // Marker without a Memory row is inconsistent — redo phase 1.
                record.phaseMemoryPersisted = false;
            }
            if (!record.phaseMemoryPersisted) {
                var structure = record.toStructure();
                // First commit = no Memory record exists yet. A retry must only fill in
                // missing data and must never reset state a newer edit produced.
                boolean firstCommit = !memoryManager.hasMemory(record.memoryUuid);
                ProjectMemory memory = memoryManager.commitBuilding(
                        record.memoryUuid, structure, record.toSpec(), record.name, dimension);
                if (memory == null) {
                    record.lastError = "commit_failed";
                    return false;
                }
                BuildingPartIndex roofIndex = BuildingPartIndex.fromPlannedBlocks(structure.getBlocks());
                if (firstCommit) {
                    // Initial registration: identity, roof index and revision are ours to set.
                    BuildingEditService.attachRoofIndex(memory, roofIndex);
                    BuildingMemoryKeys.setRevision(memory, 0L);
                } else {
                    // Retry of an unfinished commit: only fill gaps, never reset a newer revision.
                    long existingRevision = BuildingMemoryKeys.revisionOf(memory);
                    if (existingRevision > 0L) {
                        BuildingMemoryKeys.setRevision(memory, existingRevision);
                    } else {
                        BuildingMemoryKeys.setRevision(memory, 0L);
                    }
                    if (BuildingMemoryKeys.roofCellsRaw(memory).isEmpty()
                            && !roofIndex.roof().isEmpty()) {
                        roofIndex.persistTo(memory);
                    }
                }
                // FBD plan / execution summary: initialize once; retries only backfill if missing.
                if (record.fbdSourcePlanJson != null && !record.fbdSourcePlanJson.isBlank()
                        && (firstCommit || !BuildingMemoryKeys.hasFbdSourcePlan(memory))) {
                    BuildingMemoryKeys.storeFbdSourcePlan(memory, record.fbdSourcePlanJson);
                    if (memory.getTags() == null) memory.setTags(new java.util.ArrayList<>());
                    if (!memory.getTags().contains("fbd")) memory.getTags().add("fbd");
                }
                boolean missingExecutionSummary = memory.getMetadata() == null
                        || !memory.getMetadata().containsKey(BuildingMemoryKeys.EXECUTION_HASH);
                if (firstCommit || missingExecutionSummary) {
                    BuildingMemoryKeys.storeExecutionSummary(
                            memory,
                            record.sourceKind == null ? null : record.sourceKind.name(),
                            record.appliedPositions,
                            record.plannedPositions,
                            record.executionHash,
                            record.buildStatus);
                }
                var update = memoryManager.updateMemoryStrict(memory);
                if (!update.ok()) {
                    record.lastError = update.message();
                    return false;
                }
                record.phaseMemoryPersisted = true; // MEMORY_APPLIED
                // Persist the phase marker before attempting phase 2 so a crash mid-way
                // does not redo semantic initialization on the next retry.
                if (memoryManager.saveCommitRecord(record)) {
                    record.phaseMemoryCheckpointed = true; // MEMORY_CHECKPOINTED
                } else {
                    record.phaseMemoryCheckpointed = false;
                    // Safe to continue: phase-1 Memory write already succeeded and retries
                    // are idempotent via hasMemory. Cross-restart marker may be stale.
                    FormacraftMod.LOGGER.error(
                            "Memory commit MEMORY_APPLIED but not MEMORY_CHECKPOINTED for {} "
                                    + "(pending phase marker missing on disk; recovery uses hasMemory)",
                            record.memoryUuid);
                }
            }

            // ---- Phase 2: undo progress of an already-undone building ----
            boolean undoRequired = record.undoRestoredPositions != null
                    && record.undoTotalPositions != null
                    && record.undoTotalPositions > 0;
            if (!record.phaseUndoPersisted) {
                if (undoRequired) {
                    int total = record.undoTotalPositions;
                    int restored = record.undoComplete
                            ? total
                            : Math.min(Math.max(record.undoRestoredPositions, 0), total);
                    boolean complete = restored == total;
                    boolean undoApplied;
                    try {
                        var undoUpdate = new BuildUndoMemoryUpdate(
                                record.transactionId == null ? java.util.UUID.randomUUID()
                                        : java.util.UUID.fromString(record.transactionId),
                                record.memoryUuid, dimension,
                                restored, total, complete);
                        // The undo metadata write is a separate durable step — never ignore it.
                        undoApplied = memoryManager.recordBuildUndo(undoUpdate);
                    } catch (RuntimeException exception) {
                        undoApplied = false;
                        record.lastError = exception.getMessage() == null
                                ? exception.getClass().getSimpleName() : exception.getMessage();
                        FormacraftMod.LOGGER.warn("Failed to apply pending undo state to memory {}: {}",
                                record.memoryUuid, record.lastError);
                    }
                    if (!undoApplied) {
                        if (record.lastError == null) record.lastError = "undo_metadata_persist_failed";
                        // Phase 1 is done; keep pending so the next retry only finishes undo.
                        record.status = MemoryCommitRecord.Status.PENDING;
                        return false;
                    }
                }
                record.phaseUndoPersisted = true;
            }

            record.status = MemoryCommitRecord.Status.COMMITTED;
            record.lastError = null;
            return true;
        } catch (Exception e) {
            record.lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            FormacraftMod.LOGGER.warn("Memory commit retry failed: {}", record.lastError);
            return false;
        }
    }


    private void completeTask(ServerWorld world, BuildTask task) {
        var structure = task.getStructure();
        var changes = task.getAppliedChanges();
        BuildTask.BuildApplyResult applyResult = task.result();
        UUID ownerUuid = structure.getOwner();

        FormacraftMod.LOGGER.info("Build task completed: {} — {}",
                structure.getDescription(), applyResult.summaryZh());

        if (ownerUuid == null) {
            return;
        }

        ServerPlayerEntity ownerEntity = world.getServer().getPlayerManager().getPlayer(ownerUuid);
        String memoryUuid = null;

        // Shared gate with Patch: WorldMutationOutcome / BuildExecutionStatus.recordsHistory().
        var outcome = task.mutationOutcome();
        boolean recordsHistory = outcome != null
                ? outcome.recordsHistory()
                : (task.status() == BuildTask.Status.COMPLETED && applyResult.isCompletePlacement());
        // Incomplete rollback may leave residual cells (PARTIAL or CANCELLED + ROLLBACK_INCOMPLETE).
        boolean residualUndo = !recordsHistory && !changes.isEmpty();
        if (outcome != null) {
            FormacraftMod.LOGGER.info("Build mutation outcome: strategy={} status={} applied={} recovery={}/{}/{}",
                    outcome.strategy(), outcome.status(), outcome.appliedCells(),
                    outcome.recoveredFull(), outcome.recoveredStateOnly(), outcome.recoveryFailed());
        }

        // Memory：完整成功即登记（不依赖玩家仍在线）；Spec 按 owner UUID 取缓存。
        // Memory 身份绑定建造事务，重试只会更新同一条记录，不会重复登记建筑。
        if (recordsHistory && memoryManager != null && !changes.isEmpty()) {
            String dimension = world.getRegistryKey().getValue().toString();
            MemoryCommitRecord record = null;
            try {
                String buildingJson = PlayerSpecRepository.getBuildingJson(ownerUuid);
                BuildingSpec spec = null;
                if (buildingJson != null && !buildingJson.isEmpty()) {
                    try {
                        spec = JsonUtil.fromJson(buildingJson, BuildingSpec.class);
                    } catch (Exception e) {
                        FormacraftMod.LOGGER.warn("Failed to parse BuildingSpec from repository: {}", e.getMessage());
                    }
                }
                String memoryName = structure.getDescription();
                if (spec != null && spec.getNotes() != null && !spec.getNotes().isEmpty()) {
                    memoryName = spec.getNotes();
                }
                memoryUuid = stableMemoryUuid(task.transactionId(), ownerUuid, dimension);
                record = MemoryCommitRecord.create(
                        memoryUuid, task.transactionId(), dimension, memoryName,
                        structure, spec, task.fbdSourcePlanJson());
                record.plannedPositions = structure.size();
                record.markExecuted(
                        appliedPositionCount(changes),
                        executionHash(changes),
                        task.status() == null ? null : task.status().name());
                record.nextAttemptMs = 0L;
            } catch (Exception e) {
                FormacraftMod.LOGGER.warn("Failed to prepare building memory commit (owner={}): {}",
                        ownerUuid, e.getMessage());
                record = null;
            }

            if (record == null) {
                // Could not even prepare the record — fall back to a minimal pending record.
                try {
                    String pendingDimension = world.getRegistryKey().getValue().toString();
                    String pendingUuid = stableMemoryUuid(task.transactionId(), ownerUuid, pendingDimension);
                    record = MemoryCommitRecord.create(
                            pendingUuid, task.transactionId(), pendingDimension,
                            structure.getDescription(), structure, null, task.fbdSourcePlanJson());
                    record.plannedPositions = structure.size();
                    memoryUuid = pendingUuid;
                } catch (Exception ignored) {
                    FormacraftMod.LOGGER.error(
                            "Building placed but Memory commit could not be prepared (owner={})", ownerUuid);
                }
            }

            if (record != null) {
                if (commitMemory(record)) {
                    memoryManager.deleteCommitRecord(memoryUuid);
                    FormacraftMod.LOGGER.info("Saved building to memory: {} ({}) online={}",
                            record.name, memoryUuid, ownerEntity != null);
                } else {
                    FormacraftMod.LOGGER.warn(
                            "Building placed but Memory commit failed for owner={} detail={}",
                            ownerUuid, record.lastError);
                    boolean durable = queuePendingMemoryCommit(record);
                    if (!durable) {
                        // The compensation record is memory-only: a restart would lose it.
                        // This must be a hard error, never a silent "queued" state.
                        FormacraftMod.LOGGER.error(
                                "CRITICAL: building {} was placed but its Memory commit is not durable "
                                        + "(owner={} uuid={} detail={}); the pending commit will be lost on restart",
                                structure.getDescription(), ownerUuid, record.memoryUuid, record.lastError);
                        if (ownerEntity != null) {
                            try {
                                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(
                                        ownerEntity,
                                        new com.formacraft.common.network.FormaCraftNetworking
                                                .ResponseBuildStatusPayload(
                                                "建筑已放置，但记忆保存失败且补偿记录未能持久化，请联系管理员"));
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            }
        }


        // Undo：成功全量，或回滚不完整时的残留变更；完整回滚/失败无历史
        if ((recordsHistory || residualUndo) && !changes.isEmpty()) {
            undoService.pushUndo(ownerUuid, new UndoEntry(
                    world, structure.getOrigin(), structure.getDescription(), changes, memoryUuid));
        }

        if (ownerEntity != null) {
            try {
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(
                        ownerEntity,
                        new com.formacraft.common.network.FormaCraftNetworking.ResponseBuildStatusPayload(
                                applyResult.summaryZh()
                        )
                );
            } catch (Throwable ignored) {}
        } else {
            FormacraftMod.LOGGER.info(
                    "Build completed while owner offline; undo retained for uuid={}", ownerUuid);
        }
    }

    /**
     * 注册 Tick 事件（在你的 Mod 初始化时调用）
     */
    public static void registerTickHandler() {
        if (TICK_HANDLER_REGISTERED) return;
        TICK_HANDLER_REGISTERED = true;
        // This lifecycle runs for integrated and dedicated servers alike.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            MemoryManager manager = new MemoryManager(server);
            manager.initialize();
            getInstance().setMemoryManager(manager);
            com.formacraft.server.edit.BuildingEditTransactionStore.bindServer(server);
            // Recover Memory commits interrupted by a restart (world changed, Memory missing).
            server.execute(() -> getInstance().recoverPendingMemoryCommits());
            // Server-thread recovery: capture live worlds, never client snapshots.
            server.execute(() -> com.formacraft.server.edit.BuildingEditRecoveryCoordinator
                    .recoverOpenTransactions(server, manager));
        });
        // 停服丢弃进行中任务与 Undo；未完成建筑编辑事务由 edit_txns 目录跨重启识别
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            BuildExecutionService service = getInstance();
            service.activeTasks.clear();
            service.undoService.clear();
            service.setMemoryManager(null);
            PatchHistoryManager.clearAll();
            com.formacraft.server.build.PlayerMutationLedger.get().clearAll();
            com.formacraft.server.edit.BuildingEditTransactionStore.clearForTest();
        });
        ServerTickEvents.END_WORLD_TICK.register((ServerWorld world) -> BuildExecutionService.getInstance().onWorldTick(world));
        // Low-frequency reclaim of expired preview tickets / bound plans while idle.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(
                server -> {
                    com.formacraft.server.preview.PreviewTicketStorage.onServerTick();
                    // Expire abandoned build-request leases (missed finish / stuck async).
                    if ((server.getTicks() % 200) == 0) {
                        com.formacraft.server.network.BuildRequestGate.sweepStale(System.currentTimeMillis());
                        // Undo memory sync must not stall when the player stops undoing/redoing.
                        getInstance().getUndoService().retryAllPendingMemory();
                    }
                });
    }
}
