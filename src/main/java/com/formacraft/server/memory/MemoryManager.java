package com.formacraft.server.memory;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.FormacraftMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import com.formacraft.common.build.GeneratedStructure;

import java.io.IOException;
import java.util.List;

/**
 * Forma-Cortex 记忆管理器
 * 整合空间索引、语义索引和存储系统
 * 提供统一的记忆管理接口
 */
public class MemoryManager {
    private final MinecraftServer server;
    private final SpatialIndex spatialIndex;
    private final SemanticIndex semanticIndex;
    private boolean initialized = false;
    
    public MemoryManager(MinecraftServer server) {
        this.server = server;
        this.spatialIndex = new SpatialIndex();
        this.semanticIndex = new SemanticIndex();
    }
    
    /**
     * 初始化记忆系统：从磁盘加载所有记忆并构建索引
     */
    public void initialize() {
        if (initialized) {
            return;
        }
        
        try {
            List<ProjectMemory> memories = MemoryStorage.loadAllMemories(server);
            for (ProjectMemory memory : memories) {
                spatialIndex.addMemory(memory);
                semanticIndex.addMemory(memory);
            }
            initialized = true;
            FormacraftMod.LOGGER.info("Memory system initialized with {} memories", memories.size());
        } catch (IOException e) {
            FormacraftMod.LOGGER.error("Failed to initialize memory system", e);
        }
    }
    
    /**
     * 注册新建筑到记忆系统
     * 在建筑生成完成后调用
     */
    public ProjectMemory registerBuilding(
            GeneratedStructure structure,
            BuildingSpec spec,
            String name,
            ServerWorld world) {
        try {
            Identifier dimension = world.getRegistryKey().getValue();
            String uuid = java.util.UUID.randomUUID().toString();
            return commitBuilding(uuid, structure, spec, name, dimension);
        } catch (Exception e) {
            FormacraftMod.LOGGER.error("Failed to register building memory", e);
            return null;
        }
    }
    
    /**
     * Register new building memory with a caller-provided identity.
     * <p>
     * Idempotent: the same {@code memoryUuid} updates the existing record instead of
     * creating a second building. Used by the Memory commit retry path.
     */
    public ProjectMemory commitBuilding(
            String memoryUuid,
            GeneratedStructure structure,
            BuildingSpec spec,
            String name,
            Identifier dimension) {
        if (memoryUuid == null || memoryUuid.isBlank() || dimension == null) {
            return null;
        }
        try {
            ProjectMemory existing = getMemory(memoryUuid);
            ProjectMemory memory = existing != null
                    ? existing
                    : ProjectMemory.fromStructure(structure, spec, name, dimension);
            if (existing == null) {
                memory.setUuid(memoryUuid);
            } else {
                if (name != null && !name.isBlank()) memory.setName(name);
                if (spec != null) memory.setGeneData(spec);
            }
            // The dimension is bound to the record identity — never inherit a foreign one.
            var bounds = memory.getBounds();
            if (bounds != null) bounds.setDimension(dimension.toString());
            persistMemory(memory);
            // Refresh index entries only after the durable write succeeded.
            spatialIndex.removeMemory(memoryUuid);
            semanticIndex.removeMemory(memoryUuid);
            spatialIndex.addMemory(memory);
            semanticIndex.addMemory(memory);
            FormacraftMod.LOGGER.info("Committed building memory: {} ({})", name, memoryUuid);
            return memory;
        } catch (Exception e) {
            FormacraftMod.LOGGER.error("Failed to commit building memory {}", memoryUuid, e);
            return null;
        }
    }

    /**
     * True when a Memory record with this uuid already exists.
     * <p>
     * Checks the in-memory indexes first, then the durable store. Used to tell a
     * <b>first commit</b> (no record yet) from a <b>retry</b> (record already
     * registered), so a retry never overwrites newer state.
     */
    public boolean hasMemory(String uuid) {
        if (uuid == null || uuid.isBlank()) return false;
        if (getMemory(uuid) != null) return true;
        try {
            return MemoryStorage.loadMemory(server, uuid) != null;
        } catch (Exception exception) {
            return false;
        }
    }

    /**
     * 根据坐标查找建筑
     */
    public List<ProjectMemory> findAtPosition(Identifier dimension, BlockPos pos) {
        return spatialIndex.findAt(dimension, pos);
    }
    
    /**
     * 查找最近建筑（在指定范围内）
     */
    public ProjectMemory findNearest(Identifier dimension, BlockPos pos, double maxDistance) {
        return spatialIndex.findNearest(dimension, pos, maxDistance);
    }
    
    /**
     * 根据关键词搜索（AND 逻辑）
     */
    public List<ProjectMemory> searchByKeywords(String... keywords) {
        return semanticIndex.searchAnd(keywords);
    }
    
