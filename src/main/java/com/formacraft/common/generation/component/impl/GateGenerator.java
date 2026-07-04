package com.formacraft.common.generation.component.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.ComponentGenerator;
import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.palette.component.PaletteLibrary;
import com.formacraft.common.semantic.SemanticPart;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GateGenerator（门楼生成器）
 *
 * 参数化门洞框架：dimensions + door_width/door_height params，style 驱动材质。
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
        Map<String, Object> params = c.params();

        int width = Math.max(3, d.width());
        int depth = Math.max(1, d.depth());
        int height = Math.max(3, d.height());

        int doorWidth = ComponentParamParsers.intParam(params, 0, "door_width", "doorWidth");
        int doorHeight = ComponentParamParsers.intParam(params, 0, "door_height", "doorHeight");
        if (doorWidth <= 0) {
            doorWidth = Math.max(1, Math.min(width - 2, width / 3));
        }
        if (doorHeight <= 0) {
            doorHeight = Math.max(2, Math.min(height - 1, height - 1));
        }
        doorWidth = Math.max(1, Math.min(doorWidth, width - 2));
        doorHeight = Math.max(2, Math.min(doorHeight, height - 1));

        int doorStartX = (width - doorWidth) / 2;
        int doorEndX = doorStartX + doorWidth - 1;

        String styleProfile = resolveStyleProfile(semantic);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    boolean isOpening = y < doorHeight && x >= doorStartX && x <= doorEndX;
                    if (isOpening) {
                        continue;
                    }

                    SemanticPart part = y == 0 ? SemanticPart.WALL_BASE : SemanticPart.WALL;
                    if (y >= height - 1) {
                        part = SemanticPart.WALL_ACCENT;
                    }
                    String block = PaletteLibrary.resolveBlock(part, styleProfile, semantic.styleAttributes());
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

        String lintel = PaletteLibrary.resolveBlock(SemanticPart.WALL_ACCENT, styleProfile, semantic.styleAttributes());
        for (int x = 0; x < width; x++) {
            out.add(new BlockPatch(
                    BlockPatch.PLACE,
                    rp.x() + x,
                    rp.y() + height - 1,
                    rp.z(),
                    lintel
            ));
        }

        return out;
    }

    private static String resolveStyleProfile(SemanticComponent semantic) {
        if (semantic.styleProfile() != null && !semantic.styleProfile().isBlank()) {
            return semantic.styleProfile();
        }
        return "MEDIEVAL_CLASSIC";
    }
}
