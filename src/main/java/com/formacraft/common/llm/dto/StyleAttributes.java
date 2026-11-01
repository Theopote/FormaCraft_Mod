package com.formacraft.common.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * StyleAttributes（风格属性）
 *
 * AI 分析用户描述后提取的风格特征
 * 用于动态材质选择，不再依赖硬编码预设
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StyleAttributes(
        /** 墙体颜色（white, gray, red, brown, etc.） */
        @JsonProperty("wall_color") String wallColor,

        /** 墙体材质（stone, brick, wood, concrete, terracotta, etc.） */
        @JsonProperty("wall_material") String wallMaterial,

        /** 屋顶颜色（black, red, gray, brown, etc.） */
        @JsonProperty("roof_color") String roofColor,

        /** 屋顶材质（tile, shingle, slate, metal, etc.） */
        @JsonProperty("roof_material") String roofMaterial,

        /** 装饰/强调材质（dark_oak, spruce, stone, etc.） */
        @JsonProperty("accent_material") String accentMaterial,

        /** 地面材质（stone_bricks, wood_planks, cobblestone, etc.） */
        @JsonProperty("floor_material") String floorMaterial,

        /** 装饰元素（wood_carvings, lattice_windows, columns, etc.） */
        @JsonProperty("decorative_elements") List<String> decorativeElements,

        /** 其他自定义属性 */
        @JsonProperty("custom_attributes") Map<String, String> customAttributes,

        /** Orchestrator shorthand palettes (wall/roof/floor keys). */
        @JsonProperty("palette") Map<String, String> palette,
        @JsonProperty("materials") Map<String, String> materials,
        @JsonProperty("block_palette") Map<String, String> blockPalette,
        @JsonProperty("primary_material") String primaryMaterial,
        @JsonProperty("roof_form") String roofForm
) {
    /** Backward-compatible 8-arg ctor used across tests and bridges. */
    public StyleAttributes(
            String wallColor,
            String wallMaterial,
            String roofColor,
            String roofMaterial,
            String accentMaterial,
            String floorMaterial,
            List<String> decorativeElements,
            Map<String, String> customAttributes
    ) {
        this(wallColor, wallMaterial, roofColor, roofMaterial, accentMaterial, floorMaterial,
                decorativeElements, customAttributes, null, null, null, null, null);
    }

    public boolean hasDecorativeElement(String element) {
        if (decorativeElements == null) return false;
        for (String e : decorativeElements) {
            if (e != null && e.equalsIgnoreCase(element)) {
                return true;
            }
        }
        return false;
    }

    /** Resolve wall / roof / floor ids from typed fields or orchestrator palette maps. */
    public String resolvePaletteBlock(String... roles) {
        for (String role : roles) {
            if (role == null) continue;
            String fromMaps = firstFromMaps(role);
            if (fromMaps != null) return fromMaps;
        }
        return null;
    }

    private String firstFromMaps(String role) {
        String key = role.trim().toLowerCase();
        for (Map<String, String> map : new Map[]{palette, materials, blockPalette, customAttributes}) {
            if (map == null) continue;
            for (Map.Entry<String, String> e : map.entrySet()) {
                if (e.getKey() == null || e.getValue() == null || e.getValue().isBlank()) continue;
                if (e.getKey().equalsIgnoreCase(key) || e.getKey().toLowerCase().contains(key)) {
                    return e.getValue().trim();
                }
            }
        }
        if ("wall".equals(key)) {
            if (primaryMaterial != null && !primaryMaterial.isBlank()) return primaryMaterial.trim();
            if (wallMaterial != null && !wallMaterial.isBlank()) return wallMaterial.trim();
        }
        if ("roof".equals(key) && roofMaterial != null && !roofMaterial.isBlank()) {
            return roofMaterial.trim();
        }
        if ("floor".equals(key) && floorMaterial != null && !floorMaterial.isBlank()) {
            return floorMaterial.trim();
        }
        return null;
    }
}
