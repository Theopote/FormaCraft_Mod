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
 * BalconyGenerator（阳台生成器）
 * 
 * 生成阳台/挑檐平台
 * 包括平台、栏杆、支撑结构
 */
public class BalconyGenerator implements ComponentGenerator {

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
        int height = Math.max(1, Math.min(2, d.height())); // 阳台通常只有 1-2 格高

        String styleProfile = getStyleProfile(semantic);
        Palette palette = PaletteLibrary.forStyle(styleProfile);

        // 生成阳台平台（底部）
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                SemanticPart part = SemanticPart.FLOOR;
                String block = palette.pick(part);
                if (block == null || block.isEmpty()) {
                    block = "minecraft:oak_planks";
                }
                
                out.add(new BlockPatch(
                        BlockPatch.PLACE,
                        rp.x() + x,
                        rp.y(),
                        rp.z() + z,
                        block
                ));
            }
        }

        // 生成栏杆（边缘）
        if (height >= 2) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    // 只在边缘放置栏杆
                    boolean isEdge = (x == 0 || x == width - 1 || z == 0 || z == depth - 1);
                    if (isEdge) {
                        SemanticPart part = SemanticPart.RAILING;
                        String block = palette.pick(part);
                        if (block == null || block.isEmpty()) {
                            block = "minecraft:oak_fence";
                        }
                        
                        out.add(new BlockPatch(
                                BlockPatch.PLACE,
                                rp.x() + x,
                                rp.y() + 1,
                                rp.z() + z,
                                block
                        ));
                    }
                }
            }
        }

        return out;
    }

    private String getStyleProfile(SemanticComponent semantic) {
        return "MEDIEVAL_CLASSIC";
    }
}

