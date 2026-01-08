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
                        if (block == null || block.isEmpty()) {
                            // 回退到 COURTYARD_FLOOR 或 FLOOR
                            part = SemanticPart.COURTYARD_FLOOR;
                            block = palette.pick(part);
                            if (block == null || block.isEmpty()) {
                                part = SemanticPart.FLOOR;
                                block = palette.pick(part);
                            }
                        }
                        if (block == null || block.isEmpty()) {
                            block = "minecraft:stone_bricks";
                        }
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
                        // 普通铺装（优先使用 COURTYARD_FLOOR，回退到 FLOOR 或 ROAD_SURFACE）
                        SemanticPart part = SemanticPart.COURTYARD_FLOOR;
                        String block = palette.pick(part);
                        if (block == null || block.isEmpty()) {
                            // 如果没有 COURTYARD_FLOOR，尝试 FLOOR
                            part = SemanticPart.FLOOR;
                            block = palette.pick(part);
                        }
                        if (block == null || block.isEmpty()) {
                            // 如果还没有，尝试 ROAD_SURFACE
                            part = SemanticPart.ROAD_SURFACE;
                            block = palette.pick(part);
                        }
                        if (block == null || block.isEmpty()) {
                            // 最后的默认值
                            block = "minecraft:stone_bricks";
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

