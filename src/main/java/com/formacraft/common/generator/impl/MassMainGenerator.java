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
import com.formacraft.FormacraftMod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MassMainGenerator（主体体块生成器）
 * 
 * 生成建筑主体（店铺/住宅/厂房）
 * 使用 Palette 权重随机，支持不同风格
 */
public class MassMainGenerator implements ComponentGenerator {

    private enum FootprintShape {
        RECTANGLE,
        CIRCLE,
        ROUNDED_RECT
    }

    private static final class MassConfig {
        private final int offsetX;
        private final int offsetY;
        private final int offsetZ;
        private final int width;
        private final int depth;
        private final int height;
        private final FootprintShape shape;
        private final int cornerRadius;

        private MassConfig(int offsetX, int offsetY, int offsetZ, int width, int depth, int height,
                           FootprintShape shape, int cornerRadius) {
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
            this.width = width;
            this.depth = depth;
            this.height = height;
            this.shape = shape;
            this.cornerRadius = cornerRadius;
        }
    }

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

        FootprintShape baseShape = resolveShape(c, semantic);
        int cornerRadius = resolveCornerRadius(params, width, depth, baseShape);
        List<MassConfig> massConfigs = resolveMasses(params, width, depth, height, baseShape, cornerRadius);
        
        // 尺寸规范化：确保高度至少3米（3格）
        // 如果用户指定了具体高度，使用用户的要求
        int userFloorHeight = getParamInt(params, 0, "floor_height", "floorHeight");
        if (userFloorHeight <= 0) {
            userFloorHeight = com.formacraft.common.generator.util.ProportionalFacadeCalculator
                    .extractFloorHeightFromFeatures(c.features());
        }
        if (userFloorHeight > 0) {
            // 用户指定了每层高度，验证总高度是否合理
            int minTotalHeight = userFloorHeight * 1; // 至少1层
            if (height < minTotalHeight) {
                FormacraftMod.LOGGER.warn("MassMainGenerator: total height {} is less than minimum {} (floor height: {})", 
                        height, minTotalHeight, userFloorHeight);
                // 调整总高度以匹配用户要求
                height = minTotalHeight;
            }
        } else {
            // 用户未指定，确保总高度至少3米
            height = Math.max(3, height);
        }
        
        // 验证尺寸合理性
        if (!com.formacraft.common.generator.util.ProportionalFacadeCalculator.validateDimensions(width, depth, height)) {
            FormacraftMod.LOGGER.warn("MassMainGenerator: invalid dimensions {}x{}x{}, using defaults", width, depth, height);
            width = Math.max(3, width);
            depth = Math.max(3, depth);
            height = Math.max(3, height);
        }

        // 获取风格
        String styleProfile = getStyleProfile(semantic);
        Palette palette = PaletteLibrary.forStyle(styleProfile);
        
        // 记录风格信息（用于调试）
        FormacraftMod.LOGGER.info("MassMainGenerator: styleProfile={}, hasStyleAttributes={}, features={}", 
                styleProfile, 
                semantic.styleAttributes() != null,
                c.features() != null ? c.features().size() : 0);

        // 检查 features（扩展关键词匹配，支持更多变体）
        boolean hasWindows = hasFeature(c, "windows", "window", "facade_windows", "lattice", "opening",
                                        "wooden_frames", "lattice_patterns", "symmetrical_placement",
                                        "stained_glass", "stained_glass_windows", "glass", "glass_panels",
                                        "rose_window", "pointed_arches"); // 哥特式特征
        boolean hasRoof = hasFeature(c, "roof", "curved_roof", "sloped_roof", "hip", "gable", "gabled",
                                     "black_tile_roof", "upturned_eaves", "ridge_decorations",
                                     "ribbed_vaults", "vaults", "vaulted"); // 哥特式拱顶
        boolean hasDoors = hasFeature(c, "door", "doors", "entrance", "entry", "gateway",
                                      "double_doors", "stone_steps", "overhang_roof",
                                      "large_door", "tympanum", "statues"); // 哥特式入口
        boolean hasDecor = hasFeature(c, "decor", "decoration", "ornament", "carved", "carving", "lintel", "overhang",
                                      "wood_carvings", "intricate", "white_walls",
                                      "flying_buttresses", "buttresses", "gargoyles", "spire", // 哥特式装饰
                                      "pointed_arches", "arches", "ribbed_vaults"); // 哥特式结构
        boolean hasInterior = hasFeature(c, "interior", "rooms", "hollow", "courtyard", "central_courtyard",
                                         "inner_space", "empty_interior");
        boolean hasSteppedFacade = hasFeature(c, "stepped_facade", "stepped", "setback", "setbacks", 
                                               "进退", "进退关系", "立面", "facade_setback", "tiered");

