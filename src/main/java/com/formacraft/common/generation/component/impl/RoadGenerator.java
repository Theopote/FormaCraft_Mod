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
 * RoadGenerator（道路生成器 v2）
 * 
 * 使用 Palette 权重随机生成平面道路
 * 
 * 核心提升：
 * - ✅ 使用 PaletteResolver（gravel / cobblestone / stone）
 * - ✅ 支持道路边缘（ROAD_EDGE）
 */
public class RoadGenerator implements ComponentGenerator {

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

        // 生成平面道路（使用 Palette 权重随机）
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    // 判断是否为边缘
                    boolean isEdge = (x == 0 || x == width - 1 || z == 0 || z == depth - 1);
                    SemanticPart part = (isEdge && width >= 3 && depth >= 3) 
                            ? SemanticPart.ROAD_EDGE 
                            : SemanticPart.ROAD_SURFACE;
                    
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
}

