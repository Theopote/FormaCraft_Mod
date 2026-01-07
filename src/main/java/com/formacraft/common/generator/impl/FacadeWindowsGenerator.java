package com.formacraft.common.generator.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generator.ComponentGenerator;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.palette.component.Palette;
import com.formacraft.common.palette.component.PaletteLibrary;
import com.formacraft.common.palette.dynamic.DynamicPaletteResolver;
import com.formacraft.common.semantic.SemanticPart;

import java.util.ArrayList;
import java.util.List;

/**
 * FacadeWindowsGenerator（立面窗户生成器）
 * 
 * 生成建筑立面的窗户/橱窗/窗带
 * 支持不同风格的窗户（格子窗、大窗、落地窗等）
 */
public class FacadeWindowsGenerator implements ComponentGenerator {

    @Override
    public List<BlockPatch> generate(SemanticComponent semantic) {
        List<BlockPatch> out = new ArrayList<>();

        Component c = semantic.source();
        if (c == null || c.dimensions() == null || c.relativePosition() == null) {
            return out;
        }

        Dimensions d = c.dimensions();
        Vec3i rp = c.relativePosition();

        int width = Math.max(1, d.width());
        int depth = Math.max(1, d.depth());
        int height = Math.max(1, d.height());

        String styleProfile = getStyleProfile(semantic);
        Palette palette = PaletteLibrary.forStyle(styleProfile);

        // 检查 features 以确定窗户类型
        boolean isLattice = hasFeature(c, "lattice", "lattice_pattern");
        boolean isLarge = hasFeature(c, "large", "big", "wide");
        // boolean isFloorToCeiling = hasFeature(c, "floor_to_ceiling", "full_height"); // 保留用于未来扩展

        // 生成窗户（通常只在立面，depth 通常为 1）
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    // 窗户通常只在立面（z=0 或 z=depth-1）
                    boolean isFacade = (z == 0 || z == depth - 1);
                    
                    if (isFacade) {
                        // 根据位置和特征决定窗户类型
                        SemanticPart part = determineWindowPart(y, height, isLattice, isLarge);
                        String block = getBlockForWindow(semantic, palette, part, isLattice);
                        
                        out.add(new BlockPatch(
                                BlockPatch.PLACE,
                                rp.x() + x,
                                rp.y() + y,
                                rp.z() + z,
                                block
                        ));
                    }
                }
            }
        }

        return out;
    }

    /**
     * 确定窗户的语义部位
     */
    private SemanticPart determineWindowPart(int y, int height, boolean isLattice, boolean isLarge) {
        // 顶部和底部使用窗框
        if (y == 0 || y >= height - 1) {
            return SemanticPart.WINDOW; // 窗框
        }
        
        // 中间部分使用窗户
        if (isLattice) {
            return SemanticPart.WINDOW; // 格子窗
        } else if (isLarge) {
            return SemanticPart.WINDOW; // 大窗
        } else {
            return SemanticPart.WINDOW; // 普通窗
        }
    }

    /**
     * 检查是否有特定特征
     */
    private boolean hasFeature(Component c, String... keywords) {
        if (c.features() == null) return false;
        for (String feature : c.features()) {
            if (feature == null) continue;
            String lower = feature.toLowerCase();
            for (String keyword : keywords) {
                if (lower.contains(keyword.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    private String getStyleProfile(SemanticComponent semantic) {
        // 1. 优先使用 SemanticComponent 中的 styleProfile
        if (semantic != null && semantic.styleProfile() != null && !semantic.styleProfile().isBlank()) {
            String profile = semantic.styleProfile().trim();
            // 映射 LLM 返回的风格名称到系统支持的风格
            String upper = profile.toUpperCase();
            if (upper.contains("GOTHIC")) {
                return "MEDIEVAL_CLASSIC"; // 哥特式使用中世纪风格
            }
            if (upper.contains("CHINESE") || upper.contains("HUI")) {
                return "HUI_STYLE_VILLA";
            }
            return profile;
        }

        // 2. 尝试从 Component 的 features 推断风格
        Component c = semantic != null ? semantic.source() : null;
        if (c != null && c.features() != null) {
            for (String feature : c.features()) {
                if (feature == null) continue;
                String lower = feature.toLowerCase();
                if (lower.contains("gothic") || lower.contains("stained_glass") || lower.contains("pointed")) {
                    return "MEDIEVAL_CLASSIC";
                }
                if (lower.contains("chinese") || lower.contains("中式") || lower.contains("lattice")) {
                    return "HUI_STYLE_VILLA";
                }
            }
        }

        // 3. 默认
        return "MEDIEVAL_CLASSIC";
    }
    
    /**
     * 获取窗户方块（优先使用动态解析和 features，回退到传统 Palette）
     */
    private String getBlockForWindow(SemanticComponent semantic, Palette palette, SemanticPart part, boolean isLattice) {
        // 1. 优先使用动态解析（如果 LlmPlan 有 style_attributes）
        if (semantic.styleAttributes() != null) {
            String block = DynamicPaletteResolver.resolve(part, semantic.styleAttributes());
            if (block != null && !block.isEmpty()) {
                return block;
            }
        }
        
        // 2. 尝试从 features 中提取材质信息
        Component c = semantic.source();
        if (c != null && c.features() != null) {
            String block = extractBlockFromFeatures(c.features());
            if (block != null && !block.isEmpty()) {
                return block;
            }
        }
        
        // 3. 回退到传统 Palette
        if (palette != null) {
            String block = palette.pick(part);
            if (block != null && !block.isEmpty()) {
                return block;
            }
        }
        
        // 4. 默认方块
        return isLattice ? "minecraft:iron_bars" : "minecraft:glass";
    }
    
    /**
     * 从 features 中提取窗户材质
     */
    private String extractBlockFromFeatures(List<String> features) {
        if (features == null || features.isEmpty()) {
            return null;
        }
        
        for (String feature : features) {
            if (feature == null || feature.isBlank()) {
                continue;
            }
            
            String lower = feature.toLowerCase().trim();
            
            if (lower.contains("glass_curtain") || lower.contains("curtain_wall") || 
                lower.contains("glass_pane") || (lower.contains("glass") && lower.contains("curtain"))) {
                return "minecraft:glass_pane";
            }
            if (lower.contains("glass") && !lower.contains("curtain")) {
                return "minecraft:glass";
            }
            if (lower.contains("lattice") || lower.contains("iron_bars")) {
                return "minecraft:iron_bars";
            }
        }
        
        return null;
    }
}

