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
 * WallGenerator（墙体生成器 v2）
 * 
 * 使用 Palette 权重随机生成矩形墙体
 * 
 * 核心提升：
 * - ✅ 使用 PaletteResolver 替换硬编码方块
 * - ✅ 墙体自然老化、有苔藓、有裂纹、有变化
 */
public class WallGenerator implements ComponentGenerator {

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

        // 获取风格
        String styleProfile = "MEDIEVAL_CLASSIC"; // 默认
        Palette palette = PaletteLibrary.forStyle(styleProfile);

        // 检查 features
        boolean hasWindows = hasFeature(c, "windows", "window", "arrow_slits");
        boolean hasDecor = hasFeature(c, "decor", "decoration", "ornament", "carved");
        boolean hasBattlements = hasFeature(c, "battlements", "battlement", "crenelation");

        // 生成矩形墙体（使用 Palette 权重随机）
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    // 检查是否是窗户位置
                    if (hasWindows && isWindowPosition(x, z, width, depth, y, height)) {
                        SemanticPart part = SemanticPart.WINDOW;
                        String block = palette.pick(part);
                        if (block == null || block.isEmpty()) {
                            block = "minecraft:glass";
                        }
                        out.add(new BlockPatch(
                                BlockPatch.PLACE,
                                rp.x() + x,
                                rp.y() + y,
                                rp.z() + z,
                                block
                        ));
                        continue;
                    }

                    // 检查是否是装饰位置
                    if (hasDecor && isDecorPosition(x, z, width, depth, y, height)) {
                        SemanticPart part = SemanticPart.DECOR;
                        String block = palette.pick(part);
                        if (block == null || block.isEmpty()) {
                            part = SemanticPart.WALL_ACCENT;
                            block = palette.pick(part);
                        }
                        out.add(new BlockPatch(
                                BlockPatch.PLACE,
                                rp.x() + x,
                                rp.y() + y,
                                rp.z() + z,
                                block
                        ));
                        continue;
                    }

                    // 检查是否是垛口位置
                    if (hasBattlements && isBattlementPosition(x, z, width, depth, y, height)) {
                        SemanticPart part = SemanticPart.BATTLEMENT;
                        String block = palette.pick(part);
                        if (block == null || block.isEmpty()) {
                            part = SemanticPart.WALL_ACCENT;
                            block = palette.pick(part);
                        }
                        out.add(new BlockPatch(
                                BlockPatch.PLACE,
                                rp.x() + x,
                                rp.y() + y,
                                rp.z() + z,
                                block
                        ));
                        continue;
                    }

                    // 默认：根据位置确定 SemanticPart
                    SemanticPart part = determinePart(y, height, width, depth, x, z);
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

    /**
     * 检查是否是窗户位置
     */
    private boolean isWindowPosition(int x, int z, int width, int depth, int y, int height) {
        // 窗户通常在立面（z=0 或 z=depth-1），不在底部和顶部
        boolean isFacade = (z == 0 || z == depth - 1);
        boolean isMiddleHeight = (y > 0 && y < height - 1);
        // 窗户通常不在角落，间隔放置
        boolean isNotCorner = (x > 0 && x < width - 1);
        boolean isWindowSpacing = (x % 4 == 2 || x % 4 == 3);
        return isFacade && isMiddleHeight && isNotCorner && isWindowSpacing;
    }

    /**
     * 检查是否是装饰位置
     */
    private boolean isDecorPosition(int x, int z, int width, int depth, int y, int height) {
        // 装饰通常在边缘、顶部、角落
        boolean isEdge = (x == 0 || x == width - 1 || z == 0 || z == depth - 1);
        boolean isTop = (y >= height - 2);
        boolean isCorner = ((x == 0 || x == width - 1) && (z == 0 || z == depth - 1));
        // 装饰间隔放置
        boolean isDecorSpacing = (x % 3 == 0 || z % 3 == 0);
        return (isEdge || isTop || isCorner) && isDecorSpacing && (y > 0);
    }

    /**
     * 检查是否是垛口位置
     */
    private boolean isBattlementPosition(int x, int z, int width, int depth, int y, int height) {
        // 垛口通常在顶部边缘
        boolean isTop = (y >= height - 1);
        boolean isEdge = (x == 0 || x == width - 1 || z == 0 || z == depth - 1);
        // 垛口间隔放置
        boolean isBattlementSpacing = (x % 2 == 0 || z % 2 == 0);
        return isTop && isEdge && isBattlementSpacing;
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

    /**
     * 根据位置确定 SemanticPart
     */
    private SemanticPart determinePart(int y, int height, int width, int depth, int x, int z) {
        // 基础部分
        if (y == 0) {
            return SemanticPart.WALL_BASE;
        }
        
        // 顶部装饰
        if (y >= height - 1) {
            return SemanticPart.WALL_ACCENT;
        }
        
        // 边缘装饰
        if (x == 0 || x == width - 1 || z == 0 || z == depth - 1) {
            return SemanticPart.WALL_ACCENT;
        }
        
        // 默认墙体
        return SemanticPart.WALL;
    }
}

