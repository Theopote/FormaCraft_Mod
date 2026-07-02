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
 * WallComponentGenerator（构件层墙体生成器 v2）
 *
 * 使用 Palette 权重随机生成矩形墙体
 */
public class WallComponentGenerator implements ComponentGenerator {

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

        boolean hasWindows = hasFeature(c, "windows", "window", "arrow_slits");
        boolean hasDecor = hasFeature(c, "decor", "decoration", "ornament", "carved");
        boolean hasBattlements = hasFeature(c, "battlements", "battlement", "crenelation");

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
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

    private boolean isWindowPosition(int x, int z, int width, int depth, int y, int height) {
        boolean isFacade = (z == 0 || z == depth - 1);
        boolean isMiddleHeight = (y > 0 && y < height - 1);
        boolean isNotCorner = (x > 0 && x < width - 1);
        boolean isWindowSpacing = (x % 4 == 2 || x % 4 == 3);
        return isFacade && isMiddleHeight && isNotCorner && isWindowSpacing;
    }

    private boolean isDecorPosition(int x, int z, int width, int depth, int y, int height) {
        boolean isEdge = (x == 0 || x == width - 1 || z == 0 || z == depth - 1);
        boolean isTop = (y >= height - 2);
        boolean isCorner = ((x == 0 || x == width - 1) && (z == 0 || z == depth - 1));
        boolean isDecorSpacing = (x % 3 == 0 || z % 3 == 0);
        return (isEdge || isTop || isCorner) && isDecorSpacing && (y > 0);
    }

    private boolean isBattlementPosition(int x, int z, int width, int depth, int y, int height) {
        boolean isTop = (y >= height - 1);
        boolean isEdge = (x == 0 || x == width - 1 || z == 0 || z == depth - 1);
        boolean isBattlementSpacing = (x % 2 == 0 || z % 2 == 0);
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

    private SemanticPart determinePart(int y, int height, int width, int depth, int x, int z) {
        if (y == 0) {
            return SemanticPart.WALL_BASE;
        }
        if (y >= height - 1) {
            return SemanticPart.WALL_ACCENT;
        }
        if (x == 0 || x == width - 1 || z == 0 || z == depth - 1) {
            return SemanticPart.WALL_ACCENT;
        }
        return SemanticPart.WALL;
    }

    private String getStyleProfile(SemanticComponent semantic) {
        if (semantic != null && semantic.styleProfile() != null && !semantic.styleProfile().isBlank()) {
            String profile = semantic.styleProfile().trim();
            String upper = profile.toUpperCase();
            if (upper.contains("CHINESE") && (upper.contains("GREAT") || upper.contains("WALL"))) {
                return "MEDIEVAL_CLASSIC";
            }
            if (upper.contains("CHINESE_ROYAL") || upper.contains("CHINESE_IMPERIAL") || upper.contains("CHINESE_PALACE")
                    || (upper.contains("CHINESE") && (upper.contains("ROYAL") || upper.contains("IMPERIAL") || upper.contains("PALACE")))) {
                return "CHINESE_ROYAL";
            }
            if (upper.contains("CHINESE") || upper.contains("HUI")) {
                return "HUI_STYLE_VILLA";
            }
            return profile;
        }

        Component c = semantic != null ? semantic.source() : null;
        if (c != null && c.features() != null) {
            for (String feature : c.features()) {
                if (feature == null) continue;
                String lower = feature.toLowerCase();
                if (lower.contains("imperial") || lower.contains("royal") || lower.contains("palace")) {
                    return "CHINESE_ROYAL";
                }
                if (lower.contains("chinese") || lower.contains("中式") || lower.contains("great_wall")) {
                    return "MEDIEVAL_CLASSIC";
                }
                if (lower.contains("medieval") || lower.contains("gothic") || lower.contains("stone_brick")) {
                    return "MEDIEVAL_CLASSIC";
                }
                if (lower.contains("modern") || lower.contains("contemporary")) {
                    return "MODERN";
                }
            }
        }

        return "MEDIEVAL_CLASSIC";
    }
}
