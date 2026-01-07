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
 * SignageGenerator（招牌生成器）
 * 
 * 生成招牌/牌匾/灯箱
 */
public class SignageGenerator implements ComponentGenerator {

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
        int height = Math.max(1, Math.min(2, d.height())); // 招牌通常只有 1-2 格高

        String styleProfile = getStyleProfile(semantic);
        Palette palette = PaletteLibrary.forStyle(styleProfile);

        // 生成招牌（通常是扁平的）
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
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
        // 商业建筑使用更亮的招牌
        if (semantic.slot() != null && semantic.slot().program() != null) {
            String programName = semantic.slot().program();
            if ("COMMERCIAL".equals(programName)) {
                return "MODERN_CLASSIC";
            }
        }
        return "MEDIEVAL_CLASSIC";
    }
}

