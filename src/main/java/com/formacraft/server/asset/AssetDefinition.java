package com.formacraft.server.asset;

import java.util.List;
import java.util.Map;

/**
 * AssetDefinition: 预制件定义
 * 
 * 存储从 JSON 加载的预制件信息
 */
public final class AssetDefinition {
    public final String assetId;
    public final String version;
    public final List<String> tags;
    public final String category;  // CONNECTOR, FILLER, TERMINATOR, FIXTURE
    public final int[] size;  // [width, height, depth]
    public final String anchor;  // BOTTOM_CENTER, TOP_CENTER, etc.
    public final boolean isFlexible;
    public final String generatorClass;  // null for static assets
    public final Map<String, String> materialVariables;  // $primary_log -> PRIMARY_STRUCTURE
    public final List<AssetBlock> blocks;
    
    public AssetDefinition(
        String assetId,
        String version,
        List<String> tags,
        String category,
        int[] size,
        String anchor,
        boolean isFlexible,
        String generatorClass,
        Map<String, String> materialVariables,
        List<AssetBlock> blocks
    ) {
        this.assetId = assetId;
        this.version = version;
        this.tags = tags != null ? tags : List.of();
        this.category = category;
        this.size = size;
        this.anchor = anchor;
        this.isFlexible = isFlexible;
        this.generatorClass = generatorClass;
        this.materialVariables = materialVariables != null ? materialVariables : Map.of();
        this.blocks = blocks != null ? blocks : List.of();
    }
    
    /**
     * AssetBlock: 预制件中的单个方块定义
     */
    public static final class AssetBlock {
        public final int[] pos;  // [x, y, z] 相对坐标
        public final String type;  // 方块类型（可能是变量如 $primary_log）
        public final Map<String, Object> state;  // 方块状态属性
        
        public AssetBlock(int[] pos, String type, Map<String, Object> state) {
            this.pos = pos;
            this.type = type;
            this.state = state != null ? state : Map.of();
        }
    }
}

