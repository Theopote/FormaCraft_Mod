package com.formacraft.server.memory;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.genome.BuildingGenome;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.Identifier;

import java.util.*;

/**
 * Forma-Cortex 记忆系统：项目记忆
 * 三级记忆模型：空间记忆、语义记忆、基因记忆
 */
public class ProjectMemory {
    // ========== 基础信息 ==========
    private String uuid;
    private String name;  // 玩家命名或 AI 自动生成
    private String createdAt;
    private String lastModified;
    
    // ========== 1. 空间记忆 (Spatial Memory) ==========
    private SpatialBounds bounds;
    
    // ========== 2. 语义记忆 (Semantic Memory) ==========
    private String description;  // AI 生成的描述
    private List<String> tags;  // 标签列表，用于检索
    
    // ========== 3. 基因记忆 (Genetic Memory) ==========
    private BuildingSpec geneData;  // 完整的 BuildingSpec，包含所有生成参数
    private BuildingGenome genome;   // BuildingGenome（如果存在）
    
    // ========== 4. 关联关系 (Relations) ==========
    private Relations relations;
    
    // ========== 5. 元数据 ==========
    private Map<String, Object> metadata;  // 额外信息
    
    /**
     * 空间边界
     */
    public static class SpatialBounds {
        private int[] min;  // [x, y, z]
        private int[] max;  // [x, y, z]
        private String dimension;  // minecraft:overworld, minecraft:the_nether, etc.
        private Map<String, int[]> anchors;  // 关键点：main_entrance, balcony_view, etc.
        
        public SpatialBounds() {
            this.anchors = new HashMap<>();
        }
        
        public SpatialBounds(BlockPos min, BlockPos max, Identifier dimension) {
            this.min = new int[]{min.getX(), min.getY(), min.getZ()};
            this.max = new int[]{max.getX(), max.getY(), max.getZ()};
            this.dimension = dimension != null ? dimension.toString() : "minecraft:overworld";
            this.anchors = new HashMap<>();
        }
        
        public boolean contains(BlockPos pos) {
            if (min == null || max == null) return false;
            return pos.getX() >= min[0] && pos.getX() <= max[0] &&
                   pos.getY() >= min[1] && pos.getY() <= max[1] &&
                   pos.getZ() >= min[2] && pos.getZ() <= max[2];
        }
        
        public BlockPos getMinPos() {
            if (min == null) return null;
            return new BlockPos(min[0], min[1], min[2]);
        }
        
        public BlockPos getMaxPos() {
            if (max == null) return null;
            return new BlockPos(max[0], max[1], max[2]);
        }
        
        // Getters and Setters
        public int[] getMin() { return min; }
        public void setMin(int[] min) { this.min = min; }
        
        public int[] getMax() { return max; }
        public void setMax(int[] max) { this.max = max; }
        
        public String getDimension() { return dimension; }
        public void setDimension(String dimension) { this.dimension = dimension; }
        
        public Map<String, int[]> getAnchors() { return anchors; }
        public void setAnchors(Map<String, int[]> anchors) { this.anchors = anchors != null ? anchors : new HashMap<>(); }
        
        public void addAnchor(String name, BlockPos pos) {
            if (anchors == null) anchors = new HashMap<>();
            anchors.put(name, new int[]{pos.getX(), pos.getY(), pos.getZ()});
        }
        
        public BlockPos getAnchor(String name) {
            if (anchors == null || !anchors.containsKey(name)) return null;
            int[] coords = anchors.get(name);
            return new BlockPos(coords[0], coords[1], coords[2]);
        }
        
        /**
         * 扩展边界以包含新位置
         */
        public void expandBounds(BlockPos minPos, BlockPos maxPos) {
            if (minPos == null || maxPos == null) return;
            
            if (this.min == null || this.max == null) {
                this.min = new int[]{minPos.getX(), minPos.getY(), minPos.getZ()};
                this.max = new int[]{maxPos.getX(), maxPos.getY(), maxPos.getZ()};
                return;
            }
            
            this.min[0] = Math.min(this.min[0], minPos.getX());
            this.min[1] = Math.min(this.min[1], minPos.getY());
            this.min[2] = Math.min(this.min[2], minPos.getZ());
            
            this.max[0] = Math.max(this.max[0], maxPos.getX());
            this.max[1] = Math.max(this.max[1], maxPos.getY());
            this.max[2] = Math.max(this.max[2], maxPos.getZ());
        }
    }
    
    /**
     * 关联关系
     */
    public static class Relations {
        private List<String> connectedTo;  // 连接的路径/建筑 UUID
        private String parentGroup;  // 所属建筑群
        private List<String> children;  // 子建筑 UUID
        
        public Relations() {
            this.connectedTo = new ArrayList<>();
            this.children = new ArrayList<>();
        }
        
        // Getters and Setters
        public List<String> getConnectedTo() { return connectedTo; }
        public void setConnectedTo(List<String> connectedTo) { 
            this.connectedTo = connectedTo != null ? connectedTo : new ArrayList<>(); 
        }
        
        public String getParentGroup() { return parentGroup; }
        public void setParentGroup(String parentGroup) { this.parentGroup = parentGroup; }
        
        public List<String> getChildren() { return children; }
        public void setChildren(List<String> children) { 
            this.children = children != null ? children : new ArrayList<>(); 
        }
    }
    
