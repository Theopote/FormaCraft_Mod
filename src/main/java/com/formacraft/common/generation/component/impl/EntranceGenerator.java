package com.formacraft.common.generation.component.impl;

import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.ComponentGenerator;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.palette.component.Palette;
import com.formacraft.common.palette.component.PaletteLibrary;
import com.formacraft.common.palette.dynamic.DynamicPaletteResolver;
import com.formacraft.common.semantic.SemanticPart;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * EntranceGenerator（入口生成器）
 * <p>
 * 生成门廊/入口结构：默认仅挖门洞并放置门框/楣/棚，保留主体墙体。
 */
public class EntranceGenerator implements ComponentGenerator {

    @Override
    public List<BlockPatch> generate(SemanticComponent semantic) {
        if (com.formacraft.common.style.ExplicitDesignPolicy.entranceDisabled(semantic.source())) return List.of();
        List<BlockPatch> out = new ArrayList<>();

        Component c = semantic.source();
        if (c == null || c.dimensions() == null || c.relativePosition() == null) {
            return out;
        }

        Dimensions d = c.dimensions();
        Vec3i rp = com.formacraft.common.generation.component.util.ComponentFootprintUtil.resolveLegacyCornerOrigin(c);

        int width = Math.max(1, d.width());
        int depth = Math.max(1, d.depth());
        int height = Math.max(1, d.height());
        Map<String, Object> params = c.params();
        com.formacraft.common.llm.dto.GlobalConstraints.Facing facing =
                (semantic.slot() != null && semantic.slot().facing() != null)
                        ? semantic.slot().facing()
                        : com.formacraft.common.llm.dto.GlobalConstraints.Facing.SOUTH;
        if (params != null && params.get("facing") != null)
            facing = com.formacraft.common.llm.dto.GlobalConstraints.Facing.valueOf(String.valueOf(params.get("facing")));

        String styleProfile = getStyleProfile(semantic);
        Palette palette = PaletteLibrary.forStyle(styleProfile);

        boolean hasOverhang = hasFeature(c, "overhang", "canopy", "awning");
        boolean hasDecorativeLintel = hasFeature(c, "decorative_lintel", "lintel", "carving", "ornament", "decorative");
        boolean hasArch = hasFeature(c, "arched", "arch", "archway");
        boolean replaceWallRegion = getParamBoolean(params, "replace_wall_region", "replaceWallRegion");

        boolean explicitDoorWidth = params != null
                && (params.containsKey("door_width") || params.containsKey("doorWidth"));
        int requestedDoorWidth = ComponentParamParsers.intParam(params, Math.max(2, width - 2), "door_width", "doorWidth");
        int doorHeight = ComponentParamParsers.intParam(params, Math.max(2, height - 1), "door_height", "doorHeight");
        // Thin shell openings encode the wall normal in dimensions (1×N or N×1). Prefer that over
        // slot/global facing so a south-wall service opening is not measured along depth=1.
        int openingSpan;
        if (width == 1 && depth > 1) {
            openingSpan = depth;
            if (facing != com.formacraft.common.llm.dto.GlobalConstraints.Facing.EAST
                    && facing != com.formacraft.common.llm.dto.GlobalConstraints.Facing.WEST) {
                facing = com.formacraft.common.llm.dto.GlobalConstraints.Facing.EAST;
            }
        } else if (depth == 1 && width > 1) {
            openingSpan = width;
            if (facing != com.formacraft.common.llm.dto.GlobalConstraints.Facing.NORTH
                    && facing != com.formacraft.common.llm.dto.GlobalConstraints.Facing.SOUTH) {
                facing = com.formacraft.common.llm.dto.GlobalConstraints.Facing.SOUTH;
            }
        } else {
            openingSpan = facing == com.formacraft.common.llm.dto.GlobalConstraints.Facing.EAST
                    || facing == com.formacraft.common.llm.dto.GlobalConstraints.Facing.WEST ? depth : width;
        }

        int doorWidth;
        if (explicitDoorWidth) {
            // Clamp oversized LLM door_width into the opening (need ≥1 jamb cell).
            if (openingSpan < 2) {
                throw new IllegalArgumentException(
                        "E_ENTRANCE_DOOR_WIDTH: openingSpan=" + openingSpan + " too narrow for entrance");
            }
            doorWidth = Math.max(1, Math.min(requestedDoorWidth, openingSpan - 1));
            if (hasArch && doorWidth < 2) {
                throw new IllegalArgumentException(
                        "E_ENTRANCE_DOOR_WIDTH: arched entrance requires door_width>=2, got=" + doorWidth);
            }
        } else {
            int auto = Math.max(2, Math.min(requestedDoorWidth, Math.max(1, openingSpan - 1)));
            if (openingSpan < 3) {
                throw new IllegalArgumentException(
                        "E_ENTRANCE_DOOR_WIDTH: openingSpan=" + openingSpan + " too narrow for entrance");
            }
            if (hasArch) auto = Math.max(3, auto);
            doorWidth = Math.min(auto, openingSpan - 1);
        }
        doorHeight = Math.max(2, Math.min(doorHeight, height));
        int canopyDepth = ComponentParamParsers.intParam(params, 0, "canopy_depth", "canopyDepth");
        if (canopyDepth > 0) {
            hasOverhang = true;
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    if (isDoorOpening(x, z, y, width, depth, doorWidth, doorHeight, facing, hasArch)) {
                        out.add(new BlockPatch(
                                BlockPatch.REMOVE,
                                rp.x() + x,
                                rp.y() + y,
                                rp.z() + z,
                                "minecraft:air"
                        ));
                        continue;
                    }

                    boolean frame = isDoorFrame(x, z, y, width, depth, doorWidth, doorHeight, facing);
                    boolean lintelDecor = hasDecorativeLintel && y >= height - 1
                            && isAlongDoorBay(x, z, width, depth, doorWidth, facing);
                    boolean canopy = hasOverhang && y == height - 1
                            && isCanopyEdge(x, z, width, depth, facing, canopyDepth);

                    if (!frame && !lintelDecor && !canopy && !replaceWallRegion) {
                        continue; // Preserve host wall material.
                    }

                    SemanticPart part;
                    if (lintelDecor) {
                        part = SemanticPart.DECOR;
                    } else if (canopy || (hasOverhang && y >= height - 1 && frame)) {
                        part = SemanticPart.ROOF_SURFACE;
                    } else {
                        part = SemanticPart.WALL_ACCENT;
                    }
                    String block = getBlockForPart(part, semantic, palette);

                    out.add(new BlockPatch(
                            BlockPatch.PLACE,
                            rp.x() + x,
                            rp.y() + y,
                            rp.z() + z,
                            block
                    ));

                    if (canopy) {
                        SemanticPart canopyPart = SemanticPart.ROOF_SURFACE;
                        String canopyBlock = getBlockForPart(canopyPart, semantic, palette);
                        out.add(new BlockPatch(
                                BlockPatch.PLACE,
                                rp.x() + x,
                                rp.y() + y,
                                rp.z() + z,
                                canopyBlock
                        ));
                    }
                }
            }
        }

        return out;
    }

    private String getStyleProfile(SemanticComponent semantic) {
        if (semantic != null && semantic.styleProfile() != null && !semantic.styleProfile().isBlank()) {
            String profile = semantic.styleProfile().trim();
            String upper = profile.toUpperCase();
            if (upper.contains("GOTHIC")) {
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
            if (lower.startsWith("component_request:") || lower.startsWith("group_request:")) continue;
                if (lower.contains("gothic") || lower.contains("pointed") || lower.contains("flying")) {
                    return "MEDIEVAL_CLASSIC";
                }
                if (lower.contains("imperial") || lower.contains("royal") || lower.contains("palace")) {
                    return "CHINESE_ROYAL";
                }
                if (lower.contains("chinese") || lower.contains("中式")) {
                    return "HUI_STYLE_VILLA";
                }
            }
        }

        return "MEDIEVAL_CLASSIC";
    }

    private String getBlockForPart(SemanticPart part, SemanticComponent semantic, Palette palette) {
        if (semantic.styleAttributes() != null) {
            String block = DynamicPaletteResolver.resolve(part, semantic.styleAttributes());
            if (block != null && !block.isEmpty()) {
                return block;
            }
        }

        Component c = semantic.source();
        if (c != null && c.features() != null) {
            String block = extractBlockFromFeatures(part, c.features());
            if (block != null && !block.isEmpty()) {
                return block;
            }
        }

        if (palette != null) {
            String block = palette.pick(part);
            if (block != null && !block.isEmpty()) {
                return block;
            }
        }

        return getDefaultBlock(part);
    }

    private String extractBlockFromFeatures(SemanticPart part, List<String> features) {
        if (features == null || features.isEmpty()) {
            return null;
        }

        for (String feature : features) {
            if (feature == null || feature.isBlank()) {
                continue;
            }

            String lower = feature.toLowerCase().trim();

            if (lower.contains("white_concrete") || lower.contains("white concrete")) {
                if (part == SemanticPart.WALL || part == SemanticPart.WALL_BASE || part == SemanticPart.WALL_ACCENT) {
                    return "minecraft:white_concrete";
                }
            }
            if (lower.contains("gray_concrete") || lower.contains("grey_concrete") || lower.contains("gray concrete")) {
                if (part == SemanticPart.WALL || part == SemanticPart.WALL_BASE || part == SemanticPart.WALL_ACCENT) {
                    return "minecraft:gray_concrete";
                }
            }
            if (lower.contains("metal") || lower.contains("steel") || lower.contains("iron")) {
                if (part == SemanticPart.DECOR || part == SemanticPart.WALL_ACCENT) {
                    return "minecraft:iron_block";
                }
            }
        }

        return null;
    }

    private String getDefaultBlock(SemanticPart part) {
        return switch (part) {
            case WALL, WALL_BASE, WALL_ACCENT -> "minecraft:stone_bricks";
            case ROOF_SURFACE -> "minecraft:spruce_planks";
            case DECOR -> "minecraft:stone_brick_slab";
            default -> "minecraft:stone";
        };
    }

    private boolean hasFeature(Component c, String... keywords) {
        if (c.features() == null) return false;
        for (String feature : c.features()) {
            if (feature == null) continue;
            String lower = feature.toLowerCase();
            if (lower.startsWith("component_request:") || lower.startsWith("group_request:")) continue;
            for (String keyword : keywords) {
                if (lower.contains(keyword.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isDoorOpening(int x, int z, int y, int width, int depth, int doorWidth, int doorHeight,
                                  com.formacraft.common.llm.dto.GlobalConstraints.Facing facing, boolean arch) {
        if (y >= doorHeight) {
            return false;
        }
        int axis = (facing == com.formacraft.common.llm.dto.GlobalConstraints.Facing.EAST
                || facing == com.formacraft.common.llm.dto.GlobalConstraints.Facing.WEST)
                ? z : x;
        int axisMax = (facing == com.formacraft.common.llm.dto.GlobalConstraints.Facing.EAST
                || facing == com.formacraft.common.llm.dto.GlobalConstraints.Facing.WEST)
                ? depth : width;
        int center = axisMax / 2;
        int half = doorWidth / 2;
        boolean inWidth = axis >= center - half && axis <= center + (doorWidth - 1 - half);

        if (!inWidth) {
            return false;
        }

        if (!arch) {
            return true;
        }

        int archTop = doorHeight - 1;
        if (y < archTop) {
            return true;
        }
        int dist = Math.abs(axis - center);
        return dist <= half - 1;
    }

    /** Jambs beside the opening and the lintel course directly above it. */
    private boolean isDoorFrame(int x, int z, int y, int width, int depth, int doorWidth, int doorHeight,
                                com.formacraft.common.llm.dto.GlobalConstraints.Facing facing) {
        int axis = (facing == com.formacraft.common.llm.dto.GlobalConstraints.Facing.EAST
                || facing == com.formacraft.common.llm.dto.GlobalConstraints.Facing.WEST)
                ? z : x;
        int axisMax = (facing == com.formacraft.common.llm.dto.GlobalConstraints.Facing.EAST
                || facing == com.formacraft.common.llm.dto.GlobalConstraints.Facing.WEST)
                ? depth : width;
        int center = axisMax / 2;
        int half = doorWidth / 2;
        int doorMin = center - half;
        int doorMax = center + (doorWidth - 1 - half);
        if (y == doorHeight && axis >= doorMin - 1 && axis <= doorMax + 1) {
            return true;
        }
        if (y < doorHeight && (axis == doorMin - 1 || axis == doorMax + 1)) {
            return true;
        }
        return false;
    }

    private boolean isAlongDoorBay(int x, int z, int width, int depth, int doorWidth,
                                   com.formacraft.common.llm.dto.GlobalConstraints.Facing facing) {
        int axis = (facing == com.formacraft.common.llm.dto.GlobalConstraints.Facing.EAST
                || facing == com.formacraft.common.llm.dto.GlobalConstraints.Facing.WEST)
                ? z : x;
        int axisMax = (facing == com.formacraft.common.llm.dto.GlobalConstraints.Facing.EAST
                || facing == com.formacraft.common.llm.dto.GlobalConstraints.Facing.WEST)
                ? depth : width;
        int center = axisMax / 2;
        int half = doorWidth / 2;
        int doorMin = center - half;
        int doorMax = center + (doorWidth - 1 - half);
        return axis >= doorMin - 1 && axis <= doorMax + 1;
    }

    private boolean isCanopyEdge(int x, int z, int width, int depth,
                                 com.formacraft.common.llm.dto.GlobalConstraints.Facing facing, int canopyDepth) {
        if (canopyDepth <= 0) {
            return false;
        }
        return switch (facing) {
            case NORTH -> z >= depth - Math.min(canopyDepth, depth);
            case EAST -> x < Math.min(canopyDepth, width);
            case WEST -> x >= width - Math.min(canopyDepth, width);
            case SOUTH -> z < Math.min(canopyDepth, depth);
        };
    }

    private static boolean getParamBoolean(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) return false;
        for (String key : keys) {
            if (key == null) continue;
            Object v = params.get(key);
            if (v == null) continue;
            if (v instanceof Boolean b) return b;
            return "true".equalsIgnoreCase(String.valueOf(v).trim());
        }
        return false;
    }
}
