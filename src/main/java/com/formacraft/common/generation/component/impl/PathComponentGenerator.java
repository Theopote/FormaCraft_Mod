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
 * PathComponentGenerator（构件层路径生成器）
 *
 * 生成路径/道路（铺装地面）
 */
public class PathComponentGenerator implements ComponentGenerator {

    @Override
    public List<BlockPatch> generate(SemanticComponent semantic) {
        List<BlockPatch> out = new ArrayList<>();

        Component c = semantic.source();
        if (c == null || c.dimensions() == null || c.relativePosition() == null) {
            return out;
        }

        Dimensions d = c.dimensions();
        Vec3i rp = com.formacraft.common.generation.component.util.ComponentFootprintUtil.resolveLegacyCornerOrigin(c);

        int width = Math.max(1, d.width());
        int depth = Math.max(1, d.depth());
        int height = Math.max(1, Math.min(2, d.height()));

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    SemanticPart part = (x == 0 || x == width - 1 || z == 0 || z == depth - 1)
                            ? SemanticPart.ROAD_EDGE : SemanticPart.ROAD_SURFACE;
                    String block = com.formacraft.common.generation.component.util.ComponentMaterialResolver.resolve(
                            semantic, part, null, "path_block", "floor_block", "material");
                    if (block == null || block.isBlank()) {
                        block = com.formacraft.common.generation.component.util.ComponentMaterialResolver.resolve(
                                semantic, SemanticPart.ROAD_SURFACE, "minecraft:gravel",
                                "path_block", "floor_block", "material");
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

    private String getStyleProfile(SemanticComponent semantic) {
        return "MEDIEVAL_CLASSIC";
    }
}
