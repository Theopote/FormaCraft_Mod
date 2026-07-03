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
            // 创建记忆
            Identifier dimension = world.getRegistryKey().getValue();
            ProjectMemory memory = ProjectMemory.fromStructure(structure, spec, name, dimension);
            
            // 保存到磁盘
            MemoryStorage.saveMemory(server, memory);
            
            // 添加到索引
            spatialIndex.addMemory(memory);
            semanticIndex.addMemory(memory);
            
            FormacraftMod.LOGGER.info("Registered building memory: {} ({})", name, memory.getUuid());
            return memory;
        } catch (Exception e) {
            FormacraftMod.LOGGER.error("Failed to register building memory", e);
            return null;
        }
    }
    
    /**
     * 根据坐标查找建筑
     */
    public List<ProjectMemory> findAtPosition(BlockPos pos) {
        return spatialIndex.findAt(pos);
    }
    
    /**
     * 查找最近建筑（在指定范围内）
     */
    public ProjectMemory findNearest(BlockPos pos, double maxDistance) {
        return spatialIndex.findNearest(pos, maxDistance);
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
     * 更新记忆（修改后保存）
     */
    public void updateMemory(ProjectMemory memory) {
        if (memory == null || memory.getUuid() == null) {
            return;
        }
        
        try {
            memory.updateLastModified();
            MemoryStorage.saveMemory(server, memory);
            
            // 更新索引
            spatialIndex.removeMemory(memory.getUuid());
            semanticIndex.removeMemory(memory.getUuid());
            spatialIndex.addMemory(memory);
            semanticIndex.addMemory(memory);
            
            FormacraftMod.LOGGER.info("Updated memory: {}", memory.getUuid());
        } catch (Exception e) {
            FormacraftMod.LOGGER.error("Failed to update memory", e);
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
    public ProjectMemory applyMutation(GeneMutation mutation, BlockPos minPos, BlockPos maxPos) {
        if (mutation == null) {
            return null;
        }
        
        try {
            if (mutation.buildingId() == null) {
                // 新建筑：创建新记忆
                ProjectMemory created = createMemoryFromMutation(mutation, minPos, maxPos);
                if (created != null) {
                    FormacraftMod.LOGGER.info("Created new memory from mutation: {}", created.getUuid());
                }
                return created;
            }
            
            // 修改已有建筑
            ProjectMemory memory = getMemory(mutation.buildingId().toString());
            if (memory == null) {
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
    private ProjectMemory createMemoryFromMutation(GeneMutation mutation, BlockPos minPos, BlockPos maxPos) {
        ProjectMemory memory = new ProjectMemory();
        memory.setName("Building from Patch");
        memory.setDescription(mutation.reason());
        
        // 设置边界
        if (minPos != null && maxPos != null) {
            ProjectMemory.SpatialBounds bounds = new ProjectMemory.SpatialBounds();
            bounds.setMin(new int[]{minPos.getX(), minPos.getY(), minPos.getZ()});
            bounds.setMax(new int[]{maxPos.getX(), maxPos.getY(), maxPos.getZ()});
            bounds.setDimension("minecraft:overworld"); // 默认维度
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
            MemoryStorage.saveMemory(server, memory);
            spatialIndex.addMemory(memory);
            semanticIndex.addMemory(memory);
            return memory;
        } catch (Exception e) {
            FormacraftMod.LOGGER.error("Failed to create memory from mutation", e);
            return null;
        }
    }
    
    /**
     * 删除记忆
     */
    public boolean deleteMemory(String uuid) {
        try {
            // 从索引中移除
            spatialIndex.removeMemory(uuid);
            semanticIndex.removeMemory(uuid);
            
            // 从磁盘删除
            boolean deleted = MemoryStorage.deleteMemory(server, uuid);
            
            if (deleted) {
                FormacraftMod.LOGGER.info("Deleted memory: {}", uuid);
            }
            return deleted;
        } catch (Exception e) {
            FormacraftMod.LOGGER.error("Failed to delete memory", e);
            return false;
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

