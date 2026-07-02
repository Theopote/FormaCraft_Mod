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
 * TowerComponentGenerator（构件层塔楼生成器 v2）
 *
 * 圆形塔楼生成器（使用 Palette 权重随机）
 */
public class TowerComponentGenerator implements ComponentGenerator {

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

        String styleProfile = getStyleProfile(semantic);
        Palette palette = PaletteLibrary.forStyle(styleProfile);

        boolean hasWindows = hasFeature(c, "windows", "window");
        boolean hasStairs = hasFeature(c, "stairs", "stair", "spiral_stairs");
        boolean hasInterior = hasFeature(c, "interior", "rooms", "floors");
        boolean hasBattlements = hasFeature(c, "battlements", "battlement", "crenelation");
        boolean hasRoof = hasFeature(c, "roof", "spire", "dome");

        for (int y = 0; y < height; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    int distSq = x * x + z * z;
                    if (distSq <= radius * radius) {
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

                        if (hasInterior && isInteriorSpace(x, z, radius)) {
                            continue;
                        }

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

    private boolean isWindowPosition(int x, int z, int radius, int y, int height) {
        int distSq = x * x + z * z;
        boolean isNearEdge = (distSq >= (radius - 1) * (radius - 1));
        boolean isMiddleHeight = (y > 1 && y < height - 2);
        double angle = Math.atan2(z, x);
        int angleStep = (int) (angle / (Math.PI / 4));
        boolean isWindowAngle = (angleStep % 2 == 0);
        return isNearEdge && isMiddleHeight && isWindowAngle;
    }

    private boolean isStairPosition(int x, int z, int radius, int y, int height) {
        int distSq = x * x + z * z;
        boolean isNearEdge = (distSq >= (radius - 2) * (radius - 2) && distSq <= (radius - 1) * (radius - 1));
        double angle = Math.atan2(z, x);
        int angleStep = (int) ((angle + y * Math.PI / 8) / (Math.PI / 4));
        boolean isStairStep = (angleStep % 2 == 0);
        return isNearEdge && isStairStep && y < height - 1;
    }

    private boolean isInteriorSpace(int x, int z, int radius) {
        int distSq = x * x + z * z;
        return distSq < (radius - 2) * (radius - 2);
    }

    private boolean isBattlementPosition(int x, int z, int radius, int y, int height) {
        int distSq = x * x + z * z;
        boolean isTop = (y >= height - 2);
        boolean isEdge = (distSq >= (radius - 1) * (radius - 1));
        double angle = Math.atan2(z, x);
        int angleStep = (int) (angle / (Math.PI / 4));
        boolean isBattlementSpacing = (angleStep % 2 == 0);
        return isTop && isEdge && isBattlementSpacing;
    }

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
        if (semantic.slot() != null && semantic.slot().program() != null) {
            // slot program → style mapping (future)
        }
        return "MEDIEVAL_CLASSIC";
    }

    private SemanticPart determinePart(int y, int height, int radius, int x, int z) {
        if (y == 0) {
            return SemanticPart.WALL_BASE;
        }
        if (y >= height - 2) {
            return SemanticPart.WALL_ACCENT;
        }
        int distSq = x * x + z * z;
        if (distSq >= (radius - 1) * (radius - 1)) {
            return SemanticPart.WALL_ACCENT;
        }
        return SemanticPart.WALL;
    }
}
