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
        Vec3i rp = com.formacraft.common.generation.component.util.ComponentFootprintUtil.resolveLegacyCornerOrigin(c);

        int width = Math.max(1, d.width());
        int depth = Math.max(1, d.depth());
        int height = Math.max(1, Math.min(2, d.height())); // 庭院通常只有 1-2 格高

        boolean hasGarden = c.features() != null && c.features().stream()
                .anyMatch(f -> f != null && (f.contains("garden") || f.contains("pond") || f.contains("bed")));
        boolean hasPath = c.features() != null && c.features().stream()
                .anyMatch(f -> f != null && f.contains("path"));

        String pathBlock = com.formacraft.common.generation.component.util.ComponentMaterialResolver.resolve(
                semantic, SemanticPart.ROAD_SURFACE, null, "path_block", "floor_block", "material");
        if (pathBlock == null || pathBlock.isBlank()) {
            pathBlock = com.formacraft.common.generation.component.util.ComponentMaterialResolver.resolve(
                    semantic, SemanticPart.COURTYARD_FLOOR, "minecraft:stone_bricks",
                    "floor_block", "material");
        }
        String courtBlock = com.formacraft.common.generation.component.util.ComponentMaterialResolver.resolve(
                semantic, SemanticPart.COURTYARD_FLOOR, null, "floor_block", "material");
        if (courtBlock == null || courtBlock.isBlank()) {
            courtBlock = com.formacraft.common.generation.component.util.ComponentMaterialResolver.resolve(
                    semantic, SemanticPart.FLOOR, "minecraft:stone_bricks", "floor_block", "material");
        }

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    if (hasPath && isPathArea(x, z, width, depth)) {
                        out.add(new BlockPatch(
                                BlockPatch.PLACE,
                                rp.x() + x,
                                rp.y() + y,
                                rp.z() + z,
                                pathBlock
                        ));
                    } else if (hasGarden && isGardenArea(x, z, width, depth)) {
                        continue;
                    } else {
                        out.add(new BlockPatch(
                                BlockPatch.PLACE,
                                rp.x() + x,
                                rp.y() + y,
                                rp.z() + z,
                                courtBlock
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
        // 1. 优先使用 SemanticComponent 中的 styleProfile
        if (semantic != null && semantic.styleProfile() != null && !semantic.styleProfile().isBlank()) {
            String profile = semantic.styleProfile().trim();
            String upper = profile.toUpperCase();
            if (upper.contains("CHINESE") || upper.contains("HUI")) {
                return "HUI_STYLE_VILLA";
            }
            return profile;
        }
        
        // 2. 尝试从 Component 的 features 推断风格
        Component c = semantic != null ? semantic.source() : null;
        if (c != null && c.features() != null) {
            for (String feature : c.features()) {
                if (feature == null) continue;
                String lower = feature.toLowerCase();
                if (lower.contains("chinese") || lower.contains("中式")) {
                    return "HUI_STYLE_VILLA";
                }
            }
        }
        
        // 3. 默认
        return "MEDIEVAL_CLASSIC";
    }
}

