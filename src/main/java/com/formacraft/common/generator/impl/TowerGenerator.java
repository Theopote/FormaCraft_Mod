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
 * TowerGenerator（塔楼生成器 v2）
 * 
 * 圆形塔楼生成器（使用 Palette 权重随机）
 * 
 * 核心提升：
 * - ✅ 使用 PaletteResolver 替换硬编码方块
 * - ✅ 同一个 JSON，每次生成略有不同
 * - ✅ 建筑不再"贴图感"
 * - ✅ 风格可以被系统性扩展
 * 
 * 后续升级方向：
 * - 使用 GeometryModifier 做 taper / battlement
 * - 使用 ToolModifier 裁剪禁区 / 对称
 */
public class TowerGenerator implements ComponentGenerator {

    @Override
    public List<BlockPatch> generate(SemanticComponent semantic) {
        List<BlockPatch> out = new ArrayList<>();

        Component c = semantic.source();
        if (c == null || c.dimensions() == null || c.relativePosition() == null) {
            return out;
        }

        Dimensions d = c.dimensions();
        Vec3i rp = c.relativePosition();

        int radius = Math.max(1, d.width() / 2);
        int height = Math.max(1, d.height());

        // 获取风格（从 slot 或全局 styleProfile）
        String styleProfile = getStyleProfile(semantic);
        Palette palette = PaletteLibrary.forStyle(styleProfile);

        // 检查 features
        boolean hasWindows = hasFeature(c, "windows", "window");
        boolean hasStairs = hasFeature(c, "stairs", "stair", "spiral_stairs");
        boolean hasInterior = hasFeature(c, "interior", "rooms", "floors");
        boolean hasBattlements = hasFeature(c, "battlements", "battlement", "crenelation");
        boolean hasRoof = hasFeature(c, "roof", "spire", "dome");

        // 生成圆形塔楼（使用 Palette 权重随机）
        for (int y = 0; y < height; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    int distSq = x * x + z * z;
                    if (distSq <= radius * radius) {
                        // 检查是否是窗户位置
                        if (hasWindows && isWindowPosition(x, z, radius, y, height)) {
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

                        // 检查是否是楼梯位置
                        if (hasStairs && isStairPosition(x, z, radius, y, height)) {
                            SemanticPart part = SemanticPart.STAIR_STEP;
                            String block = palette.pick(part);
                            if (block == null || block.isEmpty()) {
                                block = "minecraft:stone_brick_stairs";
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

                        // 检查是否是内部空间（留空）
                        if (hasInterior && isInteriorSpace(x, z, radius)) {
                            // 内部空间不放置方块（保持空气）
                            continue;
                        }

                        // 检查是否是垛口位置
                        if (hasBattlements && isBattlementPosition(x, z, radius, y, height)) {
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

                        // 检查是否是屋顶位置
                        if (hasRoof && y >= height - 1) {
                            SemanticPart part = SemanticPart.ROOF_SURFACE;
                            String block = palette.pick(part);
                            if (block == null || block.isEmpty()) {
                                part = SemanticPart.ROOF;
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

                        // 默认：根据位置选择不同的 SemanticPart
                        SemanticPart part = determinePart(y, height, radius, x, z);
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

        return out;
    }

    /**
     * 检查是否是窗户位置
     */
    private boolean isWindowPosition(int x, int z, int radius, int y, int height) {
        // 窗户通常在塔楼边缘，不在底部和顶部
        int distSq = x * x + z * z;
        boolean isNearEdge = (distSq >= (radius - 1) * (radius - 1));
        boolean isMiddleHeight = (y > 1 && y < height - 2);
        // 窗户通常间隔放置（按角度）
        double angle = Math.atan2(z, x);
        int angleStep = (int) (angle / (Math.PI / 4)); // 每 45 度一个窗户
        boolean isWindowAngle = (angleStep % 2 == 0);
        return isNearEdge && isMiddleHeight && isWindowAngle;
    }

    /**
     * 检查是否是楼梯位置（螺旋楼梯）
     */
    private boolean isStairPosition(int x, int z, int radius, int y, int height) {
        // 螺旋楼梯通常在塔楼内部，沿边缘
        int distSq = x * x + z * z;
        boolean isNearEdge = (distSq >= (radius - 2) * (radius - 2) && distSq <= (radius - 1) * (radius - 1));
        // 螺旋楼梯按高度旋转
        double angle = Math.atan2(z, x);
        int angleStep = (int) ((angle + y * Math.PI / 8) / (Math.PI / 4));
        boolean isStairStep = (angleStep % 2 == 0);
        return isNearEdge && isStairStep && y < height - 1;
    }

    /**
     * 检查是否是内部空间
     */
    private boolean isInteriorSpace(int x, int z, int radius) {
        // 内部空间：距离中心较近的区域
        int distSq = x * x + z * z;
        return distSq < (radius - 2) * (radius - 2);
    }

    /**
     * 检查是否是垛口位置
     */
    private boolean isBattlementPosition(int x, int z, int radius, int y, int height) {
        // 垛口通常在顶部边缘
        int distSq = x * x + z * z;
        boolean isTop = (y >= height - 2);
        boolean isEdge = (distSq >= (radius - 1) * (radius - 1));
        // 垛口通常间隔放置
        double angle = Math.atan2(z, x);
        int angleStep = (int) (angle / (Math.PI / 4));
        boolean isBattlementSpacing = (angleStep % 2 == 0);
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
     * 获取风格配置
     */
    private String getStyleProfile(SemanticComponent semantic) {
        // 优先从 slot 获取（如果有）
        if (semantic.slot() != null && semantic.slot().program() != null) {
            // 可以根据 program 映射到风格
            // 例如：COMMERCIAL -> MODERN, RESIDENTIAL -> MEDIEVAL
        }
        
        // 默认返回中世纪风格
        return "MEDIEVAL_CLASSIC";
    }

    /**
     * 根据位置确定 SemanticPart
     */
    private SemanticPart determinePart(int y, int height, int radius, int x, int z) {
        // 基础部分使用 WALL_BASE
        if (y == 0) {
            return SemanticPart.WALL_BASE;
        }
        
        // 顶部使用 WALL_ACCENT（装饰）
        if (y >= height - 2) {
            return SemanticPart.WALL_ACCENT;
        }
        
        // 边缘使用 WALL_ACCENT（强调）
        int distSq = x * x + z * z;
        if (distSq >= (radius - 1) * (radius - 1)) {
            return SemanticPart.WALL_ACCENT;
        }
        
        // 默认使用 WALL
        return SemanticPart.WALL;
    }
}

