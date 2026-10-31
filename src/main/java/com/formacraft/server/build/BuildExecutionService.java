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
        if (world == null || activeTasks.isEmpty()) return;

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
        if (recordsHistory && memoryManager != null && !changes.isEmpty()) {
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
                ProjectMemory memory = memoryManager.registerBuilding(structure, spec, memoryName, world);
                if (memory != null) {
                    memoryUuid = memory.getUuid();
                    BuildingPartIndex roofIndex = BuildingPartIndex.fromPlannedBlocks(structure.getBlocks());
                    BuildingEditService.attachRoofIndex(memory, roofIndex);
                    BuildingMemoryKeys.setRevision(memory, 0L);
                    String fbdJson = task.fbdSourcePlanJson();
                    if (fbdJson != null && !fbdJson.isBlank()) {
                        BuildingMemoryKeys.storeFbdSourcePlan(memory, fbdJson);
                        if (memory.getTags() == null) memory.setTags(new java.util.ArrayList<>());
                        if (!memory.getTags().contains("fbd")) memory.getTags().add("fbd");
                    }
                    var memUpdate = memoryManager.updateMemoryStrict(memory);
                    if (!memUpdate.ok()) {
                        FormacraftMod.LOGGER.warn(
                                "Building placed but Memory persist failed for owner={} detail={}",
                                ownerUuid, memUpdate.message());
                    } else {
                        FormacraftMod.LOGGER.info("Saved building to memory: {} ({}) roofCells={} fbd={} online={}",
                                memory.getName(), memory.getUuid(), roofIndex.roof().cells().size(),
                                BuildingMemoryKeys.hasFbdSourcePlan(memory), ownerEntity != null);
                    }
                } else {
                    FormacraftMod.LOGGER.warn(
                            "Building placed but Memory registerBuilding returned null (owner={})", ownerUuid);
                }
            } catch (Exception e) {
                FormacraftMod.LOGGER.warn("Failed to save building to memory (owner={}): {}",
                        ownerUuid, e.getMessage());
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
                    }
                });
    }
}
