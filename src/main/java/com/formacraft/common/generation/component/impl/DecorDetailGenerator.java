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
 * DecorDetailGenerator（装饰细节生成器）
 * 
 * 生成装饰细节（雕刻、装饰块、装饰图案等）
 * 通常是小型的装饰元素
 */
public class DecorDetailGenerator implements ComponentGenerator {

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
        int height = Math.max(1, Math.min(3, d.height())); // 装饰通常很小（1-3 格高）

        String styleProfile = getStyleProfile(semantic);
        Palette palette = PaletteLibrary.forStyle(styleProfile);

        // 检查 features 以确定装饰类型
        boolean isCarved = hasFeature(c, "carved", "carving");
        // boolean isOrnate = hasFeature(c, "ornate", "ornament"); // 保留用于未来扩展
        boolean isPattern = hasFeature(c, "pattern", "lattice");

        // 生成装饰细节（通常是稀疏的，不是实心的）
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    // 装饰通常是稀疏的（只在特定位置）
                    if (shouldPlaceDecor(x, y, z, width, depth, height, isPattern)) {
                        SemanticPart part = SemanticPart.DECOR;
                        String block = palette.pick(part);
                        if (block == null || block.isEmpty()) {
                            block = isCarved ? "minecraft:chiseled_stone_bricks" : "minecraft:stone_bricks";
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
     * 决定是否在特定位置放置装饰
     */
    private boolean shouldPlaceDecor(int x, int y, int z, int width, int depth, int height, boolean isPattern) {
        // 如果是图案，只在边缘或特定位置
        if (isPattern) {
            return (x == 0 || x == width - 1 || z == 0 || z == depth - 1) && (y == 0 || y == height - 1);
        }
        
        // 否则，只在边缘
        return (x == 0 || x == width - 1 || z == 0 || z == depth - 1);
    }

    /**
     * 检查是否有特定特征
     */
    private boolean hasFeature(Component c, String... keywords) {
        if (c.features() == null) return false;
        for (String feature : c.features()) {
            if (feature == null) continue;
            String lower = feature.toLowerCase();
            for (String keyword : keywords) {
                if (lower.contains(keyword.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    private String getStyleProfile(SemanticComponent semantic) {
        return "MEDIEVAL_CLASSIC";
    }
}

