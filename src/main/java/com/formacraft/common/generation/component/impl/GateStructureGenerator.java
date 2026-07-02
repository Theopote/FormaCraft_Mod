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
 * GateStructureGenerator（门楼结构生成器）
 * 
 * 生成门楼/门洞结构
 */
public class GateStructureGenerator implements ComponentGenerator {

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

        // 生成门楼结构：门洞 + 门框 + 屋顶
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    // 门洞（中间部分为空，但这里先填充，后续可以移除）
                    boolean isOpening = (x > 0 && x < width - 1 && z > 0 && z < depth - 1 && y < height - 2);
                    if (isOpening) {
                        // 门洞内部不放置方块（保持空气）
                        continue;
                    }
                    
                    // 门框/门楼结构
                    SemanticPart part;
                    if (y >= height - 2) {
                        // 顶部：屋顶或装饰
                        part = SemanticPart.ROOF_SURFACE;
                    } else if (x == 0 || x == width - 1 || z == 0 || z == depth - 1) {
                        // 边缘：门框/柱子
                        part = SemanticPart.WALL_ACCENT;
                    } else {
                        // 其他：墙体
                        part = SemanticPart.WALL;
                    }
                    
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

