package com.formacraft.common.palette;

import com.formacraft.common.semantic.SemanticPart;
import com.formacraft.common.semantic.SemanticRole;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 语义调色板
 * 
 * 一个 Palette = "风格 + 一组 SemanticPart 映射表"
 * 同时支持 role/tag 做细分
 */
public class SemanticPalette {

    private final String id;

    // 最简单：part -> blocks
    private final Map<SemanticPart, List<WeightedBlock>> partMap = new EnumMap<>(SemanticPart.class);

    // 可选：更细 role 覆盖（比如 PATH_EDGE 用更硬的块）
    private final Map<SemanticPart, Map<SemanticRole, List<WeightedBlock>>> roleOverrides = new EnumMap<>(SemanticPart.class);

    public SemanticPalette(String id) {
        this.id = id;
    }

    public String id() { 
        return id; 
    }

    /**
     * 添加 part 到 blocks 的映射
     */
    public SemanticPalette put(SemanticPart part, List<WeightedBlock> blocks) {
        partMap.put(part, blocks);
        return this;
    }

    /**
     * 添加 part + role 到 blocks 的映射（覆盖默认 part 映射）
     */
    public SemanticPalette put(SemanticPart part, SemanticRole role, List<WeightedBlock> blocks) {
        roleOverrides.computeIfAbsent(part, p -> new EnumMap<>(SemanticRole.class)).put(role, blocks);
        return this;
    }

    /**
     * 获取指定 part 和 role 的方块列表
     * 优先返回 role 覆盖，如果没有则返回 part 映射
     */
    public List<WeightedBlock> get(SemanticPart part, SemanticRole role) {
        var rm = roleOverrides.get(part);
        if (rm != null && role != null && rm.containsKey(role)) {
            return rm.get(role);
        }
        return partMap.get(part);
    }
}

