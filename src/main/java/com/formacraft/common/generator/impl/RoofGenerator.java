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
 * RoofGenerator（屋顶生成器）
 * 
 * 生成屋顶结构（斜屋顶、平屋顶等）
 */
public class RoofGenerator implements ComponentGenerator {

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

        // 检查是否有斜屋顶特征
        boolean isPitched = c.features() != null && c.features().stream()
                .anyMatch(f -> f != null && (f.contains("pitched") || f.contains("sloped") || f.contains("curved")));

        if (isPitched) {
            // 生成斜屋顶
            generatePitchedRoof(out, rp, width, depth, height, palette);
        } else {
            // 生成平屋顶
            generateFlatRoof(out, rp, width, depth, height, palette);
        }

        return out;
    }

    /**
     * 生成斜屋顶
     */
    private void generatePitchedRoof(List<BlockPatch> out, Vec3i rp, int width, int depth, int height, Palette palette) {
        // 简化的斜屋顶：从边缘向中心逐渐升高
        int centerX = width / 2;
        int centerZ = depth / 2;
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    // 计算到中心的距离
                    int dx = Math.abs(x - centerX);
                    int dz = Math.abs(z - centerZ);
                    int dist = Math.max(dx, dz);
                    
                    // 根据距离和高度决定是否放置方块
                    int maxDist = Math.max(width, depth) / 2;
                    int targetY = (int) (height - 1 - (double) dist / maxDist * (height - 1));
                    
                    if (y == targetY || (y < targetY && dist < maxDist)) {
                        SemanticPart part = (y == height - 1) ? SemanticPart.ROOF_SURFACE : SemanticPart.ROOF;
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
        }
    }

    /**
     * 生成平屋顶
     */
    private void generateFlatRoof(List<BlockPatch> out, Vec3i rp, int width, int depth, int height, Palette palette) {
        // 平屋顶：只在最上层放置方块
        int topY = height - 1;
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                SemanticPart part = SemanticPart.ROOF_SURFACE;
                String block = palette.pick(part);
                
                out.add(new BlockPatch(
                        BlockPatch.PLACE,
                        rp.x() + x,
                        rp.y() + topY,
                        rp.z() + z,
                        block
                ));
            }
        }
    }

    private String getStyleProfile(SemanticComponent semantic) {
        return "MEDIEVAL_CLASSIC";
    }
}

