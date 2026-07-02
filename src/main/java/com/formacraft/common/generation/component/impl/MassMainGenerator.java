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
import com.formacraft.FormacraftMod;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    private enum PlanPattern {
        NONE,
        CROSS,
        CUT_CORNERS,
        L_SHAPE,
        COURTYARD
    }

    private static final class PatternConfig {
        private final PlanPattern pattern;
        private final int size;
        private final double ratio;
        private final String corner;

        private PatternConfig(PlanPattern pattern, int size, double ratio, String corner) {
            this.pattern = pattern;
            this.size = size;
            this.ratio = ratio;
            this.corner = corner;
        }
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
        private final PatternConfig pattern;

        private MassConfig(int offsetX, int offsetY, int offsetZ, int width, int depth, int height,
                           FootprintShape shape, int cornerRadius, PatternConfig pattern) {
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
            this.width = width;
            this.depth = depth;
            this.height = height;
            this.shape = shape;
            this.cornerRadius = cornerRadius;
            this.pattern = pattern;
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
        
        // ========== 锚点处理：relativePosition 既可能是中心点，也可能是左下角 ==========
        // 默认假设是中心点（LLM prompt），如果 params 指定 anchor_mode=min_corner，则视为左下角
        Vec3i actualRp = rp;
        String anchorMode = getParamString(c.params(), "anchor_mode", "anchorMode");
        boolean useCornerAnchor = anchorMode != null && anchorMode.toLowerCase(Locale.ROOT).contains("corner");
        if (!useCornerAnchor) {
            // 中心点 -> 左下角：左下角 = 中心点 - (width/2, 0, depth/2)
            int offsetX = -(width / 2);
            int offsetZ = -(depth / 2);
            actualRp = new Vec3i(rp.x() + offsetX, rp.y(), rp.z() + offsetZ);
            if (rp.x() != actualRp.x() || rp.z() != actualRp.z()) {
                FormacraftMod.LOGGER.debug("MassMainGenerator: converted center anchor {} to bottom-left origin {} (size: {}x{})",
                        rp, actualRp, width, depth);
            }
        }
        
        Map<String, Object> params = c.params();

        FootprintShape baseShape = resolveShape(c, semantic);
        int cornerRadius = resolveCornerRadius(params, width, depth, baseShape);
        PatternConfig basePattern = resolvePlanPattern(params, semantic, width, depth, baseShape);
        List<MassConfig> massConfigs = resolveMasses(params, semantic, width, depth, height, baseShape, cornerRadius, basePattern);
        
        // 使用转换后的实际原点
        rp = actualRp;
        
        // 尺寸规范化：确保高度至少3米（3格）
        // 如果用户指定了具体高度，使用用户的要求
        int userFloorHeight = ComponentParamParsers.intParam(params, 0, "floor_height", "floorHeight");
        if (userFloorHeight <= 0) {
            userFloorHeight = com.formacraft.common.generation.component.util.ProportionalFacadeCalculator
                    .extractFloorHeightFromFeatures(c.features());
        }
        if (userFloorHeight > 0) {
            // 用户指定了每层高度，验证总高度是否合理
            // 至少1层
            if (height < userFloorHeight) {
                FormacraftMod.LOGGER.warn("MassMainGenerator: total height {} is less than minimum {} (floor height: {})", 
                        height, userFloorHeight, userFloorHeight);
                // 调整总高度以匹配用户要求
                height = userFloorHeight;
            }
        } else {
            // 用户未指定，确保总高度至少3米
            height = Math.max(3, height);
        }
        
        // 验证尺寸合理性
        if (!com.formacraft.common.generation.component.util.ProportionalFacadeCalculator.validateDimensions(width, depth, height)) {
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
        boolean assemblyFacade = getParamBoolean(params, "assembly_facade", "assemblyFacade");
        boolean suppressWindows = getParamBoolean(params, "suppress_windows", "suppressWindows");
        boolean suppressDoors = getParamBoolean(params, "suppress_doors", "suppressDoors");

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
                    width, depth, height, semantic.slot().program());
        }
        
        if (assemblyFacade) {
            hasWindows = false;
            hasDoors = false;
        }
        if (suppressWindows) {
            hasWindows = false;
        }
        if (suppressDoors) {
            hasDoors = false;
        }

        // 记录特征匹配结果（用于调试）
        FormacraftMod.LOGGER.debug("MassMainGenerator: features check - hasWindows: {}, hasDoors: {}, hasRoof: {}, hasDecor: {}, hasInterior: {}, assemblyFacade: {}", 
                hasWindows, hasDoors, hasRoof, hasDecor, hasInterior, assemblyFacade);
        
        // 默认生成基础细节（即使没有匹配的 features）
        // 对于大多数建筑类型，默认应该有门和窗（除非明确不需要）
        boolean shouldGenerateDefaultDetails = !assemblyFacade && !suppressWindows && !suppressDoors
                && isShouldGenerateDefaultDetails(semantic, hasDoors, hasWindows);

        // 如果应该生成默认细节，启用门和窗
        if (shouldGenerateDefaultDetails) {
            hasDoors = true;
            hasWindows = true;
            if (semantic.slot() != null) {
                FormacraftMod.LOGGER.debug("MassMainGenerator: enabling default doors and windows for program: {}",
                        semantic.slot().program());
            }
        }
        if (!assemblyFacade && !suppressWindows && isBuilding && !hasWindows && width >= 5 && depth >= 5 && height >= 4) {
            hasWindows = true;
            if (windowRatio == null) {
                windowRatio = 0.25;
            }
        }
        if (!assemblyFacade && !suppressDoors && isBuilding && !hasDoors && width >= 4 && depth >= 4 && height >= 3) {
            hasDoors = true;
        }
        if (!assemblyFacade && !suppressWindows && windowRatio != null) {
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

    private static boolean isShouldGenerateDefaultDetails(SemanticComponent semantic, boolean hasDoors, boolean hasWindows) {
        boolean isResidential = semantic.slot() != null &&
                                "RESIDENTIAL".equals(semantic.slot().program());
        boolean isLandmark = semantic.slot() != null &&
                            "LANDMARK".equals(semantic.slot().program());
        boolean isCivic = semantic.slot() != null &&
                         "CIVIC".equals(semantic.slot().program());

        // 对于住宅、地标、市政建筑，默认应该有门和窗
        return (isResidential || isLandmark || isCivic) &&
                                               !hasDoors && !hasWindows;
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
                    : com.formacraft.common.generation.component.util.ProportionalFacadeCalculator
                    .extractSetbackRatioFromFeatures(semantic.source().features());

            com.formacraft.common.generation.component.util.ProportionalFacadeCalculator.LayerConfig[] layerConfigs =
                    com.formacraft.common.generation.component.util.ProportionalFacadeCalculator.calculateSteppedFacade(
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

                    if (!isInsideFootprint(localX, localZ, width, depth, mass.shape, mass.cornerRadius, mass.pattern)) {
                        continue;
                    }

                    if (mass.pattern != null && mass.pattern.pattern == PlanPattern.COURTYARD
                            && isInCourtyardHole(localX, localZ, width, depth, mass.pattern)) {
                        if (y == 0) {
                            SemanticPart part = SemanticPart.COURTYARD_FLOOR;
                            String block = getBlockForPart(part, semantic, palette);
                            if (!block.isEmpty()) {
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

                    if (hasInterior && isInteriorSpace(localX, localZ, width, depth, y, height, wallThickness,
                            mass.shape, mass.cornerRadius, mass.pattern)) {
                        if (y == 0) {
                            SemanticPart part = SemanticPart.FLOOR;
                            String block = getBlockForPart(part, semantic, palette);
                            if (block.isEmpty()) {
                                block = getBlockForPart(SemanticPart.COURTYARD_FLOOR, semantic, palette);
                            }
                            if (!block.isEmpty()) {
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

                    if (hasWindows && isWindowPosition(localX, localZ, width, depth, y, height, mass.shape,
                            mass.cornerRadius, mass.pattern, windowSpacing, userFloorHeight)) {
                        SemanticPart part = SemanticPart.WINDOW;
                        String block = getBlockForPart(part, semantic, palette);
                        if (block.isEmpty()) {
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

                    if (hasDoors && isDoorPosition(localX, localZ, width, depth, y, mass.shape,
                            mass.cornerRadius, mass.pattern, doorFacing)) {
                        SemanticPart part = SemanticPart.DOORWAY;
                        String block = getBlockForPart(part, semantic, palette);
                        if (block.isEmpty()) {
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
                        if (block.isEmpty()) {
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

                    if (hasDecor && isDecorPosition(localX, localZ, width, depth, y, height, mass.shape,
                            mass.cornerRadius, mass.pattern)) {
                        SemanticPart part = SemanticPart.DECOR;
                        String block = getBlockForPart(part, semantic, palette);
                        if (block.isEmpty()) {
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

                    SemanticPart part = determinePart(y, height, width, depth, localX, localZ,
                            mass.shape, mass.cornerRadius, mass.pattern);
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
                                     FootprintShape shape, int cornerRadius, PatternConfig pattern, int spacing,
                                     int floorHeight) {
        boolean isMiddleHeight = (y > 0 && y < height - 1);
        if (!isMiddleHeight || !isWindowBand(y, height, floorHeight)) {
            return false;
        }
        boolean isExterior = isExteriorWallPosition(x, z, width, depth, shape, cornerRadius, pattern);
        if (!isExterior) {
            return false;
        }
        if (isCornerPosition(x, z, width, depth, shape, cornerRadius, pattern)) {
            return false;
        }
        return isWindowSpacing(x, z, spacing);
    }

    private boolean isWindowBand(int y, int height, int floorHeight) {
        if (y <= 0 || y >= height - 1) {
            return false;
        }
        int bandHeight = floorHeight > 0 ? floorHeight : 3;
        bandHeight = Math.max(3, bandHeight);
        int localY = Math.floorMod(y, bandHeight);
        return localY > 0 && localY < bandHeight - 1;
    }

    /**
     * 检查是否是门位置
     */
    private boolean isDoorPosition(int x, int z, int width, int depth, int y,
                                   FootprintShape shape, int cornerRadius, PatternConfig pattern,
                                   com.formacraft.common.llm.dto.GlobalConstraints.Facing facing) {
        if (y >= 3) {
            return false;
        }
        if (!isExteriorWallPosition(x, z, width, depth, shape, cornerRadius, pattern)) {
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
                                    FootprintShape shape, int cornerRadius, PatternConfig pattern) {
        boolean isTop = (y >= height - 2);
        boolean isExterior = isExteriorWallPosition(x, z, width, depth, shape, cornerRadius, pattern);
        boolean isCorner = isCornerPosition(x, z, width, depth, shape, cornerRadius, pattern);
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
            if (upper.contains("CHINESE_ROYAL") || upper.contains("CHINESE_IMPERIAL") || upper.contains("CHINESE_PALACE")
                    || (upper.contains("CHINESE") && (upper.contains("ROYAL") || upper.contains("IMPERIAL") || upper.contains("PALACE")))) {
                return "CHINESE_ROYAL";
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
                    if (lower.contains("imperial") || lower.contains("royal") || lower.contains("palace")
                            || lower.contains("vermilion") || lower.contains("golden")) {
                        return "CHINESE_ROYAL";
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
            case WALL, WALL_BASE, FLOOR, COURTYARD_FLOOR -> "minecraft:stone_bricks";
            case ROOF, ROOF_SURFACE -> "minecraft:spruce_planks";
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
                                    int wallThickness, FootprintShape shape, int cornerRadius,
                                    PatternConfig pattern) {
        if (y >= height - 1) {
            return false;
        }
        return isInsideInnerFootprint(x, z, width, depth, wallThickness, shape, cornerRadius, pattern);
    }

    private SemanticPart determinePart(int y, int height, int width, int depth, int x, int z,
                                       FootprintShape shape, int cornerRadius, PatternConfig pattern) {
        // 基础部分
        if (y == 0) {
            return SemanticPart.WALL_BASE;
        }
        
        // 顶部
        if (y >= height - 1) {
            return SemanticPart.WALL_ACCENT;
        }
        
        if (isCornerPosition(x, z, width, depth, shape, cornerRadius, pattern)) {
            return SemanticPart.WALL_ACCENT;
        }
        isExteriorWallPosition(x, z, width, depth, shape, cornerRadius, pattern);

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

    private PatternConfig resolvePlanPattern(Map<String, Object> params, SemanticComponent semantic,
                                             int width, int depth, FootprintShape shape) {
        String planType = getParamString(params, "plan_type", "planType",
                "footprint_pattern", "footprintPattern", "plan_pattern", "planPattern");
        Component c = semantic != null ? semantic.source() : null;

        if (planType != null) {
            String normalized = planType.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty() || normalized.equals("none") || normalized.equals("default")) {
                planType = null;
            }
        }

        if (planType == null && c != null) {
            if (hasFeature(c, "gothic", "cathedral", "church", "basilica", "cruciform", "cross")) {
                planType = "cross";
            } else if (hasFeature(c, "courtyard", "siheyuan", "四合院", "庭院", "compound", "ring")) {
                planType = "courtyard";
            } else if (hasFeature(c, "l-shape", "l_shape", "lshape", "corner_wing")) {
                planType = "l_shape";
            } else if (hasFeature(c, "chinese", "hui", "徽派", "中式")) {
                planType = "cut_corners";
            }
        }

        if (planType == null && semantic != null && semantic.genome() != null && semantic.genome().culturalStyle != null) {
            String region = semantic.genome().culturalStyle.region;
            List<String> keywords = semantic.genome().culturalStyle.keywords;
            if (region != null && region.toLowerCase().contains("chinese")) {
                planType = "cut_corners";
            }
            if (keywords != null) {
                if (containsKeyword(keywords, "gothic", "cathedral", "church", "cruciform")) {
                    planType = "cross";
                }
                if (containsKeyword(keywords, "courtyard", "siheyuan")) {
                    planType = "courtyard";
                }
            }
        }

        if (planType == null && semantic != null) {
            String profile = getStyleProfile(semantic);
            String upper = profile.toUpperCase();
            if (upper.contains("HUI") || upper.contains("CHINESE")) {
                planType = "cut_corners";
            } else if (upper.contains("GOTHIC") || upper.contains("CATHEDRAL") || upper.contains("CHURCH")) {
                planType = "cross";
            }
        }

        PlanPattern pattern = parsePlanPattern(planType);
        if (pattern == PlanPattern.NONE) {
            return new PatternConfig(PlanPattern.NONE, 0, 0.0, null);
        }
        if (shape == FootprintShape.CIRCLE && pattern != PlanPattern.COURTYARD) {
            return new PatternConfig(PlanPattern.NONE, 0, 0.0, null);
        }

        int size = 0;
        double ratio = 0.0;
        String corner = null;

        switch (pattern) {
            case CROSS -> {
                size = ComponentParamParsers.intParam(params, 0, "arm_width", "cross_arm", "cross_arm_width", "armWidth", "crossArmWidth");
                if (size <= 0) {
                    size = Math.max(3, Math.min(width, depth) / 3);
                }
            }
            case CUT_CORNERS -> {
                size = ComponentParamParsers.intParam(params, 0, "corner_cut", "cornerCut", "cut_corner", "cutCorner", "cut_size");
                if (size <= 0) {
                    size = Math.max(2, Math.min(width, depth) / 5);
                }
            }
            case L_SHAPE -> {
                size = ComponentParamParsers.intParam(params, 0, "l_cut", "lCut", "cut_size", "corner_cut", "cornerCut");
                if (size <= 0) {
                    size = Math.max(3, Math.min(width, depth) / 3);
                }
                corner = normalizeCorner(getParamString(params, "l_corner", "lCorner", "cut_corner", "cutCorner", "corner"));
            }
            case COURTYARD -> {
                Double ratioValue = ComponentParamParsers.doubleOrNull(params, "courtyard_ratio", "courtyardRatio",
                        "court_ratio", "void_ratio", "voidRatio");
                if (ratioValue != null && ratioValue > 0.0) {
                    ratio = ratioValue;
                } else {
                    ratio = 0.35;
                }
            }
            default -> {
            }
        }

        if (pattern == PlanPattern.COURTYARD) {
            ratio = Math.max(0.2, Math.min(0.6, ratio));
        }
        size = Math.max(1, Math.min(size, Math.min(width, depth) - 2));

        return new PatternConfig(pattern, size, ratio, corner);
    }

    private static PlanPattern parsePlanPattern(String value) {
        if (value == null) return PlanPattern.NONE;
        String v = value.trim().toLowerCase();
        if (v.isEmpty()) return PlanPattern.NONE;
        return switch (v) {
            case "cross", "cruciform", "plus", "t_shape", "t-shape", "tshape" -> PlanPattern.CROSS;
            case "cut_corners", "cut-corners", "cutcorner", "cut_corner", "chamfer", "chamfered", "octagon", "octagonal" ->
                    PlanPattern.CUT_CORNERS;
            case "l", "l_shape", "l-shape", "lshape", "corner", "corner_wing" -> PlanPattern.L_SHAPE;
            case "courtyard", "court", "ring", "donut", "siheyuan", "compound" -> PlanPattern.COURTYARD;
            default -> PlanPattern.NONE;
        };
    }

    private static String normalizeCorner(String value) {
        if (value == null) return null;
        String v = value.trim().toLowerCase();
        if (v.isEmpty()) return null;
        if (v.contains("nw") || (v.contains("north") && v.contains("west"))
                || v.contains("top_left") || v.contains("upper_left") || v.contains("left_top")) {
            return "NW";
        }
        if (v.contains("ne") || (v.contains("north") && v.contains("east"))
                || v.contains("top_right") || v.contains("upper_right") || v.contains("right_top")) {
            return "NE";
        }
        if (v.contains("sw") || (v.contains("south") && v.contains("west"))
                || v.contains("bottom_left") || v.contains("lower_left") || v.contains("left_bottom")) {
            return "SW";
        }
        if (v.contains("se") || (v.contains("south") && v.contains("east"))
                || v.contains("bottom_right") || v.contains("lower_right") || v.contains("right_bottom")) {
            return "SE";
        }
        return null;
    }

    private static boolean containsKeyword(List<String> keywords, String... tokens) {
        if (keywords == null || tokens == null) return false;
        for (String kw : keywords) {
            if (kw == null) continue;
            String lower = kw.toLowerCase();
            for (String token : tokens) {
                if (token == null) continue;
                if (lower.contains(token.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isInCrossArms(int x, int z, int width, int depth, int size) {
        int arm = Math.max(1, Math.min(size, Math.min(width, depth)));
        if (arm >= width || arm >= depth) {
            return true;
        }
        int startX = (width - arm) / 2;
        int endX = startX + arm - 1;
        int startZ = (depth - arm) / 2;
        int endZ = startZ + arm - 1;
        return (x >= startX && x <= endX) || (z >= startZ && z <= endZ);
    }

    private static boolean isInCornerCut(int x, int z, int width, int depth, int size) {
        if (size <= 0) return false;
        int cut = Math.max(1, Math.min(size, Math.min(width, depth) - 1));
        boolean left = x < cut;
        boolean right = x >= width - cut;
        boolean top = z < cut;
        boolean bottom = z >= depth - cut;
        return (left && top) || (left && bottom) || (right && top) || (right && bottom);
    }

    private static boolean isInLCut(int x, int z, int width, int depth, int size, String corner) {
        if (size <= 0) return false;
        int cut = Math.max(1, Math.min(size, Math.min(width, depth) - 1));
        String c = corner != null ? corner : "NE";
        return switch (c) {
            case "NW" -> x < cut && z < cut;
            case "SW" -> x < cut && z >= depth - cut;
            case "SE" -> x >= width - cut && z >= depth - cut;
            default -> x >= width - cut && z < cut;
        };
    }

    private static boolean isInCourtyardHole(int x, int z, int width, int depth, PatternConfig pattern) {
        if (pattern == null || pattern.pattern != PlanPattern.COURTYARD) {
            return false;
        }
        if (width < 3 || depth < 3) {
            return false;
        }
        double ratio = pattern.ratio;
        if (ratio <= 0.0) {
            ratio = 0.35;
        }
        int holeWidth = Math.max(1, Math.min(width - 2, (int) Math.round(width * ratio)));
        int holeDepth = Math.max(1, Math.min(depth - 2, (int) Math.round(depth * ratio)));
        int startX = (width - holeWidth) / 2;
        int startZ = (depth - holeDepth) / 2;
        int endX = startX + holeWidth - 1;
        int endZ = startZ + holeDepth - 1;
        return x >= startX && x <= endX && z >= startZ && z <= endZ;
    }

    private int resolveCornerRadius(Map<String, Object> params, int width, int depth, FootprintShape shape) {
        int radius = ComponentParamParsers.intParam(params, 0, "corner_radius", "cornerRadius");
        if (radius <= 0 && shape == FootprintShape.ROUNDED_RECT) {
            radius = Math.max(1, Math.min(width, depth) / 6);
        }
        int max = Math.max(1, Math.min(width, depth) / 2);
        return Math.max(0, Math.min(radius, max));
    }

    private List<MassConfig> resolveMasses(Map<String, Object> params, SemanticComponent semantic,
                                          int width, int depth, int height,
                                          FootprintShape baseShape, int cornerRadius,
                                          PatternConfig basePattern) {
        List<MassConfig> masses = new ArrayList<>();
        masses.add(new MassConfig(0, 0, 0, width, depth, height, baseShape, cornerRadius, basePattern));

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

            int mw = ComponentParamParsers.intParam(dims, width, "width");
            int md = ComponentParamParsers.intParam(dims, depth, "depth");
            int mh = ComponentParamParsers.intParam(dims, height, "height");
            if (mw <= 0 || md <= 0 || mh <= 0) continue;

            int ox = offset != null ? ComponentParamParsers.intParam(offset, 0, "x") : 0;
            int oy = offset != null ? ComponentParamParsers.intParam(offset, 0, "y") : 0;
            int oz = offset != null ? ComponentParamParsers.intParam(offset, 0, "z") : 0;

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
            PatternConfig pattern = resolvePlanPattern(m, semantic, mw, md, shape);
            if (pattern.pattern == PlanPattern.NONE && basePattern != null && basePattern.pattern != null && basePattern.pattern != PlanPattern.NONE) {
                Map<String, Object> fallback = getStringObjectMap(basePattern);
                pattern = resolvePlanPattern(fallback, semantic, mw, md, shape);
            }
            masses.add(new MassConfig(ox, oy, oz, mw, md, mh, shape, cr, pattern));
        }

        return masses;
    }

    private static @NotNull Map<String, Object> getStringObjectMap(PatternConfig basePattern) {
        Map<String, Object> fallback = new java.util.HashMap<>();
        fallback.put("plan_type", basePattern.pattern.name().toLowerCase());
        if (basePattern.pattern == PlanPattern.COURTYARD && basePattern.ratio > 0.0) {
            fallback.put("courtyard_ratio", basePattern.ratio);
        } else if (basePattern.size > 0) {
            if (basePattern.pattern == PlanPattern.CROSS) {
                fallback.put("arm_width", basePattern.size);
            } else if (basePattern.pattern == PlanPattern.CUT_CORNERS) {
                fallback.put("corner_cut", basePattern.size);
            } else if (basePattern.pattern == PlanPattern.L_SHAPE) {
                fallback.put("l_cut", basePattern.size);
            }
        }
        if (basePattern.corner != null && !basePattern.corner.isBlank()) {
            fallback.put("l_corner", basePattern.corner);
        }
        return fallback;
    }

    private Double resolveVoidRatio(Map<String, Object> params, SemanticComponent semantic) {
        Double ratio = ComponentParamParsers.doubleOrNull(params, "void_ratio", "voidRatio");
        if (ratio != null) return clamp01(ratio);
        if (semantic != null && semantic.genome() != null && semantic.genome().structure != null) {
            Double v = semantic.genome().structure.voidRatio;
            if (v != null) return clamp01(v);
        }
        return null;
    }

    private Double resolveWindowRatio(Map<String, Object> params, SemanticComponent semantic) {
        Double ratio = ComponentParamParsers.doubleOrNull(params, "window_ratio", "windowRatio");
        if (ratio != null) return clamp01(ratio);
        return null;
    }

    private Double resolveSetbackRatio(Map<String, Object> params, SemanticComponent semantic) {
        Double ratio = ComponentParamParsers.doubleOrNull(params, "setback_ratio", "setbackRatio");
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
        int override = ComponentParamParsers.intParam(params, -1, "wall_thickness", "wallThickness");
        if (override > 0) {
            return Math.min(4, override);
        }
        Double massiveness = ComponentParamParsers.doubleOrNull(params, "massiveness");
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
        return getParamString(params, "roof_type", "roofType");
    }

    private boolean shouldEnableSteppedFacade(SemanticComponent semantic) {
        if (semantic == null || semantic.genome() == null || semantic.genome().form == null) {
            return false;
        }
        String progression = semantic.genome().form.progression;
        return "stepping".equalsIgnoreCase(progression) || "tapering".equalsIgnoreCase(progression);
    }

    private boolean isInsideFootprintBase(int x, int z, int width, int depth, FootprintShape shape, int cornerRadius) {
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
        if (r == 0) {
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

    private boolean isInsideFootprint(int x, int z, int width, int depth,
                                      FootprintShape shape, int cornerRadius,
                                      PatternConfig pattern) {
        if (!isInsideFootprintBase(x, z, width, depth, shape, cornerRadius)) {
            return false;
        }
        if (pattern == null || pattern.pattern == null || pattern.pattern == PlanPattern.NONE) {
            return true;
        }
        return switch (pattern.pattern) {
            case CROSS -> isInCrossArms(x, z, width, depth, pattern.size);
            case CUT_CORNERS -> !isInCornerCut(x, z, width, depth, pattern.size);
            case L_SHAPE -> !isInLCut(x, z, width, depth, pattern.size, pattern.corner);
            case COURTYARD -> !isInCourtyardHole(x, z, width, depth, pattern);
            default -> true;
        };
    }

    private boolean isInsideInnerFootprint(int x, int z, int width, int depth, int wallThickness,
                                           FootprintShape shape, int cornerRadius,
                                           PatternConfig pattern) {
        if (wallThickness <= 0) {
            return isInsideFootprint(x, z, width, depth, shape, cornerRadius, pattern);
        }
        if (shape == FootprintShape.RECTANGLE) {
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
                    FootprintShape.RECTANGLE,
                    0,
                    pattern
            );
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
                Math.max(0, cornerRadius - wallThickness),
                pattern
        );
    }

    private boolean isExteriorWallPosition(int x, int z, int width, int depth,
                                           FootprintShape shape, int cornerRadius,
                                           PatternConfig pattern) {
        if (!isInsideFootprint(x, z, width, depth, shape, cornerRadius, pattern)) {
            return false;
        }
        return !isInsideFootprint(x + 1, z, width, depth, shape, cornerRadius, pattern)
                || !isInsideFootprint(x - 1, z, width, depth, shape, cornerRadius, pattern)
                || !isInsideFootprint(x, z + 1, width, depth, shape, cornerRadius, pattern)
                || !isInsideFootprint(x, z - 1, width, depth, shape, cornerRadius, pattern);
    }

    private boolean isCornerPosition(int x, int z, int width, int depth,
                                     FootprintShape shape, int cornerRadius,
                                     PatternConfig pattern) {
        if (!isExteriorWallPosition(x, z, width, depth, shape, cornerRadius, pattern)) {
            return false;
        }
        int outside = 0;
        if (!isInsideFootprint(x + 1, z, width, depth, shape, cornerRadius, pattern)) outside++;
        if (!isInsideFootprint(x - 1, z, width, depth, shape, cornerRadius, pattern)) outside++;
        if (!isInsideFootprint(x, z + 1, width, depth, shape, cornerRadius, pattern)) outside++;
        if (!isInsideFootprint(x, z - 1, width, depth, shape, cornerRadius, pattern)) outside++;
        return outside >= 2;
    }

    private boolean isWindowSpacing(int x, int z, int spacing) {
        int mod = Math.max(2, spacing);
        return ((x + z) % mod) == (mod / 2);
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

    private static boolean getParamBoolean(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) return false;
        for (String key : keys) {
            if (key == null) continue;
            Object v = params.get(key);
            switch (v) {
                case Boolean b -> {
                    return b;
                }
                case String s -> {
                    String t = s.trim().toLowerCase(Locale.ROOT);
                    if (t.equals("true") || t.equals("1") || t.equals("yes")) return true;
                    if (t.equals("false") || t.equals("0") || t.equals("no")) return false;
                }
                case null, default -> {
                }
            }
        }
        return false;
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
        return Math.min(v, 1.0);
    }
}