        Double voidRatio = resolveVoidRatio(params, semantic);
        Double windowRatio = resolveWindowRatio(params, semantic);
        Double setbackRatioOverride = resolveSetbackRatio(params, semantic);
        int wallThickness = resolveWallThickness(params, semantic);
        String roofType = resolveRoofType(params, semantic);

        if ("none".equalsIgnoreCase(roofType)) {
            hasRoof = false;
        } else if (!hasRoof && roofType != null && !roofType.isBlank()) {
            hasRoof = true;
        }
        if (!hasSteppedFacade && shouldEnableSteppedFacade(semantic)) {
            hasSteppedFacade = true;
        }
        
        // 对于大多数建筑类型，默认应该有内部空间（除非明确不需要）
        // 教堂、住宅、商业建筑等都应该有内部空间
        boolean isBuilding = semantic.slot() != null && 
                            (semantic.slot().program() != null && 
                             !semantic.slot().program().equals("PLAZA") &&
                             !semantic.slot().program().equals("LANDSCAPE"));
        if (voidRatio != null) {
            hasInterior = voidRatio >= 0.08;
        }
        if (!hasInterior && isBuilding && width >= 5 && depth >= 5 && height >= 4) {
            // 对于足够大的建筑，默认应该有内部空间
            hasInterior = true;
            FormacraftMod.LOGGER.info("MassMainGenerator: enabling default interior space for building {}x{}x{} (program: {})", 
                    width, depth, height, semantic.slot() != null ? semantic.slot().program() : "unknown");
        }
        
        // 记录特征匹配结果（用于调试）
        FormacraftMod.LOGGER.debug("MassMainGenerator: features check - hasWindows: {}, hasDoors: {}, hasRoof: {}, hasDecor: {}, hasInterior: {}", 
                hasWindows, hasDoors, hasRoof, hasDecor, hasInterior);
        
        // 默认生成基础细节（即使没有匹配的 features）
        // 对于大多数建筑类型，默认应该有门和窗（除非明确不需要）
        boolean isResidential = semantic.slot() != null && 
                                "RESIDENTIAL".equals(semantic.slot().program());
        boolean isLandmark = semantic.slot() != null && 
                            "LANDMARK".equals(semantic.slot().program());
        boolean isCivic = semantic.slot() != null && 
                         "CIVIC".equals(semantic.slot().program());
        
        // 对于住宅、地标、市政建筑，默认应该有门和窗
        boolean shouldGenerateDefaultDetails = (isResidential || isLandmark || isCivic) && 
                                               !hasDoors && !hasWindows;
        
        // 如果应该生成默认细节，启用门和窗
        if (shouldGenerateDefaultDetails) {
            hasDoors = true;
            hasWindows = true;
            FormacraftMod.LOGGER.debug("MassMainGenerator: enabling default doors and windows for program: {}", 
                    semantic.slot() != null ? semantic.slot().program() : "unknown");
        }
        if (windowRatio != null) {
            hasWindows = windowRatio > 0.05;
        }

        int windowSpacing = resolveWindowSpacing(windowRatio);
        com.formacraft.common.llm.dto.GlobalConstraints.Facing doorFacing =
                (semantic.slot() != null && semantic.slot().facing() != null)
                        ? semantic.slot().facing()
                        : com.formacraft.common.llm.dto.GlobalConstraints.Facing.SOUTH;

