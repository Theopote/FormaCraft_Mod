package com.formacraft.common.generation.component.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.ComponentGenerator;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.palette.component.PaletteLibrary;
import com.formacraft.common.semantic.SemanticPart;

import java.util.ArrayList;
import java.util.List;

/**
 * FoundationGenerator（基础生成器）
 * 
 * 生成建筑基础/地基
 * 通常是埋在地下的结构
 */
public class FoundationGenerator implements ComponentGenerator {

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
        int height = Math.max(1, Math.min(5, d.height())); // 基础通常只有 1-5 格高

        String styleProfile = semantic.styleProfile();
        if (styleProfile == null || styleProfile.isBlank()) {
            styleProfile = "MEDIEVAL_CLASSIC";
        }

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    SemanticPart part = SemanticPart.FOUNDATION;
                    String block = PaletteLibrary.resolveBlock(part, styleProfile, semantic.styleAttributes());
                    if (block == null || block.isBlank()) {
                        block = PaletteLibrary.resolveBlock(SemanticPart.WALL_BASE, styleProfile, semantic.styleAttributes());
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
}

