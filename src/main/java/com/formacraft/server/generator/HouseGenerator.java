package com.formacraft.server.generator;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.style.profile.BuildStrategy;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
import com.formacraft.common.style.StyleGenome;
import com.formacraft.common.style.StyleGenomeRegistry;
import com.formacraft.server.build.GeneratedStructure;
import com.formacraft.server.build.PlannedBlock;
import com.formacraft.server.material.PaletteResolver;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.BlockPos;
import com.formacraft.server.waterfront.WaterDetector;
import com.formacraft.server.waterfront.WaterfrontPierGenerator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 房屋生成�?
 * 支持矩形建筑、外�?内墙、门、窗户、多层楼结构、地板、屋�?
 * 可扩�?features（如阳台、柱子、装饰）
 */
public class HouseGenerator implements StructureGenerator {

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> blocks = new ArrayList<>();
        Set<BlockPos> fenceFramePositions = new HashSet<>();

        // 获取参数
        int width = Math.max(6, spec.getFootprint() != null ? spec.getFootprint().getWidth() : 8);
        int depth = Math.max(6, spec.getFootprint() != null ? spec.getFootprint().getDepth() : 6);
        int height = Math.max(4, spec.getHeight());
        int floors = Math.max(1, spec.getFloors());

        BuildingStyle style = (spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.DEFAULT;

        // 风格“基因”（数据驱动）：用于提供默认材质与部分默认参�?
        // 约定：spec.materials / spec.styleOptions 显式值永远优先�?
        StyleGenome genome = StyleGenomeRegistry.forStyle(style);
        StyleProfile profile = StyleProfileRegistry.resolve(spec);

        // ===============================
        // 注：中式建筑（MingQing Courtyard）已�?MingQingCourtyardGenerator 处理
        // GeneratorRouter 会根�?extra.template �?styleProfileId 路由到专用生成器
        // ===============================

        // 解析材质（使�?HouseMaterialResolver�?
        HouseMaterialResolver.MaterialSet materials = HouseMaterialResolver.resolveMaterials(world, spec, style, genome, profile);
        BlockState wall = materials.wall();
        BlockState floor = materials.floor();
        BlockState window = materials.window();
        BlockState roof = materials.roof();
        BlockState trim = materials.trim();
        BlockState foundation = materials.foundation();
        BlockState pillar = materials.pillar();
        BlockState roofStairs = materials.roofStairs();
        BlockState roofSlab = materials.roofSlab();
        BlockState doorLower = materials.door();
        BlockState windowBlock = materials.windowBlock();

        // 获取特�?
        boolean hasWindows = spec.getFeatures() != null && spec.getFeatures().hasWindows();
        boolean hasDoor = spec.getFeatures() != null && spec.getFeatures().hasDoor();
        boolean hasRoof = spec.getFeatures() != null && spec.getFeatures().hasRoof();
        
        // 获取风格选项（BuildingSpec 2.0）：显式 styleOptions 优先，其�?genome.params，最后硬编码兜底
        String doorStyle = (spec.getStyleOptions() != null && spec.getStyleOptions().getDoorStyle() != null)
                ? spec.getStyleOptions().getDoorStyle()
                : (genome != null && genome.params != null && genome.params.doorStyle != null ? genome.params.doorStyle : "single");

        String roofType = (spec.getStyleOptions() != null && spec.getStyleOptions().getRoofType() != null)
                ? spec.getStyleOptions().getRoofType()
                : (genome != null && genome.params != null && genome.params.roofType != null
                    ? genome.params.roofType
                    : ((profile != null && profile.rules() != null && !profile.rules().allowFlatRoof) ? "gable" : "flat"));

        // windowRatio/windowDensity: StyleOptions（显式）> genome.windowDensity > genome.windowRatio > styleProfile.rules.windowDensity > fallback
        double windowRatio;
        if (spec.getStyleOptions() != null) {
            windowRatio = spec.getStyleOptions().getWindowRatio();
        } else if (genome != null && genome.params != null && genome.params.windowDensity != null) {
            windowRatio = genome.params.windowDensity;
        } else if (genome != null && genome.params != null && genome.params.windowRatio != null) {
            windowRatio = genome.params.windowRatio;
        } else if (profile != null && profile.rules() != null) {
            windowRatio = profile.rules().windowDensity;
        } else {
            windowRatio = 0.3;
        }
        windowRatio = Math.max(0.0, Math.min(1.0, windowRatio));

        // Apply windowStyle hints (broad, cross-style):
        // - curtain_wall: more glazing
        // - slit/bars: fewer, defensive openings
        // - shoji/fence: moderate density
        String effWindowStyle = HouseMaterialResolver.resolveEffectiveWindowStyle(spec, genome, profile, style);
        String ews = (effWindowStyle == null) ? "" : effWindowStyle.trim().toLowerCase(java.util.Locale.ROOT);
        if (!ews.isBlank()) {
            if (ews.contains("curtain")) windowRatio = Math.max(windowRatio, 0.70);
            else if (ews.contains("slit") || ews.contains("bars")) windowRatio = Math.min(windowRatio, 0.14);
            else if (ews.contains("shoji")) windowRatio = Math.max(Math.min(windowRatio, 0.55), 0.32);
            else if (ews.contains("fence") || ews.contains("lattice")) windowRatio = Math.max(Math.min(windowRatio, 0.45), 0.22);
        }

        String wallPattern = (spec.getStyleOptions() != null && spec.getStyleOptions().getWallPattern() != null)
                ? spec.getStyleOptions().getWallPattern()
                : (genome != null && genome.params != null && genome.params.wallPattern != null ? genome.params.wallPattern : "uniform");

        // 中式官式：默认用“攒�?庑殿”类屋顶（近�?hipped/pyramid），并收紧开窗比�?
        if (style == BuildingStyle.ASIAN) {
            if (roofType == null || roofType.isBlank() || "flat".equalsIgnoreCase(roofType)) {
                roofType = "hipped";
            }
            if (windowRatio < 0.15) windowRatio = 0.18;
        }

        // StyleProfile strategy for wall expression (v1): influences both density and deterministic window cadence.
        BuildStrategy wallStrategy = BuildStrategy.WINDOWED_WALL;
        if (profile != null) {
            wallStrategy = profile.resolve("WALL", java.util.Collections.emptySet());
        }
        // If style prefers solid walls, clamp windowRatio down (but don't force-disable windows).
        if (hasWindows && wallStrategy == BuildStrategy.SOLID_WALL) {
            windowRatio = Math.min(windowRatio, 0.22);
        }

        // -------------------------------------
        // 1. 清空内部空间（避免房屋和山体重叠�?
        // -------------------------------------
        for (int x = -1; x <= width + 1; x++) {
            for (int z = -1; z <= depth + 1; z++) {
                for (int y = 0; y <= height + 6; y++) {
                    blocks.add(new PlannedBlock(origin.add(x, y, z), Blocks.AIR.getDefaultState()));
                }
            }
        }

        // -------------------------------------
        // 2. 生成墙体（加入：地基/转角�?腰线/多层�?门）
        // -------------------------------------
        // floorHeight (style-driven): keep the rhythm stable across styles while avoiding out-of-range floor placement.
        int baseFloorHeight = Math.max(3, height / floors);
        int maxFloorHeight = (floors > 1) ? Math.max(3, (height - 1) / (floors - 1)) : Math.max(3, height);
        int floorHeight = Math.min(baseFloorHeight, maxFloorHeight);
        if (profile != null && profile.rules() != null && profile.rules().floorHeight > 0) {
            int pref = profile.rules().floorHeight;
            floorHeight = Math.max(3, Math.min(pref, maxFloorHeight));
        }

        // Optional palette (weighted randomness): for material variation/aging.
        String paletteId = null;
        if (spec.getExtra() != null) {
            Object pid = spec.getExtra().get("paletteId");
            if (pid != null) paletteId = String.valueOf(pid).trim();
        }
        if ((paletteId == null || paletteId.isBlank()) && profile != null && profile.details() != null
                && profile.details().paletteId != null && !profile.details().paletteId.isBlank()) {
            paletteId = profile.details().paletteId.trim();
        }

        // Palette-aware finer roof semantics (best-effort, low intrusion):
        // Only apply when caller did not explicitly set roof material id.
        String roofIdFromSpec = spec.getMaterials() != null ? spec.getMaterials().getRoof() : null;
        if (paletteId != null && !paletteId.isBlank() && (roofIdFromSpec == null || roofIdFromSpec.isBlank())) {
            roofStairs = PaletteResolver.pick(world, paletteId, "ROOF_SLOPE", origin, 0xA501001L, roofStairs);
            roofStairs = PaletteResolver.pick(world, paletteId, "ROOF_TILE", origin, 0xA501002L, roofStairs);
            roofSlab = PaletteResolver.pick(world, paletteId, "FLOOR_SLAB", origin, 0xA501003L, roofSlab);
            roofSlab = PaletteResolver.pick(world, paletteId, "ROOF_TILE", origin, 0xA501004L, roofSlab);
        }

        // Door side (for compounds like gatehouses): default keeps legacy behavior (NORTH wall, z==0).
        // Layout IR: extra.layout.entranceFacing has higher priority.
        Direction doorSide = HouseGeneratorUtils.resolveDoorSide(spec);
        HouseLayoutGenerator.LayoutInfo layoutInfo = HouseLayoutGenerator.resolveLayout(spec, width, depth);
        HouseLayoutGenerator.LayoutCourtyard courtyard = layoutInfo.courtyard();

        // 2.1 地基（y=0 一圈）
        for (int x = 0; x < width; x++) {
            BlockPos p1 = origin.add(x, 0, 0);
            BlockPos p2 = origin.add(x, 0, depth - 1);
            BlockState f1 = foundation;
            BlockState f2 = foundation;
            if (paletteId != null && !paletteId.isBlank()) {
                f1 = PaletteResolver.pick(world, paletteId, "WALL_FOUNDATION", p1, (x * 31L) ^ 0xBEEF, foundation);
                f2 = PaletteResolver.pick(world, paletteId, "WALL_FOUNDATION", p2, (x * 31L) ^ 0xCAFE, foundation);
            }
            blocks.add(new PlannedBlock(p1, f1));
            blocks.add(new PlannedBlock(p2, f2));
        }
        for (int z = 0; z < depth; z++) {
            BlockPos p1 = origin.add(0, 0, z);
            BlockPos p2 = origin.add(width - 1, 0, z);
            BlockState f1 = foundation;
            BlockState f2 = foundation;
            if (paletteId != null && !paletteId.isBlank()) {
                f1 = PaletteResolver.pick(world, paletteId, "WALL_FOUNDATION", p1, (z * 31L) ^ 0xD00D, foundation);
                f2 = PaletteResolver.pick(world, paletteId, "WALL_FOUNDATION", p2, (z * 31L) ^ 0xF00D, foundation);
            }
            blocks.add(new PlannedBlock(p1, f1));
            blocks.add(new PlannedBlock(p2, f2));
        }

        // 2.2 转角柱（四角贯穿到檐口）
        for (int y = 0; y < height; y++) {
            blocks.add(new PlannedBlock(origin.add(0, y, 0), pillar));
            blocks.add(new PlannedBlock(origin.add(width - 1, y, 0), pillar));
            blocks.add(new PlannedBlock(origin.add(0, y, depth - 1), pillar));
            blocks.add(new PlannedBlock(origin.add(width - 1, y, depth - 1), pillar));
        }

        for (int y = 0; y < height; y++) {
            // 腰线/檐口：每层楼板上方一圈（更“像建筑”）
            boolean isBandY = (y > 0 && (y % floorHeight == 0)) || (y == height - 1);
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    boolean isEdgeX = (x == 0 || x == width - 1);
                    boolean isEdgeZ = (z == 0 || z == depth - 1);

                    // 外墙条件
                    if (isEdgeX || isEdgeZ) {
                        BlockPos pos = origin.add(x, y, z);

                        // 门位置逻辑（根�?doorStyle + doorSide�?
                        if (hasDoor && HouseGeneratorUtils.isDoorEdge(doorSide, x, z, width, depth)) {
                            // 单门：居中；双门/拱门：两格门�?
                            boolean onNorthSouth = (doorSide == Direction.NORTH || doorSide == Direction.SOUTH);
                            int center = onNorthSouth ? (width / 2) : (depth / 2);
                            boolean doorAxis = (doorStyle.equalsIgnoreCase("double") || doorStyle.equalsIgnoreCase("arched"))
                                    ? ((onNorthSouth ? x : z) == center || (onNorthSouth ? x : z) == center - 1)
                                    : ((onNorthSouth ? x : z) == center);
                            if (doorAxis) {
                                // choose hinge side based on axis position (best-effort)
                                boolean leftSide = ((onNorthSouth ? x : z) < center);
                                if (y == 0) {
                                    // 放门（下半）
                                    blocks.add(new PlannedBlock(pos, HouseGeneratorUtils.withDoorState(doorLower, DoubleBlockHalf.LOWER, leftSide, doorSide)));
                                    continue;
                                }
                                if (y == 1) {
                                    // 放门（上半）
                                    blocks.add(new PlannedBlock(pos, HouseGeneratorUtils.withDoorState(doorLower, DoubleBlockHalf.UPPER, leftSide, doorSide)));
                                    continue;
                                }
                                if (doorStyle.equalsIgnoreCase("arched") && y == 2) {
                                    // 拱门顶部留空 + 装饰
                                    blocks.add(new PlannedBlock(pos, Blocks.AIR.getDefaultState()));
                                    continue;
                                }
                                if (doorStyle.equalsIgnoreCase("arched") && y == 3) {
                                    // 拱门门楣（用 trim 强化轮廓；不降低门洞净高）
                                    blocks.add(new PlannedBlock(pos, trim));
                                    continue;
                                }
                            }
                        }

                        // 窗户逻辑（根�?windowRatio�?
                        if (hasWindows) {
                            // 每层 2 格高的窗带（localY=1/2�?
                            int localY = y % floorHeight;
                            boolean inWindowBand = (localY == 1 || localY == 2);
                            // SOLID_WALL：窗带更矮、更克制（只保留 1 格高�?
                            if (wallStrategy == BuildStrategy.SOLID_WALL) {
                                inWindowBand = (localY == 1);
                            }
                            // 很高的建筑：顶层再多一条细窗带（更有层次）——仅�?WINDOWED_WALL 开�?
                            if (wallStrategy != BuildStrategy.SOLID_WALL && !inWindowBand && height >= 9 && y == height - 3) inWindowBand = true;

                            if (inWindowBand) {
                                boolean preferSymmetry = (profile != null && profile.rules() != null && profile.rules().preferSymmetry);
                                boolean shouldPlaceWindow = HouseGeneratorUtils.isShouldPlaceWindow(wallStrategy, windowRatio, preferSymmetry, x, z, width, depth);
                                // 避免在门附近开窗（门所在边 + 附近轴线）�?
                                boolean nearDoor = HouseGeneratorUtils.isNearDoor(doorSide, x, z, width, depth);
                                if (shouldPlaceWindow && !nearDoor) {
                                    blocks.add(new PlannedBlock(pos, windowBlock));
                                    // Gothic mullions: place iron bars just behind the glass (inside cell), best-effort.
                                    if (profile != null && profile.details() != null && profile.details().mullions) {
                                        HouseDecorator.addMullionBehindWindow(blocks, origin, x, y, z, width, depth, doorSide);
                                    }
                                    // 窗套/窗框（v1）：
                                    // 只在窗带“上下边缘”放 trim，避免双层窗时互相覆盖�?
                                    int localYForBand = y % floorHeight;
                                    boolean isExtraHighBand = (wallStrategy != BuildStrategy.SOLID_WALL) && height >= 9 && y == height - 3;
                                    boolean isTwoHighBand = (wallStrategy != BuildStrategy.SOLID_WALL)
                                            && !isExtraHighBand
                                            && (localYForBand == 1 || localYForBand == 2);
                                    boolean isBottomOfBand = !isTwoHighBand || (localYForBand == 1);
                                    boolean isTopOfBand = !isTwoHighBand || (localYForBand == 2);

                                    if (isBottomOfBand && y > 0) blocks.add(new PlannedBlock(origin.add(x, y - 1, z), trim));
                                    if (isTopOfBand && y + 1 < height) {
                                        // Gothic: pointed arch window head (best-effort, don't clobber dense window bands)
                                        boolean pointed = (profile != null && profile.details() != null && profile.details().pointedArches);
                                        if (pointed && HouseGeneratorUtils.isPointedArchWindowSafe(wallStrategy, windowRatio, preferSymmetry, doorSide, x, z, width, depth)) {
                                            HouseDecorator.addPointedWindowFrame(blocks, origin, x, y + 1, z, width, depth, trim, roofStairs);
                                        } else {
                                            blocks.add(new PlannedBlock(origin.add(x, y + 1, z), trim));
                                        }
                                    }

                                    // fence 栅窗：额外补左右窗套（同一墙面内），但避免覆盖相邻�?�?角柱
                                    if (HouseGeneratorUtils.isFenceLikeWindow(windowBlock)) {
                                        if (isEdgeZ) {
                                            HouseGeneratorUtils.tryCollectFenceFrame(fenceFramePositions, origin, wallStrategy, windowRatio, preferSymmetry,
                                                    x - 1, y, z, width, depth);
                                            HouseGeneratorUtils.tryCollectFenceFrame(fenceFramePositions, origin, wallStrategy, windowRatio, preferSymmetry,
                                                    x + 1, y, z, width, depth);
                                        } else {
                                            HouseGeneratorUtils.tryCollectFenceFrame(fenceFramePositions, origin, wallStrategy, windowRatio, preferSymmetry,
                                                    x, y, z - 1, width, depth);
                                            HouseGeneratorUtils.tryCollectFenceFrame(fenceFramePositions, origin, wallStrategy, windowRatio, preferSymmetry,
                                                    x, y, z + 1, width, depth);
                                        }
                                    }
                                    continue;
                                }
                            }
                        }

                        // 腰线/檐口优先�?trim（视觉提升很大）
                        if (isBandY) {
                            blocks.add(new PlannedBlock(pos, trim));
                            continue;
                        }

                        // 墙体花纹（striped/gradient/random�?
                        BlockState wallToUse = HouseGeneratorUtils.applyWallPattern(wall, trim, foundation, wallPattern, y, height);
                        // Palette override only for "base wall" material; do not touch trim/foundation/pillars/windows/doors.
                        if (paletteId != null && !paletteId.isBlank() && wallToUse == wall) {
                            long salt = (x * 1315423911L) ^ (z * 2654435761L) ^ (y * 97531L);
                            wallToUse = PaletteResolver.pick(world, paletteId, "WALL_BASE", pos, salt, wallToUse);
                        }

                        // Facade composition hint (cross-style, low intrusion): only affects plain wall cells.
                        String facadeProfile = null;
                        try {
                            if (profile != null && profile.details() != null) facadeProfile = profile.details().facadeProfile;
                        } catch (Throwable ignored) {}
                        if (facadeProfile != null && !facadeProfile.isBlank()) {
                            wallToUse = HouseGeneratorUtils.applyFacadeProfileToWallCell(
                                    wallToUse, wall, trim, foundation,
                                    facadeProfile, doorSide, x, y, z, width, depth,
                                    hasDoor, floorHeight
                            );
                        }

                        // 普通墙
                        blocks.add(new PlannedBlock(pos, wallToUse));
                    }
                }
            }
        }

        // -------------------------------------
        // 3. 地板/天花（每层一层）
        // -------------------------------------
        // fence 栅窗窗套（左�?trim）统一在墙体生成之后落位，避免被后续墙体迭代覆盖�?
        if (!fenceFramePositions.isEmpty()) {
            for (BlockPos fp : fenceFramePositions) {
                blocks.add(new PlannedBlock(fp, trim));
            }
        }

        for (int f = 0; f < floors; f++) {
            int y0 = f * floorHeight;

            for (int x = 1; x < width - 1; x++) {
                for (int z = 1; z < depth - 1; z++) {
                    if (courtyard.enabled() && courtyard.containsInterior(x, z)) continue;
                    BlockPos fp = origin.add(x, y0, z);
                    BlockState fl = floor;
                    if (paletteId != null && !paletteId.isBlank()) {
                        long salt = (x * 31L) ^ (z * 17L) ^ (y0 * 13L);
                        fl = PaletteResolver.pick(world, paletteId, "FLOORING", fp, salt, floor);
                    }
                    blocks.add(new PlannedBlock(fp, fl));
                }
            }

            // 天花（除了顶层）：用 trim 做“梁/檐线”，更有层次
            if (f < floors - 1) {
                int cy = y0 + floorHeight;
                if (cy > 0 && cy < height) {
                    for (int x = 1; x < width - 1; x++) {
                        blocks.add(new PlannedBlock(origin.add(x, cy, 1), trim));
                        blocks.add(new PlannedBlock(origin.add(x, cy, depth - 2), trim));
                    }
                    for (int z = 1; z < depth - 1; z++) {
                        blocks.add(new PlannedBlock(origin.add(1, cy, z), trim));
                        blocks.add(new PlannedBlock(origin.add(width - 2, cy, z), trim));
                    }
                }
            }
        }

        // -------------------------------------
        // 3.5 内部分区（Layout IR: extra.layout.plan�?
        // -------------------------------------
        // 使用 HouseLayoutGenerator 生成内部分区
        HouseLayoutGenerator.generatePartitions(blocks, origin, width, depth, height, floors, floorHeight,
                wall, trim, doorSide, layoutInfo);

        // -------------------------------------
        // 4. 屋顶（根�?roofType；加入：檐口/楼梯屋顶/平顶女儿墙）
        // -------------------------------------
        if (hasRoof) {
            // �?styleOptions 获取屋顶类型（向后兼�?extra�?
            String actualRoofType = roofType;
            BuildStrategy roofStrategy;
            if (profile != null) {
                roofStrategy = profile.resolve("ROOF", java.util.Collections.emptySet());
            } else {
                roofStrategy = ("flat".equalsIgnoreCase(actualRoofType)) ? BuildStrategy.ROOF_FLAT : BuildStrategy.ROOF_SLOPE;
            }

            boolean roofExplicit = (spec.getStyleOptions() != null && spec.getStyleOptions().getRoofType() != null);
            boolean roofFromExtra = false;
            if (actualRoofType == null || actualRoofType.isEmpty()) {
                if (spec.getExtra() != null && spec.getExtra().containsKey("roofType")) {
                    actualRoofType = String.valueOf(spec.getExtra().get("roofType"));
                    roofFromExtra = true;
                } else {
                    // 完全没有 roofType 时：�?StyleProfile �?ROOF 策略决定默认
                    if (roofStrategy == BuildStrategy.ROOF_FLAT) {
                        actualRoofType = "flat";
                    } else {
                        // prefer style profile hint when available (CatalogStyleProfile uses StyleProfileCatalog.defaults.geometry.roof.type)
                        if (profile != null && profile.rules() != null && profile.rules().roofTypeHint != null && !profile.rules().roofTypeHint.isBlank()) {
                            actualRoofType = profile.rules().roofTypeHint;
                        } else {
                            actualRoofType = (style == BuildingStyle.ASIAN) ? "hipped" : "gable";
                        }
                    }
                }
            }
            // normalize aliases
            if ("hip".equalsIgnoreCase(actualRoofType)) actualRoofType = "hipped";
            // Chinese hip-and-gable (歇山): keep it distinguishable from plain hipped roofs
            if (actualRoofType != null) {
                String rt = actualRoofType.trim().toLowerCase(java.util.Locale.ROOT);
                if (rt.equals("xie_shan") || rt.equals("xieshan") || rt.equals("xie-shan") || rt.equals("xie shan") || rt.contains("xie")) {
                    actualRoofType = "xie_shan";
                }
            }
            // 如果不是显式指定（StyleOptions/extra），则允�?StyleProfile 修正"�?�?大方�?
            if (!roofExplicit && !roofFromExtra) {
                if (roofStrategy == BuildStrategy.ROOF_FLAT) {
                    actualRoofType = "flat";
                } else if (roofStrategy == BuildStrategy.ROOF_SLOPE && "flat".equalsIgnoreCase(actualRoofType)) {
                    actualRoofType = (style == BuildingStyle.ASIAN) ? "hipped" : "gable";
                }
            }
            
            // 使用 HouseRoofGenerator 生成屋顶
            HouseRoofGenerator.generateRoof(blocks, origin, width, depth, height, actualRoofType,
                    roof, roofStairs, roofSlab, trim, style, profile, doorSide, spec, paletteId);
            
            // Huizhou (徽派) phenotype: 马头墙（阶梯状山墙），在双坡屋顶两端�?墙高逐级上升"的收边�?
            // 注意：需要在屋顶生成后调用，因为需要知道屋顶高�?
            if (HorseHeadWallGenerator.isHuizhouStyle(spec, paletteId) 
                    && ("gable".equalsIgnoreCase(actualRoofType) || roofStrategy == BuildStrategy.ROOF_SLOPE
                    || style == BuildingStyle.MEDIEVAL || style == BuildingStyle.RUSTIC)) {
                int roofHeight = Math.min(width / 2 + 1, 7);
                HorseHeadWallGenerator.generate(blocks, origin, width, depth, height, roofHeight, wall, trim, roofSlab, doorSide);
            }

            // Layout IR: courtyard opening (best-effort)
            // We carve an open shaft by placing AIR blocks after roof generation.
            if (courtyard.enabled()) {
                int yMax = height + 14; // conservative upper bound across roof types
                for (int y = 1; y <= yMax; y++) {
                    for (int x = courtyard.x0(); x <= courtyard.x1(); x++) {
                        for (int z = courtyard.z0(); z <= courtyard.z1(); z++) {
                            blocks.add(new PlannedBlock(origin.add(x, y, z), Blocks.AIR.getDefaultState()));
                        }
                    }
                }
            }
        }

        // -------------------------------------
        // 4.5 门口/围墙照明（可�?extra.features 控制�?
        // -------------------------------------
        if (hasDoor) {
            String lightingMode = "door";     // none | door | perimeter
            String lightingType = "torch";    // torch | lantern
            int lightingSpacing = 6;          // only used for perimeter
            boolean banner = false;           // door banners / crests
            String bannerColor = "red";       // red/black/white/blue...
            if (spec.getExtra() != null) {
                Object lm = spec.getExtra().get("lighting");
                Object lt = spec.getExtra().get("lightingType");
                Object ls = spec.getExtra().get("lightingSpacing");
                Object bn = spec.getExtra().get("banner");
                Object bc = spec.getExtra().get("bannerColor");
                if (lm != null) lightingMode = String.valueOf(lm).trim().toLowerCase();
                if (lt != null) lightingType = String.valueOf(lt).trim().toLowerCase();
                if (ls != null) {
                    try {
                        int v = (ls instanceof Number n) ? n.intValue() : Integer.parseInt(String.valueOf(ls).trim());
                        lightingSpacing = Math.max(2, Math.min(12, v));
                    } catch (Exception ignored) {}
                }
                if (bn instanceof Boolean b) banner = b;
                else if (bn != null) {
                    String s = String.valueOf(bn).trim().toLowerCase();
                    if (!s.isEmpty()) banner = (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on"));
                }
                if (bc != null) {
                    String s = String.valueOf(bc).trim().toLowerCase();
                    if (!s.isEmpty()) bannerColor = s;
                }
            }

            // 使用 HouseDecorator 生成照明
            HouseDecorator.generateLighting(blocks, origin, width, depth, foundation, doorSide,
                    lightingMode, lightingType, lightingSpacing, banner ? bannerColor : null, paletteId, world);
        }

        // -------------------------------------
        // 5. 装饰元素（使�?HouseDecorator�?
        // -------------------------------------
        // --- Facade component library (Greco-Roman / Gothic), best-effort ---
        try {
            if (profile != null && profile.details() != null) {
                HouseDecorator.decorate(blocks, origin, world, spec, width, depth, height,
                        wall, trim, foundation, pillar, roof, roofStairs, roofSlab, windowBlock,
                        paletteId, profile.details(), layoutInfo);
            }
        } catch (Throwable ignored) {}

        // 临水码头自动关联逻辑（可选）
        try {
            generateWaterfrontPierIfNeeded(blocks, world, spec, origin, width, depth, height, doorSide, paletteId);
        } catch (Exception e) {
            // 静默失败，不影响主建筑生�?
        }

        String description = String.format("House (%s, %dx%dx%d, floors=%d)", 
                spec.getType(), width, height, depth, floors);

        return new GeneratedStructure(
                null, // owner 将在 BuildExecutionService 中设�?
                origin,
                description,
                blocks
        );
    }



    /**
     * 生成临水码头（如果满足条件）
     */
    private static void generateWaterfrontPierIfNeeded(
            List<PlannedBlock> blocks, ServerWorld world, BuildingSpec spec,
            BlockPos origin, int width, int depth, int height, Direction doorSide, String paletteId) {
        
        // 检查是否启用（通过配置控制，默认启用）
        if (spec != null && spec.getExtra() != null) {
            Object waterfrontObj = spec.getExtra().get("waterfront");
            if (waterfrontObj instanceof java.util.Map<?, ?> wf) {
                Object enabled = wf.get("enabled");
                if (enabled != null && !Boolean.parseBoolean(String.valueOf(enabled))) {
                    return; // 禁用
                }
            }
        }
        
        // 检测附近水�?
        WaterDetector.WaterDetectionResult waterResult = WaterDetector.detectNearbyWater(
            world, origin, width, depth, 8, 8
        );
        
        if (!waterResult.hasWater()) {
            return; // 没有附近水体
        }
        
        // 计算建筑出入口位�?
        BlockPos buildingExit = calculateDoorExitPosition(origin, width, depth, doorSide);
        
        // 寻找最佳接驳点
        WaterDetector.PierAnchor anchor = WaterDetector.findBestPierAnchor(
            world, buildingExit, doorSide, 8
        );
        
        if (anchor == null) {
            return; // 未找到合适的接驳�?
        }
        
        // 生成码头
        List<PlannedBlock> pierBlocks = WaterfrontPierGenerator.generate(
            world, anchor, paletteId, 3  // 默认宽度3�?
        );
        
        blocks.addAll(pierBlocks);
    }
    
    /**
     * 计算建筑出入口位置（门外的位置）
     */
    private static BlockPos calculateDoorExitPosition(BlockPos origin, int width, int depth, Direction doorSide) {
        int cx = width / 2;
        int cz = depth / 2;
        
        return switch (doorSide) {
            case NORTH -> origin.add(cx, 0, -1);  // 门外1�?
            case SOUTH -> origin.add(cx, 0, depth);
            case EAST -> origin.add(width, 0, cz);
            case WEST -> origin.add(-1, 0, cz);
            default -> origin.add(cx, 0, -1);
        };
    }

}

