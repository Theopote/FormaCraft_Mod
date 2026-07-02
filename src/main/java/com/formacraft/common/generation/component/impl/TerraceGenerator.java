package com.formacraft.common.generation.component.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.ComponentGenerator;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.palette.component.Palette;
import com.formacraft.common.palette.component.PaletteLibrary;
import com.formacraft.common.semantic.SemanticPart;

import java.util.ArrayList;
import java.util.List;

/**
 * TerraceGenerator（露台生成器）
 * 
 * 生成露台/平台
 * 包括平台地板、边缘护栏（可选）
 * 
 * 与 Balcony 的区别：
 * - Balcony：突出于建筑外部的挑台，通常有栏杆
 * - Terrace：建筑顶部的平台，通常更大，可能没有栏杆
 */
public class TerraceGenerator implements ComponentGenerator {

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
        int height = Math.max(0, d.height()); // 露台可以是平面（height=0）

        String styleProfile = getStyleProfile(semantic);
        Palette palette = PaletteLibrary.forStyle(styleProfile);

        // 检查是否有栏杆特征
        boolean hasRailing = hasFeature(c, "railing", "fence", "barrier", "guard");
        
        // 生成露台平台（地板）
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                SemanticPart part = SemanticPart.FLOOR;
                String block = palette.pick(part);
                if (block == null || block.isEmpty()) {
                    // 默认使用石头或木板
                    block = "minecraft:stone_bricks";
                }
                
                out.add(new BlockPatch(
                        BlockPatch.PLACE,
                        rp.x() + x,
                        rp.y(),
                        rp.z() + z,
                        block
                ));
            }
        }

        // 生成边缘护栏（如果指定了栏杆特征）
        if (hasRailing && height >= 1) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    // 只在边缘放置栏杆
                    boolean isEdge = (x == 0 || x == width - 1 || z == 0 || z == depth - 1);
                    if (isEdge) {
                        SemanticPart part = SemanticPart.RAILING;
                        String block = palette.pick(part);
                        if (block == null || block.isEmpty()) {
                            block = "minecraft:oak_fence";
                        }
                        
                        out.add(new BlockPatch(
                                BlockPatch.PLACE,
                                rp.x() + x,
                                rp.y() + 1,
                                rp.z() + z,
                                block
                        ));
                    }
                }
            }
        }

        return out;
    }

    private String getStyleProfile(SemanticComponent semantic) {
        // 1. 优先使用 SemanticComponent 中的 styleProfile
        if (semantic != null && semantic.styleProfile() != null && !semantic.styleProfile().isBlank()) {
            return semantic.styleProfile();
        }

        // 2. 尝试从 Component 的 features 推断风格
        Component c = semantic != null ? semantic.source() : null;
        if (c != null && c.features() != null) {
            for (String feature : c.features()) {
                if (feature == null) continue;
                String lower = feature.toLowerCase();
                if (lower.contains("chinese") || lower.contains("中式") || lower.contains("traditional")) {
                    return "CHINESE_TRADITIONAL";
                }
                if (lower.contains("medieval") || lower.contains("gothic")) {
                    return "MEDIEVAL_CLASSIC";
                }
                if (lower.contains("modern") || lower.contains("contemporary")) {
                    return "MODERN";
                }
            }
        }

        // 3. 默认
        return "DEFAULT";
    }
    
    private boolean hasFeature(Component c, String... keywords) {
        if (c == null || c.features() == null) {
            return false;
        }
        
        List<String> features = c.features();
        for (String keyword : keywords) {
            for (String feature : features) {
                if (feature != null && feature.toLowerCase().contains(keyword.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }
}

