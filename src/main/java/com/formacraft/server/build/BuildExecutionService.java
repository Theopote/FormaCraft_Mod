package com.formacraft.server.build;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.city.CitySpec;
import com.formacraft.server.city.CityBuilder;
import com.formacraft.server.generation.structure.StructureGenerator;
import com.formacraft.server.generation.structure.StructureGeneratorFactory;
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
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * 建造执行服务
 * 核心执行服务，维护全局的 BuildTask 列表
 * 每个世界 Tick 时执行一部分方块
 * 任务完成后自动将结果交给 UndoService
 */
public class BuildExecutionService {
    private static final int BLOCKS_PER_TICK = 200; // 可做成配置

    private final List<BuildTask> activeTasks = new ArrayList<>();
    private final UndoService undoService = new UndoService();
    private MemoryManager memoryManager;

    private static BuildExecutionService INSTANCE;
    private static boolean TICK_HANDLER_REGISTERED = false;

    private BuildExecutionService() {}
    
    /**
     * 设置记忆管理器（由 ServerInitializer 调用）
     */
    public void setMemoryManager(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
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
        if (spec.getMaterials() != null && spec.getMaterials().getFoundation() != null) {
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
     */
    public void enqueueBuild(ServerWorld world, GeneratedStructure structure) {
        activeTasks.add(new BuildTask(structure, world));
        FormacraftMod.LOGGER.info("Queued build task: {} blocks at {} (owner: {})", 
                structure.size(), structure.getOrigin(), structure.getOwner());
    }

    /**
     * 每个世界 tick 调用，处理所有 BuildTask
     */
    public void onWorldTick(ServerWorld world) {
        Iterator<BuildTask> it = activeTasks.iterator();
        while (it.hasNext()) {
            BuildTask task = it.next();

            if (task.getWorld() != world) {
                continue;
            }

            int executed = task.tick(BLOCKS_PER_TICK);
            // 这里你可以顺便做进度广播、调试输出等
            if (executed > 0) {
                FormacraftMod.LOGGER.debug("Executed {} blocks in tick", executed);
            }

            if (task.isFinished()) {
                it.remove();

                // 将执行完的 BlockChange 记录为 UndoEntry
                var structure = task.getStructure();
                var changes = task.getAppliedChanges();
                if (structure.getOwner() != null) {
                    ServerPlayerEntity owner = world.getServer().getPlayerManager().getPlayer(structure.getOwner());
                    if (owner != null) {
                        // 只有实际有改动才入 Undo 栈
                        if (!changes.isEmpty()) {
                            UndoEntry entry = new UndoEntry(
                                    world,
                                    structure.getOrigin(),
                                    structure.getDescription(),
                                    changes
                            );
                            undoService.pushUndo(owner, entry);
                        }

                        FormacraftMod.LOGGER.info("Build task completed: {} ({} blocks, owner: {})",
                                structure.getDescription(), changes.size(), owner.getName().getString());

                        // 保存到记忆系统
                        if (memoryManager != null && !changes.isEmpty()) {
                            try {
                                // 尝试从 PlayerSpecRepository 获取 BuildingSpec
                                String buildingJson = PlayerSpecRepository.getBuildingJson(owner);
                                BuildingSpec spec = null;
                                if (buildingJson != null && !buildingJson.isEmpty()) {
                                    try {
                                        spec = JsonUtil.fromJson(buildingJson, BuildingSpec.class);
                                    } catch (Exception e) {
                                        FormacraftMod.LOGGER.warn("Failed to parse BuildingSpec from repository: {}", e.getMessage());
                                    }
                                }
                                
                                // 生成记忆名称（如果没有从 spec 获取）
                                String memoryName = structure.getDescription();
                                if (spec != null && spec.getNotes() != null && !spec.getNotes().isEmpty()) {
                                    memoryName = spec.getNotes();
                                }
                                
                                // 注册到记忆系统
                                ProjectMemory memory = memoryManager.registerBuilding(
                                        structure,
                                        spec,
                                        memoryName,
                                        world
                                );
                                
                                if (memory != null) {
                                    FormacraftMod.LOGGER.info("Saved building to memory: {} ({})", memory.getName(), memory.getUuid());
                                }
                            } catch (Exception e) {
                                FormacraftMod.LOGGER.warn("Failed to save building to memory: {}", e.getMessage());
                            }
                        }

                        // 给客户端一个明确的"已完成"提示（即使 0 方块也提示，便于排查）
                        try {
                            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(
                                    owner,
                                    new com.formacraft.common.network.FormaCraftNetworking.ResponseBuildStatusPayload(
                                            "建造完成：" + structure.getDescription() + "（" + changes.size() + " 方块）"
                                    )
                            );
                        } catch (Throwable ignored) {}
                    }
                } else {
                    FormacraftMod.LOGGER.info("Build task completed: {} ({} blocks, no owner)",
                            structure.getDescription(), changes.size());
                }
            }
        }
    }

    /**
     * 注册 Tick 事件（在你的 Mod 初始化时调用）
     */
    public static void registerTickHandler() {
        if (TICK_HANDLER_REGISTERED) return;
        TICK_HANDLER_REGISTERED = true;
        ServerTickEvents.END_WORLD_TICK.register((ServerWorld world) -> BuildExecutionService.getInstance().onWorldTick(world));
    }
}
