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
        @JsonProperty("custom_attributes") Map<String, String> customAttributes
) {
    /**
     * 检查是否有装饰元素
     */
    public boolean hasDecorativeElement(String element) {
        if (decorativeElements == null) return false;
        for (String e : decorativeElements) {
            if (e != null && e.equalsIgnoreCase(element)) {
                return true;
            }
        }
        return false;
    }
}