        for (MassConfig mass : massConfigs) {
            emitMass(out, semantic, palette, rp, mass, hasInterior, hasWindows, hasDoors, hasRoof, hasDecor,
                    hasSteppedFacade, windowSpacing, doorFacing, wallThickness, userFloorHeight, setbackRatioOverride);
        }

        return out;
    }

    private void emitMass(
            List<BlockPatch> out,
            SemanticComponent semantic,
            Palette palette,
            Vec3i rp,
            MassConfig mass,
            boolean hasInterior,
            boolean hasWindows,
            boolean hasDoors,
            boolean hasRoof,
            boolean hasDecor,
            boolean hasSteppedFacade,
            int windowSpacing,
            com.formacraft.common.llm.dto.GlobalConstraints.Facing doorFacing,
            int wallThickness,
            int userFloorHeight,
            Double setbackRatioOverride
    ) {
        int width = mass.width;
        int depth = mass.depth;
        int height = mass.height;

        int[] layerWidths = new int[height];
        int[] layerDepths = new int[height];
        int[] layerXOffsets = new int[height];
        int[] layerZOffsets = new int[height];

        if (hasSteppedFacade && height >= 3) {
            double userSetbackRatio = setbackRatioOverride != null ? setbackRatioOverride
                    : com.formacraft.common.generator.util.ProportionalFacadeCalculator
                    .extractSetbackRatioFromFeatures(semantic.source().features());

            com.formacraft.common.generator.util.ProportionalFacadeCalculator.LayerConfig[] layerConfigs =
                    com.formacraft.common.generator.util.ProportionalFacadeCalculator.calculateSteppedFacade(
                            width, depth, height, userFloorHeight, userSetbackRatio
                    );

            for (int y = 0; y < height; y++) {
                layerWidths[y] = layerConfigs[y].width;
                layerDepths[y] = layerConfigs[y].depth;
                layerXOffsets[y] = layerConfigs[y].xOffset;
                layerZOffsets[y] = layerConfigs[y].zOffset;
            }
        } else {
            for (int y = 0; y < height; y++) {
                layerWidths[y] = width;
                layerDepths[y] = depth;
                layerXOffsets[y] = 0;
                layerZOffsets[y] = 0;
            }
        }

        for (int y = 0; y < height; y++) {
            int currentWidth = layerWidths[y];
            int currentDepth = layerDepths[y];
            int xOffset = layerXOffsets[y];
            int zOffset = layerZOffsets[y];

            for (int x = 0; x < currentWidth; x++) {
                for (int z = 0; z < currentDepth; z++) {
                    int localX = x + xOffset;
                    int localZ = z + zOffset;

                    if (!isInsideFootprint(localX, localZ, width, depth, mass.shape, mass.cornerRadius)) {
                        continue;
                    }

                    if (hasInterior && isInteriorSpace(localX, localZ, width, depth, y, height, wallThickness, mass.shape, mass.cornerRadius)) {
                        if (y == 0) {
                            SemanticPart part = SemanticPart.FLOOR;
                            String block = getBlockForPart(part, semantic, palette);
                            if (block == null || block.isEmpty()) {
                                block = getBlockForPart(SemanticPart.COURTYARD_FLOOR, semantic, palette);
                            }
                            if (block != null && !block.isEmpty()) {
                                out.add(new BlockPatch(
                                        BlockPatch.PLACE,
                                        rp.x() + mass.offsetX + localX,
                                        rp.y() + mass.offsetY + y,
                                        rp.z() + mass.offsetZ + localZ,
                                        block
                                ));
                            }
                        }
                        continue;
                    }

                    if (hasWindows && isWindowPosition(localX, localZ, width, depth, y, height, mass.shape, mass.cornerRadius, windowSpacing)) {
                        SemanticPart part = SemanticPart.WINDOW;
                        String block = palette.pick(part);
                        if (block == null || block.isEmpty()) {
                            block = "minecraft:glass";
                        }
                        out.add(new BlockPatch(
                                BlockPatch.PLACE,
                                rp.x() + mass.offsetX + localX,
                                rp.y() + mass.offsetY + y,
                                rp.z() + mass.offsetZ + localZ,
                                block
                        ));
                        continue;
                    }

                    if (hasDoors && isDoorPosition(localX, localZ, width, depth, y, mass.shape, mass.cornerRadius, doorFacing)) {
                        SemanticPart part = SemanticPart.DOORWAY;
                        String block = getBlockForPart(part, semantic, palette);
                        if (block == null || block.isEmpty()) {
                            block = "minecraft:air";
                        }
                        if (y == 0) {
                            part = SemanticPart.WALL_ACCENT;
                            block = getBlockForPart(part, semantic, palette);
                            out.add(new BlockPatch(
                                    BlockPatch.PLACE,
                                    rp.x() + mass.offsetX + localX,
                                    rp.y() + mass.offsetY + y,
                                    rp.z() + mass.offsetZ + localZ,
                                    block
                            ));
                        }
                        continue;
                    }

                    if (hasRoof && y >= height - 1) {
                        SemanticPart part = SemanticPart.ROOF_SURFACE;
                        String block = getBlockForPart(part, semantic, palette);
                        if (block == null || block.isEmpty()) {
                            part = SemanticPart.ROOF;
                            block = getBlockForPart(part, semantic, palette);
                        }
                        out.add(new BlockPatch(
                                BlockPatch.PLACE,
                                rp.x() + mass.offsetX + localX,
                                rp.y() + mass.offsetY + y,
                                rp.z() + mass.offsetZ + localZ,
                                block
                        ));
                        continue;
                    }

                    if (hasDecor && isDecorPosition(localX, localZ, width, depth, y, height, mass.shape, mass.cornerRadius)) {
                        SemanticPart part = SemanticPart.DECOR;
                        String block = getBlockForPart(part, semantic, palette);
                        if (block == null || block.isEmpty()) {
                            part = SemanticPart.WALL_ACCENT;
                            block = getBlockForPart(part, semantic, palette);
                        }
                        out.add(new BlockPatch(
                                BlockPatch.PLACE,
                                rp.x() + mass.offsetX + localX,
                                rp.y() + mass.offsetY + y,
                                rp.z() + mass.offsetZ + localZ,
                                block
                        ));
                        continue;
                    }

                    SemanticPart part = determinePart(y, height, width, depth, localX, localZ, mass.shape, mass.cornerRadius);
                    String block = getBlockForPart(part, semantic, palette);

                    out.add(new BlockPatch(
                            BlockPatch.PLACE,
                            rp.x() + mass.offsetX + localX,
                            rp.y() + mass.offsetY + y,
                            rp.z() + mass.offsetZ + localZ,
                            block
                    ));
                }
            }
        }
    }

    /**
     * 检查是否是窗户位置
     */
    private boolean isWindowPosition(int x, int z, int width, int depth, int y, int height,
                                     FootprintShape shape, int cornerRadius, int spacing) {
        boolean isMiddleHeight = (y > 0 && y < height - 1);
        if (!isMiddleHeight) {
            return false;
        }
        boolean isExterior = isExteriorWallPosition(x, z, width, depth, shape, cornerRadius);
        if (!isExterior) {
            return false;
        }
        if (isCornerPosition(x, z, width, depth, shape, cornerRadius)) {
            return false;
        }
        return isWindowSpacing(x, z, spacing);
    }

    /**
     * 检查是否是门位置
     */
    private boolean isDoorPosition(int x, int z, int width, int depth, int y,
                                   FootprintShape shape, int cornerRadius,
                                   com.formacraft.common.llm.dto.GlobalConstraints.Facing facing) {
        if (y >= 3) {
            return false;
        }
        if (!isExteriorWallPosition(x, z, width, depth, shape, cornerRadius)) {
            return false;
        }
        int centerX = width / 2;
        int centerZ = depth / 2;
        boolean isCenter = (x >= centerX - 1 && x <= centerX + 1) || (z >= centerZ - 1 && z <= centerZ + 1);

        return switch (facing) {
            case NORTH -> z >= depth - 1 && isCenter;
            case EAST -> x <= 0 && isCenter;
            case WEST -> x >= width - 1 && isCenter;
            case SOUTH -> z <= 0 && isCenter;
        };
    }

    /**
     * 检查是否是装饰位置
     */
    private boolean isDecorPosition(int x, int z, int width, int depth, int y, int height,
                                    FootprintShape shape, int cornerRadius) {
        boolean isTop = (y >= height - 2);
        boolean isExterior = isExteriorWallPosition(x, z, width, depth, shape, cornerRadius);
        boolean isCorner = isCornerPosition(x, z, width, depth, shape, cornerRadius);
        return (isTop || isExterior || isCorner) && (y > 0);
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
            if (upper.contains("GOTHIC")) {
                return "MEDIEVAL_CLASSIC"; // 哥特式使用中世纪风格
            }
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
                    if (lower.contains("gothic") || lower.contains("pointed_arches") || 
                        lower.contains("flying_buttresses") || lower.contains("ribbed_vaults")) {
                        return "MEDIEVAL_CLASSIC"; // 哥特式特征
                    }
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
                FormacraftMod.LOGGER.debug("MassMainGenerator: using DynamicPaletteResolver for {}: {}", part, block);
                return block;
            }
        }
        
        // 2. 尝试从 features 中提取材质信息（如果 style_attributes 为空）
        Component c = semantic.source();
        if (c != null && c.features() != null) {
            String block = extractBlockFromFeatures(part, c.features());
            if (block != null && !block.isEmpty()) {
                FormacraftMod.LOGGER.debug("MassMainGenerator: extracted block from features for {}: {}", part, block);
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
     * 例如：features 中包含 "white_concrete" → 返回 "minecraft:white_concrete"
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
            
            // 检查是否是直接的方块 ID（例如 "white_concrete", "glass", "glass_pane"）
            if (lower.contains("white_concrete") || lower.contains("white concrete")) {
                if (part == SemanticPart.WALL || part == SemanticPart.WALL_BASE || part == SemanticPart.WALL_ACCENT) {
                    return "minecraft:white_concrete";
                }
            }
            if (lower.contains("glass_curtain") || lower.contains("curtain_wall") || 
                lower.contains("glass_pane") || (lower.contains("glass") && lower.contains("curtain"))) {
                if (part == SemanticPart.WINDOW) {
                    return "minecraft:glass_pane";
                }
            }
            if (lower.contains("glass") && !lower.contains("curtain")) {
                if (part == SemanticPart.WINDOW) {
                    return "minecraft:glass";
                }
            }
            if (lower.contains("black_concrete") || lower.contains("black concrete")) {
                if (part == SemanticPart.ROOF || part == SemanticPart.ROOF_SURFACE) {
                    return "minecraft:black_concrete";
                }
            }
            if (lower.contains("gray_concrete") || lower.contains("grey_concrete") || lower.contains("gray concrete")) {
                if (part == SemanticPart.WALL || part == SemanticPart.WALL_BASE) {
                    return "minecraft:gray_concrete";
                }
            }
            if (lower.contains("metal") || lower.contains("steel") || lower.contains("iron")) {
                if (part == SemanticPart.DECOR || part == SemanticPart.WALL_ACCENT) {
                    return "minecraft:iron_block";
                }
            }
            if (lower.contains("concrete") && !lower.contains("white") && !lower.contains("black") && !lower.contains("gray")) {
                // 默认混凝土（灰色）
                if (part == SemanticPart.WALL || part == SemanticPart.WALL_BASE) {
                    return "minecraft:gray_concrete";
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
    private boolean isInteriorSpace(int x, int z, int width, int depth, int y, int height,
                                    int wallThickness, FootprintShape shape, int cornerRadius) {
        if (y >= height - 1) {
            return false;
        }
        return isInsideInnerFootprint(x, z, width, depth, wallThickness, shape, cornerRadius);
    }

    private SemanticPart determinePart(int y, int height, int width, int depth, int x, int z,
                                       FootprintShape shape, int cornerRadius) {
        // 基础部分
        if (y == 0) {
            return SemanticPart.WALL_BASE;
        }
        
        // 顶部
        if (y >= height - 1) {
            return SemanticPart.WALL_ACCENT;
        }
        
        if (isExteriorWallPosition(x, z, width, depth, shape, cornerRadius)) {
            return SemanticPart.WALL_ACCENT;
        }
        
        // 内部（填充）
        return SemanticPart.WALL;
    }

    private FootprintShape resolveShape(Component c, SemanticComponent semantic) {
        String shape = getParamString(c.params(), "shape", "footprint_shape", "footprintShape");
        if (shape == null && semantic != null && semantic.genome() != null
                && semantic.genome().topology != null && semantic.genome().topology.layout != null) {
            String layout = semantic.genome().topology.layout;
            if (layout.equalsIgnoreCase("circular") || layout.equalsIgnoreCase("radial")) {
                shape = "circle";
            } else if (layout.equalsIgnoreCase("freeform")) {
                shape = "rounded_rect";
            }
        }
        if (shape == null && c.features() != null) {
            for (String feature : c.features()) {
                if (feature == null) continue;
                String lower = feature.toLowerCase();
                if (lower.contains("circle") || lower.contains("circular") || lower.contains("round")) {
                    shape = "circle";
                    break;
                }
                if (lower.contains("rounded") || lower.contains("curved")) {
                    shape = "rounded_rect";
                }
            }
        }
        if (shape == null && semantic != null && semantic.genome() != null
                && semantic.genome().form != null && "curved".equalsIgnoreCase(semantic.genome().form.curvature)) {
            shape = "rounded_rect";
        }
        if (shape == null) {
            return FootprintShape.RECTANGLE;
        }
        return switch (shape.trim().toLowerCase()) {
            case "circle", "circular", "round" -> FootprintShape.CIRCLE;
            case "rounded_rect", "rounded", "roundrect", "round_rect" -> FootprintShape.ROUNDED_RECT;
            default -> FootprintShape.RECTANGLE;
        };
    }

    private int resolveCornerRadius(Map<String, Object> params, int width, int depth, FootprintShape shape) {
        int radius = getParamInt(params, 0, "corner_radius", "cornerRadius");
        if (radius <= 0 && shape == FootprintShape.ROUNDED_RECT) {
            radius = Math.max(1, Math.min(width, depth) / 6);
        }
        int max = Math.max(1, Math.min(width, depth) / 2);
        return Math.max(0, Math.min(radius, max));
    }

    private List<MassConfig> resolveMasses(Map<String, Object> params, int width, int depth, int height,
                                          FootprintShape baseShape, int cornerRadius) {
        List<MassConfig> masses = new ArrayList<>();
        masses.add(new MassConfig(0, 0, 0, width, depth, height, baseShape, cornerRadius));

        Object massesObj = params != null ? params.get("masses") : null;
        if (!(massesObj instanceof List<?> list)) {
            return masses;
        }

        for (Object obj : list) {
            Map<String, Object> m = asMap(obj);
            if (m == null) continue;
            Map<String, Object> offset = asMap(m.get("offset"));
            Map<String, Object> dims = asMap(m.get("dimensions"));
            if (dims == null) continue;

            int mw = getParamInt(dims, width, "width");
            int md = getParamInt(dims, depth, "depth");
            int mh = getParamInt(dims, height, "height");
            if (mw <= 0 || md <= 0 || mh <= 0) continue;

            int ox = offset != null ? getParamInt(offset, 0, "x") : 0;
            int oy = offset != null ? getParamInt(offset, 0, "y") : 0;
            int oz = offset != null ? getParamInt(offset, 0, "z") : 0;

            FootprintShape shape = baseShape;
            String massShape = getParamString(m, "shape");
            if (massShape != null) {
                shape = switch (massShape.trim().toLowerCase()) {
                    case "circle", "circular", "round" -> FootprintShape.CIRCLE;
                    case "rounded_rect", "rounded", "roundrect", "round_rect" -> FootprintShape.ROUNDED_RECT;
                    default -> FootprintShape.RECTANGLE;
                };
            }
            int cr = resolveCornerRadius(m, mw, md, shape);
            masses.add(new MassConfig(ox, oy, oz, mw, md, mh, shape, cr));
        }

        return masses;
    }

    private Double resolveVoidRatio(Map<String, Object> params, SemanticComponent semantic) {
        Double ratio = getParamDouble(params, "void_ratio", "voidRatio");
        if (ratio != null) return clamp01(ratio);
        if (semantic != null && semantic.genome() != null && semantic.genome().structure != null) {
            Double v = semantic.genome().structure.voidRatio;
            if (v != null) return clamp01(v);
        }
        return null;
    }

    private Double resolveWindowRatio(Map<String, Object> params, SemanticComponent semantic) {
        Double ratio = getParamDouble(params, "window_ratio", "windowRatio");
        if (ratio != null) return clamp01(ratio);
        return null;
    }

    private Double resolveSetbackRatio(Map<String, Object> params, SemanticComponent semantic) {
        Double ratio = getParamDouble(params, "setback_ratio", "setbackRatio");
        if (ratio != null) return clamp01(ratio);
        if (semantic != null && semantic.genome() != null && semantic.genome().form != null) {
            String progression = semantic.genome().form.progression;
            if ("stepping".equalsIgnoreCase(progression)) {
                return 0.07;
            }
            if ("tapering".equalsIgnoreCase(progression)) {
                return 0.05;
            }
        }
        return null;
    }

    private int resolveWallThickness(Map<String, Object> params, SemanticComponent semantic) {
        int override = getParamInt(params, -1, "wall_thickness", "wallThickness");
        if (override > 0) {
            return Math.min(4, override);
        }
        Double massiveness = getParamDouble(params, "massiveness");
        if (massiveness == null && semantic != null && semantic.genome() != null
                && semantic.genome().structure != null) {
            massiveness = semantic.genome().structure.massiveness;
        }
        if (massiveness != null) {
            if (massiveness >= 0.85) return 3;
            if (massiveness >= 0.65) return 2;
        }
        return 1;
    }

    private String resolveRoofType(Map<String, Object> params, SemanticComponent semantic) {
        String type = getParamString(params, "roof_type", "roofType");
        if (type != null) return type;
        return null;
    }

    private boolean shouldEnableSteppedFacade(SemanticComponent semantic) {
        if (semantic == null || semantic.genome() == null || semantic.genome().form == null) {
            return false;
        }
        String progression = semantic.genome().form.progression;
        return "stepping".equalsIgnoreCase(progression) || "tapering".equalsIgnoreCase(progression);
    }

    private boolean isInsideFootprint(int x, int z, int width, int depth, FootprintShape shape, int cornerRadius) {
        if (x < 0 || z < 0 || x >= width || z >= depth) {
            return false;
        }
        if (shape == FootprintShape.RECTANGLE) {
            return true;
        }
        if (shape == FootprintShape.CIRCLE) {
            double cx = (width - 1) / 2.0;
            double cz = (depth - 1) / 2.0;
            double rx = Math.max(0.5, width / 2.0);
            double rz = Math.max(0.5, depth / 2.0);
            double dx = (x - cx) / rx;
            double dz = (z - cz) / rz;
            return (dx * dx + dz * dz) <= 1.0;
        }
        int r = Math.max(0, Math.min(cornerRadius, Math.min(width, depth) / 2));
        if (r <= 0) {
            return true;
        }
        int maxX = width - 1;
        int maxZ = depth - 1;
        boolean inXBand = x >= r && x <= maxX - r;
        boolean inZBand = z >= r && z <= maxZ - r;
        if (inXBand || inZBand) {
            return true;
        }
        int cornerX = x < r ? r - 1 : maxX - r + 1;
        int cornerZ = z < r ? r - 1 : maxZ - r + 1;
        double dx = x - cornerX;
        double dz = z - cornerZ;
        double rr = r - 0.5;
        return (dx * dx + dz * dz) <= (rr * rr);
    }

    private boolean isInsideInnerFootprint(int x, int z, int width, int depth, int wallThickness,
                                           FootprintShape shape, int cornerRadius) {
        if (wallThickness <= 0) {
            return isInsideFootprint(x, z, width, depth, shape, cornerRadius);
        }
        if (shape == FootprintShape.RECTANGLE) {
            return x >= wallThickness && z >= wallThickness
                    && x < width - wallThickness
                    && z < depth - wallThickness;
        }
        if (shape == FootprintShape.CIRCLE) {
            double cx = (width - 1) / 2.0;
            double cz = (depth - 1) / 2.0;
            double rx = Math.max(0.1, (width / 2.0) - wallThickness);
            double rz = Math.max(0.1, (depth / 2.0) - wallThickness);
            double dx = (x - cx) / rx;
            double dz = (z - cz) / rz;
            return (dx * dx + dz * dz) <= 1.0;
        }
        int innerWidth = width - wallThickness * 2;
        int innerDepth = depth - wallThickness * 2;
        if (innerWidth <= 0 || innerDepth <= 0) {
            return false;
        }
        return isInsideFootprint(
                x - wallThickness,
                z - wallThickness,
                innerWidth,
                innerDepth,
                FootprintShape.ROUNDED_RECT,
                Math.max(0, cornerRadius - wallThickness)
        );
    }

    private boolean isExteriorWallPosition(int x, int z, int width, int depth, FootprintShape shape, int cornerRadius) {
        if (!isInsideFootprint(x, z, width, depth, shape, cornerRadius)) {
            return false;
        }
        return !isInsideFootprint(x + 1, z, width, depth, shape, cornerRadius)
                || !isInsideFootprint(x - 1, z, width, depth, shape, cornerRadius)
                || !isInsideFootprint(x, z + 1, width, depth, shape, cornerRadius)
                || !isInsideFootprint(x, z - 1, width, depth, shape, cornerRadius);
    }

    private boolean isCornerPosition(int x, int z, int width, int depth, FootprintShape shape, int cornerRadius) {
        if (!isExteriorWallPosition(x, z, width, depth, shape, cornerRadius)) {
            return false;
        }
        int outside = 0;
        if (!isInsideFootprint(x + 1, z, width, depth, shape, cornerRadius)) outside++;
        if (!isInsideFootprint(x - 1, z, width, depth, shape, cornerRadius)) outside++;
        if (!isInsideFootprint(x, z + 1, width, depth, shape, cornerRadius)) outside++;
        if (!isInsideFootprint(x, z - 1, width, depth, shape, cornerRadius)) outside++;
        return outside >= 2;
    }

    private boolean isWindowSpacing(int x, int z, int spacing) {
        int mod = Math.max(2, spacing);
        return ((x + z) % mod) != 0;
    }

    private int resolveWindowSpacing(Double windowRatio) {
        if (windowRatio == null) {
            return 3;
        }
        if (windowRatio >= 0.6) return 2;
        if (windowRatio >= 0.35) return 3;
        if (windowRatio >= 0.2) return 4;
        return 5;
    }

    private static int getParamInt(Map<String, Object> params, int fallback, String... keys) {
        if (params == null || keys == null) return fallback;
        for (String key : keys) {
            if (key == null) continue;
            Object v = params.get(key);
            if (v == null) continue;
            if (v instanceof Number n) {
                return n.intValue();
            }
            if (v instanceof String s) {
                try {
                    return Integer.parseInt(s.trim());
                } catch (NumberFormatException ignored) {}
            }
        }
        return fallback;
    }

    private static Double getParamDouble(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) return null;
        for (String key : keys) {
            if (key == null) continue;
            Object v = params.get(key);
            if (v == null) continue;
            if (v instanceof Number n) {
                return n.doubleValue();
            }
            if (v instanceof String s) {
                try {
                    return Double.parseDouble(s.trim());
                } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    private static String getParamString(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) return null;
        for (String key : keys) {
            if (key == null) continue;
            Object v = params.get(key);
            if (v == null) continue;
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}

