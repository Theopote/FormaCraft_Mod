package com.formacraft.common.generator.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generator.ComponentGenerator;
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

/**
 * MassMainGenerator（主体体块生成器）
 * 
 * 生成建筑主体（店铺/住宅/厂房）
 * 使用 Palette 权重随机，支持不同风格
 */
public class MassMainGenerator implements ComponentGenerator {

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
        String styleProfile = getStyleProfile(semantic);
        Palette palette = PaletteLibrary.forStyle(styleProfile);

        // 检查 features（扩展关键词匹配，支持更多变体）
        boolean hasWindows = hasFeature(c, "windows", "window", "facade_windows", "lattice", "opening",
                                        "wooden_frames", "lattice_patterns", "symmetrical_placement");
        boolean hasRoof = hasFeature(c, "roof", "curved_roof", "sloped_roof", "hip", "gable", "gabled",
                                     "black_tile_roof", "upturned_eaves", "ridge_decorations");
        boolean hasDoors = hasFeature(c, "door", "doors", "entrance", "entry", "gateway",
                                      "double_doors", "stone_steps", "overhang_roof");
        boolean hasDecor = hasFeature(c, "decor", "decoration", "ornament", "carved", "carving", "lintel", "overhang",
                                      "wood_carvings", "intricate", "white_walls");
        boolean hasInterior = hasFeature(c, "interior", "rooms", "hollow", "courtyard", "central_courtyard",
                                         "inner_space", "empty_interior");
        boolean hasSteppedFacade = hasFeature(c, "stepped_facade", "stepped", "setback", "setbacks", 
                                               "进退", "进退关系", "立面", "facade_setback", "tiered");
        
        // 默认生成基础细节（即使没有匹配的 features）
        // 对于住宅类建筑，默认应该有门和窗
        boolean isResidential = semantic.slot() != null && 
                                "RESIDENTIAL".equals(semantic.slot().program());
        boolean shouldGenerateDefaultDetails = isResidential && !hasDoors && !hasWindows;
        
        // 如果应该生成默认细节，启用门和窗
        if (shouldGenerateDefaultDetails) {
            hasDoors = true;
            hasWindows = true;
        }

        // 计算每层的尺寸（如果有 stepped_facade，每层逐渐缩小）
        int[] layerWidths = new int[height];
        int[] layerDepths = new int[height];
        int[] layerXOffsets = new int[height];
        int[] layerZOffsets = new int[height];
        
        if (hasSteppedFacade && height > 4) {
            // 阶梯式立面：每层逐渐缩小
            int floors = Math.max(1, height / 4); // 假设每层约4格高
            int stepSize = Math.max(1, Math.min(2, width / (floors * 2))); // 每层缩小1-2格
            
            for (int y = 0; y < height; y++) {
                int floor = y / 4; // 当前楼层
                int step = Math.min(floor, floors - 1);
                layerWidths[y] = Math.max(3, width - step * stepSize * 2);
                layerDepths[y] = Math.max(3, depth - step * stepSize * 2);
                layerXOffsets[y] = step * stepSize;
                layerZOffsets[y] = step * stepSize;
            }
        } else {
            // 普通立面：所有层尺寸相同
            for (int y = 0; y < height; y++) {
                layerWidths[y] = width;
                layerDepths[y] = depth;
                layerXOffsets[y] = 0;
                layerZOffsets[y] = 0;
            }
        }

