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
 * WallGenerator（墙体生成器 v2）
 * 
 * 使用 Palette 权重随机生成矩形墙体
 * 
 * 核心提升：
 * - ✅ 使用 PaletteResolver 替换硬编码方块
 * - ✅ 墙体自然老化、有苔藓、有裂纹、有变化
 */
public class WallGenerator implements ComponentGenerator {

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

        // 获取风格
        String styleProfile = "MEDIEVAL_CLASSIC"; // 默认
        Palette palette = PaletteLibrary.forStyle(styleProfile);

        // 生成矩形墙体（使用 Palette 权重随机）
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    // 根据位置确定 SemanticPart
                    SemanticPart part = determinePart(y, height, width, depth, x, z);
                    String block = palette.pick(part);
                    
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
     * 根据位置确定 SemanticPart
     */
    private SemanticPart determinePart(int y, int height, int width, int depth, int x, int z) {
        // 基础部分
        if (y == 0) {
            return SemanticPart.WALL_BASE;
        }
        
        // 顶部装饰
        if (y >= height - 1) {
            return SemanticPart.WALL_ACCENT;
        }
        
        // 边缘装饰
        if (x == 0 || x == width - 1 || z == 0 || z == depth - 1) {
            return SemanticPart.WALL_ACCENT;
        }
        
        // 默认墙体
        return SemanticPart.WALL;
    }
}

