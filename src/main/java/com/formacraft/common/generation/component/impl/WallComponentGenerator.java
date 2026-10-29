package com.formacraft.common.generation.component.impl;

import com.formacraft.FormacraftMod;
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
import java.util.Map;
import java.util.Set;

/**
 * WallComponentGenerator（构件层墙体生成器 v2）
 * <p>
 * 使用 Palette 权重随机生成矩形墙体
 */
public class WallComponentGenerator implements ComponentGenerator {

    @Override
    public List<BlockPatch> generate(SemanticComponent semantic) {
        List<BlockPatch> out = new ArrayList<>();

        Component c = semantic.source();
        if (c == null || c.dimensions() == null || c.relativePosition() == null) {
            FormacraftMod.LOGGER.warn(
                    "WallComponentGenerator: skipping wall — missing source/dimensions/relativePosition (type={})",
                    semantic.source() != null ? semantic.source().componentType() : "unknown"
            );
            return out;
        }

        Dimensions d = c.dimensions();
        Vec3i rp = c.relativePosition();

        int width = Math.max(1, d.width());
        int depth = Math.max(1, d.depth());
        int height = Math.max(1, d.height());

        String explicitWall = getParamString(c.params(),
                "wall_block", "wallBlock", "wall_material", "wallMaterial", "material", "block");
        // Linear fence / bars / railing: place a 1-high, 1-thick run — never solid palette fill or foundation.
        boolean fenceIntent = isFenceLikeBlock(explicitWall)
                || hasFeature(c, "fence", "fencing", "railing", "railings", "paling", "picket");
        if (fenceIntent) {
            String fenceBlock = isFenceLikeBlock(explicitWall) ? explicitWall : "minecraft:oak_fence";
            return generateFenceLine(rp, width, depth, height, fenceBlock, c.params());
        }

        String anchorMode = getParamString(c.params(), "anchor_mode", "anchorMode");
        boolean useCornerAnchor = anchorMode != null && anchorMode.toLowerCase(java.util.Locale.ROOT).contains("corner");
        if (!useCornerAnchor) {
            rp = new Vec3i(rp.x() - width / 2, rp.y(), rp.z() - depth / 2);
        }

        String styleProfile = getStyleProfile(semantic);
        Palette palette = PaletteLibrary.forStyle(styleProfile);

        boolean hasWindows = hasFeature(c, "windows", "window", "arrow_slits");
        boolean hasDecor = hasFeature(c, "decor", "decoration", "ornament", "carved");
        boolean hasBattlements = hasFeature(c, "battlements", "battlement", "crenelation");
        boolean enclosure = "curtain_wall".equals(getParamString(c.params(), "wall_type"));
        int thickness = Math.max(1, com.formacraft.common.generation.component.util.ComponentParamParsers
                .intParam(c.params(), 1, "thickness", "wall_thickness"));
        Map<?,?> gate = c.params() != null && c.params().get("gate_opening") instanceof Map<?,?> g ? g : Map.of();
        String gateSide = String.valueOf(gate.get("side")).toLowerCase(java.util.Locale.ROOT);
        int gateWidth = gate.get("width") instanceof Number n ? n.intValue() : 0;
        int gateHeight = gate.get("height") instanceof Number n ? n.intValue() : 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    if (enclosure) {
                        if (x >= thickness && x < width-thickness && z >= thickness && z < depth-thickness) continue;
                        boolean onGateSide = switch (gateSide) {
                            case "south" -> z >= depth-thickness;
                            case "north" -> z < thickness;
                            case "east" -> x >= width-thickness;
                            case "west" -> x < thickness;
                            default -> false;
                        };
                        int axis = Set.of("east","west").contains(gateSide) ? z : x;
                        int span = Set.of("east","west").contains(gateSide) ? depth : width;
                        if (onGateSide && y < gateHeight && axis >= (span-gateWidth)/2 && axis < (span+gateWidth)/2) continue;
                    }
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
                    String block = (explicitWall != null && !explicitWall.isBlank() && y > 0 && y < height - 1)
                            ? normalizeBlockId(explicitWall)
                            : palette.pick(part);
                    if (block == null || block.isBlank()) {
                        block = explicitWall != null ? normalizeBlockId(explicitWall) : "minecraft:stone_bricks";
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

        return out;
    }

    /** Single-file fence/bars run along the longer horizontal axis. */
    private static List<BlockPatch> generateFenceLine(
            Vec3i rp, int width, int depth, int height, String blockId, Map<String, Object> params) {
        String block = normalizeBlockId(blockId);
        int h = Math.max(1, Math.min(2, height)); // fences are 1 (rarely 2) tall
        boolean alongX = width >= depth;
        int length = Math.max(1, alongX ? width : depth);
        String anchorMode = getParamString(params, "anchor_mode", "anchorMode");
        boolean corner = anchorMode != null && anchorMode.toLowerCase(java.util.Locale.ROOT).contains("corner");
        int x0 = rp.x();
        int z0 = rp.z();
        if (!corner) {
            if (alongX) {
                x0 = rp.x() - length / 2;
            } else {
                z0 = rp.z() - length / 2;
            }
        }
        List<BlockPatch> out = new ArrayList<>(length * h);
        for (int i = 0; i < length; i++) {
            int x = alongX ? x0 + i : x0;
            int z = alongX ? z0 : z0 + i;
            for (int y = 0; y < h; y++) {
                out.add(new BlockPatch(BlockPatch.PLACE, x, rp.y() + y, z, block));
            }
        }
        return out;
    }

    private static boolean isFenceLikeBlock(String id) {
        if (id == null || id.isBlank()) return false;
        String lower = id.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("fence_gate")) return false;
        return lower.contains("fence") || lower.contains("iron_bars") || lower.endsWith("_bars");
    }

    private static String normalizeBlockId(String id) {
        if (id == null || id.isBlank()) return "minecraft:oak_fence";
        String s = id.trim();
        if (!s.contains(":")) s = "minecraft:" + s;
        return s;
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

    private static String getParamString(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) return null;
        for (String key : keys) {
            if (key == null) continue;
            Object v = params.get(key);
            if (v != null && !v.toString().isBlank()) {
                return v.toString().trim();
            }
        }
        return null;
    }
}
