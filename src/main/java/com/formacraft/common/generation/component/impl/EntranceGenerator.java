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
 * 生成门廊/入口结构
 */
public class EntranceGenerator implements ComponentGenerator {

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
        Map<String, Object> params = c.params();

        String styleProfile = getStyleProfile(semantic);
        Palette palette = PaletteLibrary.forStyle(styleProfile);

        // 检查 features
        boolean hasOverhang = hasFeature(c, "overhang", "canopy", "awning");
        boolean hasDecorativeLintel = hasFeature(c, "decorative_lintel", "lintel", "carving", "ornament", "decorative");
        boolean hasArch = hasFeature(c, "arched", "arch", "archway");

        int doorWidth = ComponentParamParsers.intParam(params, Math.max(2, width - 2), "door_width", "doorWidth");
        int doorHeight = ComponentParamParsers.intParam(params, Math.max(2, height - 1), "door_height", "doorHeight");
        doorWidth = Math.max(1, Math.min(doorWidth, Math.max(1, width - 1)));
        doorHeight = Math.max(2, Math.min(doorHeight, height));
        int canopyDepth = ComponentParamParsers.intParam(params, 0, "canopy_depth", "canopyDepth");
        if (canopyDepth > 0) {
            hasOverhang = true;
        }
        com.formacraft.common.llm.dto.GlobalConstraints.Facing facing =
                (semantic.slot() != null && semantic.slot().facing() != null)
                        ? semantic.slot().facing()
                        : com.formacraft.common.llm.dto.GlobalConstraints.Facing.SOUTH;
        
        // 生成入口结构（门洞 + 门框）
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
                    
                    // 门框（边缘部分）
                    SemanticPart part;
                    // 顶部可以是门楣/过梁
                    if (y >= height - 1 && hasDecorativeLintel) {
                        part = SemanticPart.DECOR;
                    } else if (y >= height - 1 && hasOverhang) {
                        part = SemanticPart.ROOF_SURFACE; // 门廊顶
                    } else {
                        part = SemanticPart.WALL_ACCENT; // 门框
                    }
                    String block = getBlockForPart(part, semantic, palette);
                    
                    out.add(new BlockPatch(
                            BlockPatch.PLACE,
                            rp.x() + x,
                            rp.y() + y,
                            rp.z() + z,
                            block
                    ));

                    if (hasOverhang && y == height - 1 && isCanopyEdge(x, z, width, depth, facing, canopyDepth)) {
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
        // 1. 优先使用 SemanticComponent 中的 styleProfile
        if (semantic != null && semantic.styleProfile() != null && !semantic.styleProfile().isBlank()) {
            String profile = semantic.styleProfile().trim();
            // 映射 LLM 返回的风格名称到系统支持的风格
            String upper = profile.toUpperCase();
            if (upper.contains("GOTHIC")) {
                return "MEDIEVAL_CLASSIC"; // 哥特式使用中世纪风格
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

        // 2. 尝试从 Component 的 features 推断风格
        Component c = semantic != null ? semantic.source() : null;
        if (c != null && c.features() != null) {
            for (String feature : c.features()) {
                if (feature == null) continue;
                String lower = feature.toLowerCase();
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

        // 3. 默认
        return "MEDIEVAL_CLASSIC";
    }
    
    /**
     * 获取方块（优先使用动态解析和 features，回退到传统 Palette）
     */
    private String getBlockForPart(SemanticPart part, SemanticComponent semantic, Palette palette) {
        // 1. 优先使用动态解析（如果 LlmPlan 有 style_attributes）
        if (semantic.styleAttributes() != null) {
            String block = DynamicPaletteResolver.resolve(part, semantic.styleAttributes());
            if (block != null && !block.isEmpty()) {
                return block;
            }
        }
        
        // 2. 尝试从 features 中提取材质信息
        Component c = semantic.source();
        if (c != null && c.features() != null) {
            String block = extractBlockFromFeatures(part, c.features());
            if (block != null && !block.isEmpty()) {
                return block;
            }
        }
        
        // 3. 回退到传统 Palette
        if (palette != null) {
            String block = palette.pick(part);
            if (block != null && !block.isEmpty()) {
                return block;
            }
        }
        
        // 4. 默认方块
        return getDefaultBlock(part);
    }
    
    /**
     * 从 features 中提取材质信息
     */
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
    
    /**
     * 获取默认方块
     */
    private String getDefaultBlock(SemanticPart part) {
        return switch (part) {
            case WALL, WALL_BASE, WALL_ACCENT -> "minecraft:stone_bricks";
            case ROOF_SURFACE -> "minecraft:spruce_planks";
            case DECOR -> "minecraft:stone_brick_slab";
            default -> "minecraft:stone";
        };
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

        boolean onPlane = switch (facing) {
            case NORTH -> z == depth - 1;
            case EAST -> x == 0;
            case WEST -> x == width - 1;
            case SOUTH -> z == 0;
        };

        if (!onPlane || !inWidth) {
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
}

