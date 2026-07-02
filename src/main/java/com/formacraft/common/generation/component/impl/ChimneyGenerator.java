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
 * ChimneyGenerator（烟囱生成器）
 * 
 * 生成烟囱/排风结构
 * 通常是细长的垂直结构
 */
public class ChimneyGenerator implements ComponentGenerator {

    @Override
    public List<BlockPatch> generate(SemanticComponent semantic) {
        List<BlockPatch> out = new ArrayList<>();

        Component c = semantic.source();
        if (c == null || c.dimensions() == null || c.relativePosition() == null) {
            return out;
        }

        Dimensions d = c.dimensions();
        Vec3i rp = c.relativePosition();

        int width = Math.max(1, Math.min(3, d.width())); // 烟囱通常很窄（1-3 格）
        int depth = Math.max(1, Math.min(3, d.depth()));
        int height = Math.max(3, d.height()); // 烟囱通常较高

        String styleProfile = getStyleProfile(semantic);
        Palette palette = PaletteLibrary.forStyle(styleProfile);

        // 生成烟囱（细长的垂直结构）
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    // 根据位置确定语义部位
                    SemanticPart part = determinePart(y, height, width, depth, x, z);
                    String block = palette.pick(part);
                    if (block == null || block.isEmpty()) {
                        block = "minecraft:bricks";
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

    /**
     * 确定烟囱的语义部位
     */
    private SemanticPart determinePart(int y, int height, int width, int depth, int x, int z) {
        // 顶部使用装饰
        if (y >= height - 1) {
            return SemanticPart.DECOR;
        }
        
        // 底部使用基础
        if (y == 0) {
            return SemanticPart.WALL_BASE;
        }
        
        // 中间使用墙体
        return SemanticPart.WALL;
    }

    private String getStyleProfile(SemanticComponent semantic) {
        return "MEDIEVAL_CLASSIC";
    }
}

