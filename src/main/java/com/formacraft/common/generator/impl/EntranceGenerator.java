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

        // 生成入口结构（门洞 + 门框）
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    // 门洞（中间部分为空）
                    if (x > 0 && x < width - 1 && z > 0 && z < depth - 1 && y < height - 1) {
                        // 门洞内部不放置方块（由后续处理或保持空气）
                        continue;
                    }
                    
                    // 门框
                    SemanticPart part = SemanticPart.WALL_ACCENT;
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
        return "MEDIEVAL_CLASSIC";
    }
}

