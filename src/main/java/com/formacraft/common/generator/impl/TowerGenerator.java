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
 * TowerGenerator（塔楼生成器 v2）
 * 
 * 圆形塔楼生成器（使用 Palette 权重随机）
 * 
 * 核心提升：
 * - ✅ 使用 PaletteResolver 替换硬编码方块
 * - ✅ 同一个 JSON，每次生成略有不同
 * - ✅ 建筑不再"贴图感"
 * - ✅ 风格可以被系统性扩展
 * 
 * 后续升级方向：
 * - 使用 GeometryModifier 做 taper / battlement
 * - 使用 ToolModifier 裁剪禁区 / 对称
 */
public class TowerGenerator implements ComponentGenerator {

    @Override
    public List<BlockPatch> generate(SemanticComponent semantic) {
        List<BlockPatch> out = new ArrayList<>();

        Component c = semantic.source();
        if (c == null || c.dimensions() == null || c.relativePosition() == null) {
            return out;
        }

        Dimensions d = c.dimensions();
        Vec3i rp = c.relativePosition();

        int radius = Math.max(1, d.width() / 2);
        int height = Math.max(1, d.height());

        // 获取风格（从 slot 或全局 styleProfile）
        String styleProfile = getStyleProfile(semantic);
        Palette palette = PaletteLibrary.forStyle(styleProfile);

        // 生成圆形塔楼（使用 Palette 权重随机）
        for (int y = 0; y < height; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x * x + z * z <= radius * radius) {
                        // 根据位置选择不同的 SemanticPart
                        SemanticPart part = determinePart(y, height, radius, x, z);
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
        }

        return out;
    }

    /**
     * 获取风格配置
     */
    private String getStyleProfile(SemanticComponent semantic) {
        // 优先从 slot 获取（如果有）
        if (semantic.slot() != null && semantic.slot().program() != null) {
            // 可以根据 program 映射到风格
            // 例如：COMMERCIAL -> MODERN, RESIDENTIAL -> MEDIEVAL
        }
        
        // 默认返回中世纪风格
        return "MEDIEVAL_CLASSIC";
    }

    /**
     * 根据位置确定 SemanticPart
     */
    private SemanticPart determinePart(int y, int height, int radius, int x, int z) {
        // 基础部分使用 WALL_BASE
        if (y == 0) {
            return SemanticPart.WALL_BASE;
        }
        
        // 顶部使用 WALL_ACCENT（装饰）
        if (y >= height - 2) {
            return SemanticPart.WALL_ACCENT;
        }
        
        // 边缘使用 WALL_ACCENT（强调）
        int distSq = x * x + z * z;
        if (distSq >= (radius - 1) * (radius - 1)) {
            return SemanticPart.WALL_ACCENT;
        }
        
        // 默认使用 WALL
        return SemanticPart.WALL;
    }
}

