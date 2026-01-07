package com.formacraft.common.generator.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generator.ComponentGenerator;
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
 * EntranceGenerator（入口生成器）
 * 
 * 生成门廊/入口结构
 */
public class EntranceGenerator implements ComponentGenerator {

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

        // 检查 features
        boolean hasOverhang = hasFeature(c, "overhang", "canopy", "awning");
        boolean hasDecorativeLintel = hasFeature(c, "decorative_lintel", "lintel", "carving", "ornament", "decorative");
        
        // 生成入口结构（门洞 + 门框）
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    // 门洞（中间部分为空，但底部可以放置门槛）
                    boolean isDoorOpening = (x > 0 && x < width - 1 && z > 0 && z < depth - 1 && y < height - 1);
                    if (isDoorOpening) {
                        // 门洞内部不放置方块（保持空气）
                        // 但可以在底部放置门槛
                        if (y == 0) {
                            SemanticPart part = SemanticPart.WALL_BASE;
                            String block = palette.pick(part);
                            out.add(new BlockPatch(
                                    BlockPatch.PLACE,
                                    rp.x() + x,
                                    rp.y() + y,
                                    rp.z() + z,
                                    block
                            ));
                        }
                        continue;
                    }
                    
                    // 门框（边缘部分）
                    SemanticPart part;
                    // 顶部可以是门楣/过梁
                    if (y >= height - 1 && hasDecorativeLintel) {
                        part = SemanticPart.DECOR;
                    } else if (y >= height - 1 && hasOverhang) {
                        part = SemanticPart.ROOF_SURFACE; // 门廊顶
                    } else {
                        part = SemanticPart.WALL_ACCENT; // 门框
                    }
                    String block = palette.pick(part);
                    if (block == null || block.isEmpty()) {
                        block = palette.pick(SemanticPart.WALL_ACCENT);
                    }
                    
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

        return out;
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
                if (lower.contains("gothic") || lower.contains("pointed") || lower.contains("flying")) {
                    return "MEDIEVAL_CLASSIC";
                }
                if (lower.contains("chinese") || lower.contains("中式")) {
                    return "HUI_STYLE_VILLA";
                }
            }
        }

        // 3. 默认
        return "MEDIEVAL_CLASSIC";
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
}