    /**
     * 根据关键词搜索（OR 逻辑）
     */
    public List<ProjectMemory> searchByKeywordsOr(String... keywords) {
        return semanticIndex.searchOr(keywords);
    }
    
    /**
     * 模糊搜索
     */
    public List<ProjectMemory> searchContains(String query) {
        return semanticIndex.searchContains(query);
    }
    
    /**
     * 根据名称查找（精确匹配）
     */
    public ProjectMemory findByName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        
        List<ProjectMemory> results = semanticIndex.searchContains(name);
        for (ProjectMemory memory : results) {
            if (name.equalsIgnoreCase(memory.getName())) {
                return memory;
            }
        }
        return null;
    }
    
    /**
     * 根据 UUID 获取记忆
     */
    public ProjectMemory getMemory(String uuid) {
        ProjectMemory memory = spatialIndex.getMemory(uuid);
        if (memory == null) {
            memory = semanticIndex.getMemory(uuid);
        }
        return memory;
    }
    
    /**
     * Soft update: logs persistence failures and does not propagate them.
     * Building-edit Confirm must use {@link #updateMemoryStrict} instead.
     */
    public void updateMemory(ProjectMemory memory) {
        updateMemoryStrict(memory);
    }

    /**
     * Strict update for building-edit transactions: in-memory index refresh only after
     * durable persist succeeds. Failure is returned — never reported as commit success.
     */
    public MemoryUpdateResult updateMemoryStrict(ProjectMemory memory) {
        if (memory == null || memory.getUuid() == null) {
            return MemoryUpdateResult.fail("invalid_memory");
        }
        try {
            memory.updateLastModified();
            persistMemory(memory);
            spatialIndex.removeMemory(memory.getUuid());
            semanticIndex.removeMemory(memory.getUuid());
            spatialIndex.addMemory(memory);
            semanticIndex.addMemory(memory);
            FormacraftMod.LOGGER.info("Updated memory: {}", memory.getUuid());
            return MemoryUpdateResult.succeeded();
        } catch (Exception e) {
            FormacraftMod.LOGGER.error("Failed to update memory", e);
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return MemoryUpdateResult.fail(detail);
        }
    }
    
    /**
     * 应用基因变更（Mutation）到记忆系统
     * 
     * 这是 Memory → Patch → Memory 闭环的核心方法
     * 
     * @param mutation 基因变更记录
     * @param newBounds 新的边界（如果扩建）
     * @return 更新后的记忆（如果成功）
     */
    public ProjectMemory applyMutation(Identifier dimension, GeneMutation mutation, BlockPos minPos, BlockPos maxPos) {
        if (dimension == null || mutation == null) {
            return null;
        }
        
        try {
            if (mutation.buildingId() == null) {
                // 新建筑：创建新记忆
                ProjectMemory created = createMemoryFromMutation(dimension, mutation, minPos, maxPos);
                if (created != null) {
                    FormacraftMod.LOGGER.info("Created new memory from mutation: {}", created.getUuid());
                }
                return created;
            }
            
            // 修改已有建筑
            ProjectMemory memory = getMemory(mutation.buildingId().toString());
            if (!SpatialIndex.inDimension(memory, dimension)) {
                FormacraftMod.LOGGER.warn("Cannot apply mutation: building {} not found", mutation.buildingId());
                return null;
            }
            
            // 1. 更新边界（如果扩建）
            if (minPos != null && maxPos != null) {
                memory.expandBounds(minPos, maxPos);
            }
            
            // 2. 应用基因变更
            memory.applyGeneDelta(mutation.geneDelta());
            
            // 3. 更新修改时间
            memory.updateLastModified();
            
            // 4. 保存并更新索引
            updateMemory(memory);
            
            FormacraftMod.LOGGER.info("Applied mutation to memory: {} - {}", 
                memory.getUuid(), mutation.reason());
            
            return memory;
        } catch (Exception e) {
            FormacraftMod.LOGGER.error("Failed to apply mutation", e);
            return null;
        }
    }
    
    /**
     * 从 Mutation 创建新记忆（用于新建建筑）
     */
    private ProjectMemory createMemoryFromMutation(Identifier dimension, GeneMutation mutation, BlockPos minPos, BlockPos maxPos) {
        ProjectMemory memory = new ProjectMemory();
        memory.setName("Building from Patch");
        memory.setDescription(mutation.reason());
        
        // 设置边界
        if (minPos != null && maxPos != null) {
            ProjectMemory.SpatialBounds bounds = new ProjectMemory.SpatialBounds();
            bounds.setMin(new int[]{minPos.getX(), minPos.getY(), minPos.getZ()});
            bounds.setMax(new int[]{maxPos.getX(), maxPos.getY(), maxPos.getZ()});
            bounds.setDimension(dimension.toString());
            memory.setBounds(bounds);
        }
        
        // 应用基因变更
        memory.applyGeneDelta(mutation.geneDelta());
        
        // 添加标签
        for (SemanticPart part : mutation.affectedParts()) {
            memory.addTag(part.name());
        }
        
        // 保存
        try {
            persistMemory(memory);
            spatialIndex.addMemory(memory);
            semanticIndex.addMemory(memory);
            return memory;
        } catch (Exception e) {
            FormacraftMod.LOGGER.error("Failed to create memory from mutation", e);
            return null;
        }
    }
    
    protected void persistMemory(ProjectMemory memory) throws IOException {
        MemoryStorage.saveMemory(server, memory);
    }

    /** Update only the associated build record; never infer demolition from restored block counts. */
    public boolean recordBuildUndo(BuildUndoMemoryUpdate update) {
        if (update == null) return false;
        ProjectMemory memory = getMemory(update.memoryUuid());
        if (!update.targets(memory)) return false;
        var previousMetadata = memory.getMetadata();
        String previousModified = memory.getLastModified();
        try {
            memory.setMetadata(update.metadata(memory));
            memory.updateLastModified();
            persistMemory(memory);
            // Bounds, UUID and semantic tags are unchanged; cached indexes already refer to this record.
            return true;
        } catch (Exception exception) {
            memory.setMetadata(previousMetadata);
            memory.setLastModified(previousModified);
            FormacraftMod.LOGGER.warn("Failed to persist build undo memory {}", update.memoryUuid(), exception);
            return false;
        }
    }

    /**
     * 删除记忆
     */
    public boolean deleteMemory(String uuid) {
        try {
            // Durable delete first: a failed file delete must not drop the index entries,
            // otherwise the record becomes invisible while still on disk.
            boolean deleted = MemoryStorage.deleteMemory(server, uuid);
            if (!deleted) {
                FormacraftMod.LOGGER.warn("Memory delete failed for {}; index entries kept", uuid);
                return false;
            }
            spatialIndex.removeMemory(uuid);
            semanticIndex.removeMemory(uuid);
            FormacraftMod.LOGGER.info("Deleted memory: {}", uuid);
            return true;
        } catch (Exception e) {
            FormacraftMod.LOGGER.error("Failed to delete memory", e);
            return false;
        }
    }

    /**
     * Persist a building Memory commit record (survives restarts).
     *
     * @return {@code true} only when the record is durably on disk. A {@code false}
     *         result means the record exists in memory only and would be lost on
     *         restart — the caller must not treat it as reliably queued.
     */
    public boolean saveCommitRecord(MemoryCommitRecord record) {
        if (record == null || record.memoryUuid == null) return false;
        try {
            MemoryCommitStore.save(server, record);
            return true;
        } catch (Exception exception) {
            FormacraftMod.LOGGER.error("Failed to persist memory commit record {}: {}",
                    record.memoryUuid, exception.getMessage());
            return false;
        }
    }

    /** Delete a building Memory commit record (commit finished or cancelled). */
    public void deleteCommitRecord(String memoryUuid) {
        if (memoryUuid == null) return;
        try {
            MemoryCommitStore.delete(server, memoryUuid);
        } catch (RuntimeException exception) {
            FormacraftMod.LOGGER.warn("Failed to delete memory commit record {}: {}",
                    memoryUuid, exception.getMessage());
        }
    }

    /** Load pending building Memory commit records (restart recovery). */
    public List<MemoryCommitRecord> loadCommitRecords() {
        try {
            return MemoryCommitStore.loadAll(server);
        } catch (RuntimeException exception) {
            FormacraftMod.LOGGER.warn("Failed to load memory commit records: {}", exception.getMessage());
            return List.of();
        }
    }
    
    /**
     * 列出所有记忆
     */
    public List<String> listAllMemoryUuids() {
        try {
            return MemoryStorage.listMemories(server);
        } catch (IOException e) {
            FormacraftMod.LOGGER.error("Failed to list memories", e);
            return List.of();
        }
    }
    
    /**
     * 重新加载所有记忆（用于调试或修复）
     */
    public void reload() {
        spatialIndex.clear();
        semanticIndex.clear();
        initialized = false;
        initialize();
    }
    
    /**
     * 获取统计信息
     */
    public MemoryStats getStats() {
        return new MemoryStats(
            spatialIndex.size(),
            semanticIndex.size()
        );
    }
    
    /**
     * 统计信息
     */
    public static class MemoryStats {
        private final int spatialCount;
        private final int semanticCount;
        
        public MemoryStats(int spatialCount, int semanticCount) {
            this.spatialCount = spatialCount;
            this.semanticCount = semanticCount;
        }
        
        public int getSpatialCount() { return spatialCount; }
        public int getSemanticCount() { return semanticCount; }
    }
}

