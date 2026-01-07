package com.formacraft.common.gen.impl;

import com.formacraft.common.gen.PaletteResolver;

import java.util.HashMap;
import java.util.Map;

/**
 * SimplePaletteResolver（简单调色板解析器）
 * 
 * 最小可用实现：使用硬编码映射
 * 
 * 后续会升级为权重随机（Semantic → Palette 权重随机）
 */
public class SimplePaletteResolver implements PaletteResolver {

    private final Map<String, String> map = new HashMap<>();

    public SimplePaletteResolver() {
        // 默认映射
        map.put("road.surface", "minecraft:gravel");
        map.put("road.edge", "minecraft:stone");
        map.put("wall.base", "minecraft:stone_bricks");
        map.put("wall.top", "minecraft:chiseled_stone_bricks");
    }

    /**
     * 添加映射
     */
    public SimplePaletteResolver put(String semanticKey, String blockId) {
        map.put(semanticKey, blockId);
        return this;
    }

    @Override
    public String pickBlockId(String semanticKey) {
        String block = map.get(semanticKey);
        return block != null ? block : "minecraft:stone"; // fallback
    }
}

