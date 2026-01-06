package com.formacraft.common.palette;

import java.util.HashMap;
import java.util.Map;

/**
 * 语义调色板注册表
 * 
 * 按 paletteId 找到语义映射
 */
public final class SemanticPaletteRegistry {

    private static final Map<String, SemanticPalette> REGISTRY = new HashMap<>();

    private SemanticPaletteRegistry() {}

    /**
     * 注册调色板
     */
    public static void register(SemanticPalette palette) {
        if (palette != null && palette.id() != null) {
            REGISTRY.put(palette.id(), palette);
        }
    }

    /**
     * 获取调色板，如果不存在则返回默认调色板
     */
    public static SemanticPalette getOrDefault(String paletteId) {
        if (paletteId == null || paletteId.isBlank()) {
            return REGISTRY.get("DEFAULT");
        }
        SemanticPalette p = REGISTRY.get(paletteId.trim());
        if (p != null) return p;
        return REGISTRY.get("DEFAULT");
    }

    /**
     * 获取调色板（不返回默认值）
     */
    public static SemanticPalette get(String paletteId) {
        if (paletteId == null || paletteId.isBlank()) {
            return null;
        }
        return REGISTRY.get(paletteId.trim());
    }
}