    // ========== 构造函数 ==========
    public ProjectMemory() {
        this.uuid = UUID.randomUUID().toString();
        this.tags = new ArrayList<>();
        this.relations = new Relations();
        this.metadata = new HashMap<>();
        this.createdAt = java.time.Instant.now().toString();
        this.lastModified = this.createdAt;
    }
    
    public ProjectMemory(String name, BuildingSpec spec) {
        this();
        this.name = name;
        this.geneData = spec;
    }
    
    // ========== Getters and Setters ==========
    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    
    public String getLastModified() { return lastModified; }
    public void setLastModified(String lastModified) { this.lastModified = lastModified; }
    
    public SpatialBounds getBounds() { return bounds; }
    public void setBounds(SpatialBounds bounds) { this.bounds = bounds; }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { 
        this.tags = tags != null ? tags : new ArrayList<>(); 
    }
    
    public void addTag(String tag) {
        if (tags == null) tags = new ArrayList<>();
        if (tag != null && !tags.contains(tag)) {
            tags.add(tag);
        }
    }
    
    public BuildingSpec getGeneData() { return geneData; }
    public void setGeneData(BuildingSpec geneData) { 
        this.geneData = geneData;
        updateLastModified();
    }
    
    public BuildingGenome getGenome() { return genome; }
    public void setGenome(BuildingGenome genome) { 
        this.genome = genome;
        updateLastModified();
    }
    
    public Relations getRelations() { return relations; }
    public void setRelations(Relations relations) { 
        this.relations = relations != null ? relations : new Relations(); 
    }
    
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { 
        this.metadata = metadata != null ? metadata : new HashMap<>(); 
    }
    
    /**
     * 更新最后修改时间
     */
    public void updateLastModified() {
        this.lastModified = java.time.Instant.now().toString();
    }
    
    /**
     * 检查坐标是否在此项目范围内
     */
    public boolean contains(BlockPos pos) {
        return bounds != null && bounds.contains(pos);
    }
    
    /**
     * 扩展边界以包含新区域
     */
    public void expandBounds(BlockPos minPos, BlockPos maxPos) {
        if (bounds == null) {
            // 如果没有边界，创建新的
            bounds = new SpatialBounds();
            if (minPos != null && maxPos != null) {
                bounds.setMin(new int[]{minPos.getX(), minPos.getY(), minPos.getZ()});
                bounds.setMax(new int[]{maxPos.getX(), maxPos.getY(), maxPos.getZ()});
            }
            return;
        }
        
        if (minPos != null && maxPos != null) {
            bounds.expandBounds(minPos, maxPos);
        }
    }
    
    /**
     * 应用基因变更增量（delta）
     * 将 delta 中的变更应用到 metadata 中
     */
    public void applyGeneDelta(java.util.Map<String, Object> delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        
        if (metadata == null) {
            metadata = new HashMap<>();
        }
        
        // 将 delta 合并到 metadata
        for (var entry : delta.entrySet()) {
            metadata.put(entry.getKey(), entry.getValue());
        }
        
        // 更新修改历史（可选：记录变更历史）
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> history = 
            (java.util.List<java.util.Map<String, Object>>) metadata.get("mutation_history");
        
        if (history == null) {
            history = new ArrayList<>();
            metadata.put("mutation_history", history);
        }
        
        // 记录本次变更
        java.util.Map<String, Object> mutationRecord = new HashMap<>();
        mutationRecord.put("timestamp", java.time.Instant.now().toString());
        mutationRecord.put("delta", new HashMap<>(delta));
        history.add(mutationRecord);
        
        // 限制历史记录数量（最多保留 50 条）
        if (history.size() > 50) {
            history.remove(0);
        }
    }
    
    /**
     * 从 GeneratedStructure 和 BuildingSpec 创建记忆
     */
    public static ProjectMemory fromStructure(
            com.formacraft.common.build.GeneratedStructure structure,
            BuildingSpec spec,
            String name,
            Identifier dimension) {
        ProjectMemory memory = new ProjectMemory(name, spec);
        
        // 计算边界
        if (structure != null && !structure.getBlocks().isEmpty()) {
            List<com.formacraft.common.build.PlannedBlock> blocks = structure.getBlocks();
            BlockPos origin = structure.getOrigin();
            
            int minX = origin.getX(), minY = origin.getY(), minZ = origin.getZ();
            int maxX = origin.getX(), maxY = origin.getY(), maxZ = origin.getZ();
            
            for (com.formacraft.common.build.PlannedBlock block : blocks) {
                BlockPos pos = block.getPos();
                minX = Math.min(minX, pos.getX());
                minY = Math.min(minY, pos.getY());
                minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX());
                maxY = Math.max(maxY, pos.getY());
                maxZ = Math.max(maxZ, pos.getZ());
            }
            
            SpatialBounds bounds = new SpatialBounds(
                new BlockPos(minX, minY, minZ),
                new BlockPos(maxX, maxY, maxZ),
                dimension
            );
            bounds.addAnchor("origin", origin);
            memory.setBounds(bounds);
        }
        
        // 从 spec 提取标签
        if (spec != null) {
            List<String> tags = new ArrayList<>();
            if (spec.getType() != null) {
                tags.add(spec.getType().name());
            }
            if (spec.getStyle() != null) {
                tags.add(spec.getStyle().name());
            }
            memory.setTags(tags);
        }
        
        return memory;
    }
}

