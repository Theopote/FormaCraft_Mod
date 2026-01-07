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
 * MassMainGenerator（主体体块生成器）
 * 
 * 生成建筑主体（店铺/住宅/厂房）
 * 使用 Palette 权重随机，支持不同风格
 */
public class MassMainGenerator implements ComponentGenerator {

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
        String styleProfile = getStyleProfile(semantic);
        Palette palette = PaletteLibrary.forStyle(styleProfile);

        // 生成矩形体块
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

    private String getStyleProfile(SemanticComponent semantic) {
        if (semantic.slot() != null && semantic.slot().program() != null) {
            // 可以根据 program 映射到风格
            String programName = semantic.slot().program();
            if ("COMMERCIAL".equals(programName)) {
                return "MODERN_CLASSIC";
            } else if ("RESIDENTIAL".equals(programName)) {
                return "MEDIEVAL_CLASSIC";
            }
        }
        return "MEDIEVAL_CLASSIC";
    }

    private SemanticPart determinePart(int y, int height, int width, int depth, int x, int z) {
        // 基础部分
        if (y == 0) {
            return SemanticPart.WALL_BASE;
        }
        
        // 顶部
        if (y >= height - 1) {
            return SemanticPart.WALL_ACCENT;
        }
        
        // 边缘（外墙）
        boolean isEdge = (x == 0 || x == width - 1 || z == 0 || z == depth - 1);
        if (isEdge) {
            return SemanticPart.WALL_ACCENT;
        }
        
        // 内部（填充）
        return SemanticPart.WALL;
    }
}

