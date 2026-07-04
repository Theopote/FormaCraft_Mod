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
 * KeepGenerator（主堡生成器）
 *
 * 参数化矩形主堡：dimensions 驱动体量，style/style_attributes 驱动材质。
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
        int wallThickness = resolveWallThickness(c.params());

        String styleProfile = resolveStyleProfile(semantic);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    boolean isExterior = isExteriorCell(x, z, width, depth, wallThickness);
                    if (!isExterior && y > 0 && y < height - 1) {
                        continue;
                    }

                    SemanticPart part = y == 0
                            ? SemanticPart.WALL_BASE
                            : (y >= height - 1 ? SemanticPart.WALL_ACCENT : SemanticPart.WALL);
                    if (!isExterior && y == 0) {
                        part = SemanticPart.FLOOR;
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

        return out;
    }

    private static int resolveWallThickness(Map<String, Object> params) {
        int thickness = ComponentParamParsers.intParam(params, 1, "wall_thickness", "wallThickness");
        return Math.max(1, Math.min(3, thickness));
    }

    private static boolean isExteriorCell(int x, int z, int width, int depth, int wallThickness) {
        return x < wallThickness || x >= width - wallThickness
                || z < wallThickness || z >= depth - wallThickness;
    }

    private static String resolveStyleProfile(SemanticComponent semantic) {
        if (semantic.styleProfile() != null && !semantic.styleProfile().isBlank()) {
            return semantic.styleProfile();
        }
        return "MEDIEVAL_CLASSIC";
    }
}
