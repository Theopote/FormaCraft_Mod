package com.formacraft.common.generator.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generator.ComponentGenerator;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;

import java.util.ArrayList;
import java.util.List;

/**
 * GateGenerator（门楼生成器）
 * 
 * 基础版本：生成门洞和门楣
 * 
 * 后续升级方向：
 * - 支持 facing 方向
 * - 使用 PaletteResolver
 * - 门洞自动识别（中间留空）
 */
public class GateGenerator implements ComponentGenerator {

    @Override
    public List<BlockPatch> generate(SemanticComponent semantic) {
        List<BlockPatch> out = new ArrayList<>();

        Component c = semantic.source();
        if (c == null || c.dimensions() == null || c.relativePosition() == null) {
            return out;
        }

        Dimensions d = c.dimensions();
        Vec3i rp = c.relativePosition();

        int width = Math.max(3, d.width());
        int depth = Math.max(1, d.depth());
        int height = Math.max(3, d.height());

        // 生成门楼框架
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    // 门洞：中间留空（宽度 >= 3 时，中间 1 格留空）
                    boolean isOpening = (width >= 3) && (x == width / 2) && (y < height - 1);
                    
                    if (!isOpening) {
                        out.add(new BlockPatch(
                                BlockPatch.PLACE,
                                rp.x() + x,
                                rp.y() + y,
                                rp.z() + z,
                                "minecraft:stone_bricks"
                        ));
                    }
                }
            }
        }

        // 门楣（顶部）
        for (int x = 0; x < width; x++) {
            out.add(new BlockPatch(
                    BlockPatch.PLACE,
                    rp.x() + x,
                    rp.y() + height - 1,
                    rp.z(),
                    "minecraft:chiseled_stone_bricks"
            ));
        }

        return out;
    }
}

