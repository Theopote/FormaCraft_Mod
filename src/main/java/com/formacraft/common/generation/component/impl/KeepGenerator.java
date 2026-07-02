package com.formacraft.common.generation.component.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.ComponentGenerator;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;

import java.util.ArrayList;
import java.util.List;

/**
 * KeepGenerator（主堡生成器）
 * 
 * 基础版本：生成矩形主堡（类似 Tower，但更大）
 * 
 * 后续升级方向：
 * - 使用 PaletteResolver
 * - 使用 GeometryModifier（垛口、塔楼节点等）
 * - 支持内部结构（房间、楼梯等）
 */
public class KeepGenerator implements ComponentGenerator {

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

        // 生成矩形主堡
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    // 外墙：使用 stone_bricks
                    boolean isWall = (x == 0 || x == width - 1 || z == 0 || z == depth - 1);
                    String block = isWall ? "minecraft:stone_bricks" : "minecraft:cobblestone";

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

