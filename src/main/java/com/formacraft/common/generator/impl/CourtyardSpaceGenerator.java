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
 * CourtyardSpaceGenerator（庭院空间生成器）
 * 
 * 生成庭院/广场空间（铺装、花园等）
 */
public class CourtyardSpaceGenerator implements ComponentGenerator {

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
        int height = Math.max(1, Math.min(2, d.height())); // 庭院通常只有 1-2 格高

        String styleProfile = getStyleProfile(semantic);
        Palette palette = PaletteLibrary.forStyle(styleProfile);

        // 检查特征，决定生成什么
        boolean hasGarden = c.features() != null && c.features().stream()
                .anyMatch(f -> f != null && (f.contains("garden") || f.contains("pond") || f.contains("bed")));
        boolean hasPath = c.features() != null && c.features().stream()
                .anyMatch(f -> f != null && f.contains("path"));

        // 生成铺装地面
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    // 如果是路径区域，使用路径材质
                    if (hasPath && isPathArea(x, z, width, depth)) {
                        SemanticPart part = SemanticPart.ROAD_SURFACE;
                        String block = palette.pick(part);
                        out.add(new BlockPatch(
                                BlockPatch.PLACE,
                                rp.x() + x,
                                rp.y() + y,
                                rp.z() + z,
                                block
                        ));
                    } else if (hasGarden && isGardenArea(x, z, width, depth)) {
                        // 花园区域：使用不同的材质或留空（由后续处理）
                        continue; // 暂时跳过，或使用特殊材质
                    } else {
                        // 普通铺装（使用 ROAD_SURFACE 或 FLOOR）
                        SemanticPart part = SemanticPart.ROAD_SURFACE;
                        String block = palette.pick(part);
                        if (block == null || block.isEmpty()) {
                            // 如果没有 ROAD_SURFACE，尝试 FLOOR
                            part = SemanticPart.FLOOR;
                            block = palette.pick(part);
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
        }

        return out;
    }

    /**
     * 判断是否是路径区域（中心或边缘）
     */
    private boolean isPathArea(int x, int z, int width, int depth) {
        int centerX = width / 2;
        int centerZ = depth / 2;
        // 中心路径或边缘路径
        return (Math.abs(x - centerX) < 2) || (Math.abs(z - centerZ) < 2) ||
               (x < 2 || x >= width - 2) || (z < 2 || z >= depth - 2);
    }

    /**
     * 判断是否是花园区域
     */
    private boolean isGardenArea(int x, int z, int width, int depth) {
        // 简单的花园区域判断：角落或特定区域
        int centerX = width / 2;
        int centerZ = depth / 2;
        int distFromCenter = Math.abs(x - centerX) + Math.abs(z - centerZ);
        return distFromCenter > Math.min(width, depth) / 2;
    }

    private String getStyleProfile(SemanticComponent semantic) {
        return "MEDIEVAL_CLASSIC";
    }
}