        // 生成矩形体块（基础结构）
        for (int y = 0; y < height; y++) {
            int currentWidth = layerWidths[y];
            int currentDepth = layerDepths[y];
            int xOffset = layerXOffsets[y];
            int zOffset = layerZOffsets[y];
            
            for (int x = 0; x < currentWidth; x++) {
                for (int z = 0; z < currentDepth; z++) {
                    // 转换为全局坐标（考虑 offset）
                    int globalX = x + xOffset;
                    int globalZ = z + zOffset;
                    
                    // 检查是否超出原始边界（不应该发生，但安全起见）
                    if (globalX >= width || globalZ >= depth) {
                        continue;
                    }
                    // 检查是否是内部空间（留空）
                    if (hasInterior && isInteriorSpace(globalX, globalZ, width, depth, y, height)) {
                        // 内部空间不放置方块（保持空气）
                        // 但底部可以放置地板
                        if (y == 0) {
                            SemanticPart part = SemanticPart.FLOOR;
                            String block = getBlockForPart(part, semantic, palette);
                            if (block == null || block.isEmpty()) {
                                block = getBlockForPart(SemanticPart.COURTYARD_FLOOR, semantic, palette);
                            }
                            if (block != null && !block.isEmpty()) {
                                out.add(new BlockPatch(
                                        BlockPatch.PLACE,
                                        rp.x() + x,
                                        rp.y() + y,
                                        rp.z() + z,
                                        block
                                ));
                            }
                        }
                        continue;
                    }

                    // 检查是否是窗户位置（使用全局坐标）
                    if (hasWindows && isWindowPosition(globalX, globalZ, width, depth, y, height)) {
                        // 生成窗户（使用 WINDOW 语义部位）
                        SemanticPart part = SemanticPart.WINDOW;
                        String block = palette.pick(part);
                        if (block == null || block.isEmpty()) {
                            block = "minecraft:glass";
                        }
                        out.add(new BlockPatch(
                                BlockPatch.PLACE,
                                rp.x() + globalX,
                                rp.y() + y,
                                rp.z() + globalZ,
                                block
                        ));
                        continue;
                    }

                    // 检查是否是门位置（使用全局坐标）
                    if (hasDoors && isDoorPosition(globalX, globalZ, width, depth, y)) {
                        // 生成门（使用 DOORWAY 语义部位）
                        SemanticPart part = SemanticPart.DOORWAY;
                        String block = getBlockForPart(part, semantic, palette);
                        if (block == null || block.isEmpty()) {
                            block = "minecraft:air"; // 门洞保持空气
                        }
                        // 门洞不放置方块，但可以放置门框
                        if (y == 0 || (globalX == width / 2 && globalZ == 0)) {
                            // 门框
                            part = SemanticPart.WALL_ACCENT;
                            block = getBlockForPart(part, semantic, palette);
                            out.add(new BlockPatch(
                                    BlockPatch.PLACE,
                                    rp.x() + globalX,
                                    rp.y() + y,
                                    rp.z() + globalZ,
                                    block
                            ));
                        }
                        continue;
                    }

                    // 检查是否是屋顶位置
                    if (hasRoof && y >= height - 1) {
                        // 生成屋顶（使用 ROOF_SURFACE 语义部位）
                        SemanticPart part = SemanticPart.ROOF_SURFACE;
                        String block = getBlockForPart(part, semantic, palette);
                        if (block == null || block.isEmpty()) {
                            part = SemanticPart.ROOF;
                            block = getBlockForPart(part, semantic, palette);
                        }
                        out.add(new BlockPatch(
                                BlockPatch.PLACE,
                                rp.x() + globalX,
                                rp.y() + y,
                                rp.z() + globalZ,
                                block
                        ));
                        continue;
                    }

                    // 检查是否是装饰位置（使用全局坐标）
                    if (hasDecor && isDecorPosition(globalX, globalZ, width, depth, y, height)) {
                        // 生成装饰（使用 DECOR 语义部位）
                        SemanticPart part = SemanticPart.DECOR;
                        String block = getBlockForPart(part, semantic, palette);
                        if (block == null || block.isEmpty()) {
                            part = SemanticPart.WALL_ACCENT;
                            block = getBlockForPart(part, semantic, palette);
                        }
                        out.add(new BlockPatch(
                                BlockPatch.PLACE,
                                rp.x() + globalX,
                                rp.y() + y,
                                rp.z() + globalZ,
                                block
                        ));
                        continue;
                    }

                    // 检查是否在当前层的边界内（stepped_facade 时，只生成当前层的方块）
                    boolean isInCurrentLayer = (x >= 0 && x < currentWidth && z >= 0 && z < currentDepth);
                    if (!isInCurrentLayer) {
                        continue; // 跳过不在当前层的方块
                    }

                    // 默认：根据位置确定 SemanticPart（使用全局坐标）
                    SemanticPart part = determinePart(y, height, width, depth, globalX, globalZ);
                    String block = getBlockForPart(part, semantic, palette);
                    
                    out.add(new BlockPatch(
                            BlockPatch.PLACE,
                            rp.x() + globalX,
                            rp.y() + y,
                            rp.z() + globalZ,
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
        // 窗户通常不在角落
        boolean isNotCorner = (x > 0 && x < width - 1);
        // 窗户通常间隔放置
        boolean isWindowSpacing = (x % 3 == 1 || x % 3 == 2);
        return isFacade && isMiddleHeight && isNotCorner && isWindowSpacing;
    }

    /**
     * 检查是否是门位置
     */
    private boolean isDoorPosition(int x, int z, int width, int depth, int y) {
        // 门通常在正面（z=0），居中，只在底部
        boolean isFront = (z == 0);
        boolean isCenter = (x >= width / 2 - 1 && x <= width / 2 + 1);
        boolean isBottom = (y < 3);
        return isFront && isCenter && isBottom;
    }

    /**
     * 检查是否是装饰位置
     */
    private boolean isDecorPosition(int x, int z, int width, int depth, int y, int height) {
        // 装饰通常在边缘、顶部、角落
        boolean isEdge = (x == 0 || x == width - 1 || z == 0 || z == depth - 1);
        boolean isTop = (y >= height - 2);
        boolean isCorner = ((x == 0 || x == width - 1) && (z == 0 || z == depth - 1));
        return (isEdge || isTop || isCorner) && (y > 0);
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
        // 1. 优先使用 SemanticComponent 中的 styleProfile
        if (semantic.styleProfile() != null && !semantic.styleProfile().isBlank()) {
            String profile = semantic.styleProfile().trim();
            // 映射 LLM 返回的风格名称到系统支持的风格
            String upper = profile.toUpperCase();
            if (upper.contains("CHINESE") && (upper.contains("TRADITIONAL") || upper.contains("TRAD"))) {
                return "HUI_STYLE_VILLA"; // 映射到徽派风格
            }
            if (upper.contains("CHINESE") || upper.contains("HUI")) {
                return "HUI_STYLE_VILLA";
            }
            return profile; // 其他风格直接返回
        }

        // 2. 尝试从 Component 的 features 推断风格
        Component c = semantic.source();
        if (c != null && c.features() != null) {
            for (String feature : c.features()) {
                if (feature != null) {
                    String lower = feature.toLowerCase();
                    if (lower.contains("hui") || lower.contains("chinese") || 
                        lower.contains("white_walls") || lower.contains("black_tile")) {
                        return "HUI_STYLE_VILLA";
                    }
                }
            }
        }

        // 3. 根据 program 映射到风格
        if (semantic.slot() != null && semantic.slot().program() != null) {
            String programName = semantic.slot().program();
            if ("COMMERCIAL".equals(programName)) {
                return "MODERN_CLASSIC";
            } else if ("RESIDENTIAL".equals(programName)) {
                return "MEDIEVAL_CLASSIC";
            }
        }

        // 4. 默认返回
        return "MEDIEVAL_CLASSIC";
    }

    /**
     * 获取方块（优先使用动态解析，回退到传统 Palette）
     */
    private String getBlockForPart(SemanticPart part, SemanticComponent semantic, Palette palette) {
        // 1. 优先使用动态解析（如果 LlmPlan 有 style_attributes）
        if (semantic.styleAttributes() != null) {
            String block = DynamicPaletteResolver.resolve(part, semantic.styleAttributes());
            if (block != null && !block.isEmpty()) {
                return block;
            }
        }
        
        // 2. 回退到传统 Palette
        if (palette != null) {
            String block = palette.pick(part);
            if (block != null && !block.isEmpty()) {
                return block;
            }
        }
        
        // 3. 默认方块
        return getDefaultBlock(part);
    }
    
    /**
     * 获取默认方块
     */
    private String getDefaultBlock(SemanticPart part) {
        return switch (part) {
            case WALL, WALL_BASE -> "minecraft:stone_bricks";
            case ROOF, ROOF_SURFACE -> "minecraft:spruce_planks";
            case FLOOR, COURTYARD_FLOOR -> "minecraft:stone_bricks";
            case DECOR -> "minecraft:stone_brick_slab";
            case WINDOW -> "minecraft:glass";
            case DOORWAY -> "minecraft:air";
            default -> "minecraft:stone";
        };
    }

    /**
     * 检查是否是内部空间（需要留空）
     */
    private boolean isInteriorSpace(int x, int z, int width, int depth, int y, int height) {
        // 留出边缘（墙体），内部留空
        int wallThickness = 1;
        boolean isInteriorX = x >= wallThickness && x < width - wallThickness;
        boolean isInteriorZ = z >= wallThickness && z < depth - wallThickness;
        
        // 内部空间（不包括屋顶层）
        boolean isInteriorY = y < height - 1;
        
        return isInteriorX && isInteriorZ && isInteriorY;
    }

    private SemanticPart determinePart(int y, int height, int width, int depth, int x, int z) {
        // 基础部分
        if (y == 0) {
            return SemanticPart.WALL_BASE;
        }
        
        // 顶部
        if (y >= height - 1) {
            return SemanticPart.WALL_ACCENT;
        }
        
        // 边缘（外墙）
        boolean isEdge = (x == 0 || x == width - 1 || z == 0 || z == depth - 1);
        if (isEdge) {
            return SemanticPart.WALL_ACCENT;
        }
        
        // 内部（填充）
        return SemanticPart.WALL;
    }
}

