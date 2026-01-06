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
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.BlockPos;
import com.formacraft.server.generator.HorseHeadWallGenerator;
import com.formacraft.server.waterfront.WaterDetector;
import com.formacraft.server.waterfront.WaterfrontPierGenerator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 房屋生成器
 * 支持矩形建筑、外墙/内墙、门、窗户、多层楼结构、地板、屋顶
 * 可扩展 features（如阳台、柱子、装饰）
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

        // 风格“基因”（数据驱动）：用于提供默认材质与部分默认参数
        // 约定：spec.materials / spec.styleOptions 显式值永远优先。
        StyleGenome genome = StyleGenomeRegistry.forStyle(style);
        StyleProfile profile = StyleProfileRegistry.resolve(spec);

        // ===============================
        // Ming/Qing 官式中式宅院（优先实现）
        // ===============================
        // ASIAN 作为“中式”总开关；当占地足够大时，生成四合院（围墙+门楼+主殿+厢房）
        if (style == BuildingStyle.ASIAN && width >= 16 && depth >= 16) {
            generateMingQingCourtyard(spec, origin, world, blocks, width, depth);
            String description = String.format("MingQing Courtyard (ASIAN, %dx%d)", width, depth);
            return new GeneratedStructure(null, origin, description, blocks);
        }

        // 获取材质
        String wallId = spec.getMaterials() != null ? spec.getMaterials().getWall() : null;
        String floorId = spec.getMaterials() != null ? spec.getMaterials().getFloor() : null;
        String windowId = spec.getMaterials() != null ? spec.getMaterials().getWindow() : null;
        String roofId = spec.getMaterials() != null ? spec.getMaterials().getRoof() : null;

        String pWall = (profile != null && profile.palette() != null) ? profile.palette().wall : null;
        String pFloor = (profile != null && profile.palette() != null) ? profile.palette().floor : null;
        String pWindow = (profile != null && profile.palette() != null) ? profile.palette().window : null;
        String pRoof = (profile != null && profile.palette() != null) ? profile.palette().roof : null;

        BlockState wall = getStateOrDefault(world, wallId,
                getStateOrDefault(world,
                        genome != null && genome.palette != null ? genome.palette.wall : pWall,
                        defaultWall(style)));
        BlockState floor = getStateOrDefault(world, floorId,
                getStateOrDefault(world,
                        genome != null && genome.palette != null ? genome.palette.floor : pFloor,
                        defaultFloor(style)));
        BlockState window = getStateOrDefault(world, windowId,
                getStateOrDefault(world,
                        genome != null && genome.palette != null ? genome.palette.window : pWindow,
                        defaultWindow(style)));
        BlockState roof = getStateOrDefault(world, roofId,
                getStateOrDefault(world,
                        genome != null && genome.palette != null ? genome.palette.roof : pRoof,
                        defaultRoof(style)));

        // 装饰/细节材质（不要求模型显式提供，但能显著提升观感）
        BlockState trim = getStateOrDefault(world,
                genome != null && genome.palette != null ? genome.palette.trim : (profile != null && profile.palette() != null ? profile.palette().trim : null),
                defaultTrim(style, wall));
        BlockState foundation = getStateOrDefault(world,
                genome != null && genome.palette != null ? genome.palette.foundation : (profile != null && profile.palette() != null ? profile.palette().foundation : null),
                defaultFoundation(style, wall));
        BlockState pillar = getStateOrDefault(world,
                genome != null && genome.palette != null ? genome.palette.pillar : (profile != null && profile.palette() != null ? profile.palette().pillar : null),
                defaultPillar(style));
        BlockState roofStairs = defaultRoofStairs(style, roof);
        BlockState roofSlab = defaultRoofSlab(style, roof);
        BlockState windowBlock = resolveWindowByStyleOption(world, style, spec, genome, profile, window, pillar, trim);
        BlockState doorLower = defaultDoor(style);

        // 获取特性
        boolean hasWindows = spec.getFeatures() != null && spec.getFeatures().hasWindows();
        boolean hasDoor = spec.getFeatures() != null && spec.getFeatures().hasDoor();
        boolean hasRoof = spec.getFeatures() != null && spec.getFeatures().hasRoof();
        
        // 获取风格选项（BuildingSpec 2.0）：显式 styleOptions 优先，其次 genome.params，最后硬编码兜底
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
        String effWindowStyle = resolveEffectiveWindowStyle(spec, genome, profile, style);
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

        // 中式官式：默认用“攒尖/庑殿”类屋顶（近似 hipped/pyramid），并收紧开窗比例
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
        // 1. 清空内部空间（避免房屋和山体重叠）
        // -------------------------------------
        for (int x = -1; x <= width + 1; x++) {
            for (int z = -1; z <= depth + 1; z++) {
                for (int y = 0; y <= height + 6; y++) {
                    blocks.add(new PlannedBlock(origin.add(x, y, z), Blocks.AIR.getDefaultState()));
                }
            }
        }

        // -------------------------------------
        // 2. 生成墙体（加入：地基/转角柱/腰线/多层窗/门）
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
        if (paletteId != null && !paletteId.isBlank() && (roofId == null || roofId.isBlank())) {
            roofStairs = PaletteResolver.pick(world, paletteId, "ROOF_SLOPE", origin, 0xA501001L, roofStairs);
            roofStairs = PaletteResolver.pick(world, paletteId, "ROOF_TILE", origin, 0xA501002L, roofStairs);
            roofSlab = PaletteResolver.pick(world, paletteId, "FLOOR_SLAB", origin, 0xA501003L, roofSlab);
            roofSlab = PaletteResolver.pick(world, paletteId, "ROOF_TILE", origin, 0xA501004L, roofSlab);
        }

        // Door side (for compounds like gatehouses): default keeps legacy behavior (NORTH wall, z==0).
        // Layout IR: extra.layout.entranceFacing has higher priority.
        Direction doorSide = resolveDoorSide(spec);
        LayoutCourtyard courtyard = resolveLayoutCourtyard(spec, width, depth);
        String layoutPlan = resolveLayoutPlan(spec);

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

                        // 门位置逻辑（根据 doorStyle + doorSide）
                        if (hasDoor && isDoorEdge(doorSide, x, z, width, depth)) {
                            // 单门：居中；双门/拱门：两格门洞
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
                                    blocks.add(new PlannedBlock(pos, withDoorState(doorLower, DoubleBlockHalf.LOWER, leftSide, doorSide)));
                                    continue;
                                }
                                if (y == 1) {
                                    // 放门（上半）
                                    blocks.add(new PlannedBlock(pos, withDoorState(doorLower, DoubleBlockHalf.UPPER, leftSide, doorSide)));
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

                        // 窗户逻辑（根据 windowRatio）
                        if (hasWindows) {
                            // 每层 2 格高的窗带（localY=1/2）
                            int localY = y % floorHeight;
                            boolean inWindowBand = (localY == 1 || localY == 2);
                            // SOLID_WALL：窗带更矮、更克制（只保留 1 格高）
                            if (wallStrategy == BuildStrategy.SOLID_WALL) {
                                inWindowBand = (localY == 1);
                            }
                            // 很高的建筑：顶层再多一条细窗带（更有层次）——仅对 WINDOWED_WALL 开启
                            if (wallStrategy != BuildStrategy.SOLID_WALL && !inWindowBand && height >= 9 && y == height - 3) inWindowBand = true;

                            if (inWindowBand) {
                                boolean preferSymmetry = (profile != null && profile.rules() != null && profile.rules().preferSymmetry);
                                boolean shouldPlaceWindow = isShouldPlaceWindow(wallStrategy, windowRatio, preferSymmetry, x, z, width, depth);
                                // 避免在门附近开窗（门所在边 + 附近轴线）
                                boolean nearDoor = isNearDoor(doorSide, x, z, width, depth);
                                if (shouldPlaceWindow && !nearDoor) {
                                    blocks.add(new PlannedBlock(pos, windowBlock));
                                    // Gothic mullions: place iron bars just behind the glass (inside cell), best-effort.
                                    if (profile != null && profile.details() != null && profile.details().mullions) {
                                        HouseDecorator.addMullionBehindWindow(blocks, origin, x, y, z, width, depth, doorSide);
                                    }
                                    // 窗套/窗框（v1）：
                                    // 只在窗带“上下边缘”放 trim，避免双层窗时互相覆盖。
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
                                        if (pointed && isPointedArchWindowSafe(wallStrategy, windowRatio, preferSymmetry, x, z, width, depth, doorSide)) {
                                            HouseDecorator.addPointedWindowFrame(blocks, origin, x, y + 1, z, width, depth, trim, roofStairs);
                                        } else {
                                            blocks.add(new PlannedBlock(origin.add(x, y + 1, z), trim));
                                        }
                                    }

                                    // fence 栅窗：额外补左右窗套（同一墙面内），但避免覆盖相邻窗/门/角柱
                                    if (isFenceLikeWindow(windowBlock)) {
                                        if (isEdgeZ) {
                                            tryCollectFenceFrame(fenceFramePositions, origin, wallStrategy, windowRatio, preferSymmetry,
                                                    x - 1, y, z, width, depth);
                                            tryCollectFenceFrame(fenceFramePositions, origin, wallStrategy, windowRatio, preferSymmetry,
                                                    x + 1, y, z, width, depth);
                                        } else {
                                            tryCollectFenceFrame(fenceFramePositions, origin, wallStrategy, windowRatio, preferSymmetry,
                                                    x, y, z - 1, width, depth);
                                            tryCollectFenceFrame(fenceFramePositions, origin, wallStrategy, windowRatio, preferSymmetry,
                                                    x, y, z + 1, width, depth);
                                        }
                                    }
                                    continue;
                                }
                            }
                        }

                        // 腰线/檐口优先用 trim（视觉提升很大）
                        if (isBandY) {
                            blocks.add(new PlannedBlock(pos, trim));
                            continue;
                        }

                        // 墙体花纹（striped/gradient/random）
                        BlockState wallToUse = applyWallPattern(wall, trim, foundation, wallPattern, y, height);
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
                            wallToUse = applyFacadeProfileToWallCell(
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
        // fence 栅窗窗套（左右 trim）统一在墙体生成之后落位，避免被后续墙体迭代覆盖。
        if (!fenceFramePositions.isEmpty()) {
            for (BlockPos fp : fenceFramePositions) {
                blocks.add(new PlannedBlock(fp, trim));
            }
        }

        for (int f = 0; f < floors; f++) {
            int y0 = f * floorHeight;

            for (int x = 1; x < width - 1; x++) {
                for (int z = 1; z < depth - 1; z++) {
                    if (courtyard.enabled && courtyard.containsInterior(x, z)) continue;
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
        // 3.5 内部分区（Layout IR: extra.layout.plan）
        // -------------------------------------
        // NOTE: best-effort zoning.
        // - ring_corridor is designed to work WITH courtyard.
        // - other plans are skipped when courtyard is enabled to avoid conflicting geometry.
        if (courtyard.enabled && isLayoutPlanRingCorridor(layoutPlan)) {
            addRingCorridorPartitions(blocks, origin, width, depth, height, floors, floorHeight, wall, trim, courtyard, doorSide);
        } else if (!courtyard.enabled) {
            addInteriorPartitions(blocks, origin, width, depth, height, floors, floorHeight, wall, trim, doorSide, layoutPlan);
        }

        // -------------------------------------
        // 4. 屋顶（根据 roofType；加入：檐口/楼梯屋顶/平顶女儿墙）
        // -------------------------------------
        if (hasRoof) {
            // 从 styleOptions 获取屋顶类型（向后兼容 extra）
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
                    // 完全没有 roofType 时：由 StyleProfile 的 ROOF 策略决定默认
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
            // 如果不是显式指定（StyleOptions/extra），则允许 StyleProfile 修正"平/坡"大方向
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
            
            // Huizhou (徽派) phenotype: 马头墙（阶梯状山墙），在双坡屋顶两端做"墙高逐级上升"的收边。
            // 注意：需要在屋顶生成后调用，因为需要知道屋顶高度
            if (HorseHeadWallGenerator.isHuizhouStyle(spec, paletteId) 
                    && ("gable".equalsIgnoreCase(actualRoofType) || roofStrategy == BuildStrategy.ROOF_SLOPE
                    || style == BuildingStyle.MEDIEVAL || style == BuildingStyle.RUSTIC)) {
                int roofHeight = Math.min(width / 2 + 1, 7);
                HorseHeadWallGenerator.generate(blocks, origin, width, depth, height, roofHeight, wall, trim, roofSlab, doorSide);
            }

            // Layout IR: courtyard opening (best-effort)
            // We carve an open shaft by placing AIR blocks after roof generation.
            if (courtyard.enabled) {
                int yMax = height + 14; // conservative upper bound across roof types
                for (int y = 1; y <= yMax; y++) {
                    for (int x = courtyard.x0; x <= courtyard.x1; x++) {
                        for (int z = courtyard.z0; z <= courtyard.z1; z++) {
                            blocks.add(new PlannedBlock(origin.add(x, y, z), Blocks.AIR.getDefaultState()));
                        }
                    }
                }
            }
        }

        // -------------------------------------
        // 4.5 门口/围墙照明（可由 extra.features 控制）
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
        // 5. 装饰元素（使用 HouseDecorator）
        // -------------------------------------
        // --- Facade component library (Greco-Roman / Gothic), best-effort ---
        try {
            if (profile != null && profile.details() != null) {
                HouseDecorator.decorate(blocks, origin, world, spec, width, depth, height,
                        wall, trim, foundation, pillar, roof, roofStairs, roofSlab, windowBlock,
                        paletteId, profile.details());
            }
        } catch (Throwable ignored) {}

        // 临水码头自动关联逻辑（可选）
        try {
            generateWaterfrontPierIfNeeded(blocks, world, spec, origin, width, depth, height, doorSide, paletteId);
        } catch (Exception e) {
            // 静默失败，不影响主建筑生成
        }

        String description = String.format("House (%s, %dx%dx%d, floors=%d)", 
                spec.getType(), width, height, depth, floors);

        return new GeneratedStructure(
                null, // owner 将在 BuildExecutionService 中设置
                origin,
                description,
                blocks
        );
    }

    private static boolean isShouldPlaceWindow(BuildStrategy wallStrategy, double windowRatio, boolean preferSymmetry,
                                               int x, int z, int width, int depth) {
        // Only meaningful on exterior ring; caller already checks edges.
        // Derive spacing from density suggestion (still respects explicit windowRatio values).
        int spacing;
        if (windowRatio >= 0.65) spacing = 2;
        else if (windowRatio >= 0.38) spacing = 3;
        else spacing = 4;

        // Don't place windows at corners (corner pillars take that space and look better without glass).
        boolean corner = (x == 0 || x == width - 1) && (z == 0 || z == depth - 1);
        if (corner) return false;

        // Keep a small margin away from corners for better rhythm
        if (x <= 1 || z <= 1 || x >= width - 2 || z >= depth - 2) {
            // still allow on the outermost ring if it's not a corner, but be conservative for SOLID_WALL
            if (wallStrategy == BuildStrategy.SOLID_WALL) return false;
        }

        boolean onNorthSouth = (z == 0 || z == depth - 1);
        boolean onWestEast = (x == 0 || x == width - 1);

        if (wallStrategy == BuildStrategy.SOLID_WALL) {
            // Solid walls: sparse, centered rhythm (stronger silhouette).
            if (onNorthSouth) {
                int cx = width / 2;
                return (Math.abs(x - cx) % spacing == 0) && x >= 2 && x <= width - 3;
            }
            if (onWestEast) {
                int cz = depth / 2;
                return (Math.abs(z - cz) % spacing == 0) && z >= 2 && z <= depth - 3;
            }
            return false;
        }

        // WINDOWED_WALL (default): regular cadence along edges.
        if (preferSymmetry) {
            int cx = width / 2;
            int cz = depth / 2;
            if (onNorthSouth) return (Math.abs(x - cx) % spacing == 0) && x >= 2 && x <= width - 3;
            if (onWestEast) return (Math.abs(z - cz) % spacing == 0) && z >= 2 && z <= depth - 3;
            return false;
        }
        if (onNorthSouth) return (x % spacing == 0) && x >= 2 && x <= width - 3;
        if (onWestEast) return (z % spacing == 0) && z >= 2 && z <= depth - 3;
        return false;
    }

    private static BlockState withFacingIfPossible(BlockState state, Direction facing) {
        if (state == null) return null;
        try {
            if (state.contains(Properties.HORIZONTAL_FACING)) {
                return state.with(Properties.HORIZONTAL_FACING, facing);
            }
        } catch (Throwable ignored) {}
        return state;
    }

    private static BlockState withDoorState(BlockState door, DoubleBlockHalf half, boolean leftSide, Direction facing) {
        BlockState s = door;
        try {
            if (s.contains(Properties.HORIZONTAL_FACING)) s = s.with(Properties.HORIZONTAL_FACING, facing != null ? facing : Direction.NORTH);
        } catch (Throwable ignored) {}
        try {
            if (s.contains(Properties.DOUBLE_BLOCK_HALF)) s = s.with(Properties.DOUBLE_BLOCK_HALF, half);
        } catch (Throwable ignored) {}
        // 双门时用相反铰链，避免都向同一侧开（即便不完美，也比默认好）
        try {
            if (s.contains(Properties.DOOR_HINGE)) {
                s = s.with(Properties.DOOR_HINGE, leftSide ? net.minecraft.block.enums.DoorHinge.LEFT : net.minecraft.block.enums.DoorHinge.RIGHT);
            }
        } catch (Throwable ignored) {}
        return s;
    }

    private static void addDoorLighting(List<PlannedBlock> blocks, BlockPos origin, int width, int depth, BlockState foundation,
                                        Direction doorSide, String lightingType) {
        // Two lights flanking the door, placed without overwriting wall blocks.
        boolean onNorthSouth = (doorSide == Direction.NORTH || doorSide == Direction.SOUTH);
        int center = onNorthSouth ? (width / 2) : (depth / 2);
        int y = 2;

        // positions in the "air" adjacent to the wall: inside by default for wall torches, outside posts for lanterns
        int off = 2;
        int a0 = center - off;
        int a1 = center + off;
        if (onNorthSouth) {
            a0 = Math.max(2, Math.min(width - 3, a0));
            a1 = Math.max(2, Math.min(width - 3, a1));
        } else {
            a0 = Math.max(2, Math.min(depth - 3, a0));
            a1 = Math.max(2, Math.min(depth - 3, a1));
        }

        if ("lantern".equals(lightingType)) {
            // Outside posts: foundation base + fence + lantern
            placeLanternPost(blocks, origin, foundation, doorSide, a0, y, width, depth);
            if (a1 != a0) placeLanternPost(blocks, origin, foundation, doorSide, a1, y, width, depth);
        } else {
            // Wall torch: place at inside air cell and face toward the wall block.
            BlockState wallTorch = Blocks.WALL_TORCH.getDefaultState();
            wallTorch = withFacingIfPossible(wallTorch, doorSide);
            BlockPos p0 = doorTorchPos(origin, doorSide, a0, y, width, depth);
            blocks.add(new PlannedBlock(p0, wallTorch));
            if (a1 != a0) {
                BlockPos p1 = doorTorchPos(origin, doorSide, a1, y, width, depth);
                blocks.add(new PlannedBlock(p1, wallTorch));
            }
        }
    }

    private static void addPerimeterLighting(List<PlannedBlock> blocks, BlockPos origin, int width, int depth,
                                            Direction doorSide, String lightingType, int spacing) {
        // MVP: perimeter lighting only for torches (lantern perimeter is more intrusive and terrain-sensitive).
        if (!"torch".equals(lightingType)) return;
        BlockState wallTorch = Blocks.WALL_TORCH.getDefaultState();
        int y = 2;

        // north wall (inside z=1, facing NORTH to attach to z=0)
        wallTorch = withFacingIfPossible(wallTorch, Direction.NORTH);
        for (int x = 2; x <= width - 3; x += spacing) {
            if (doorSide == Direction.NORTH && isNearDoor(Direction.NORTH, x, 0, width, depth)) continue;
            blocks.add(new PlannedBlock(origin.add(x, y, 1), wallTorch));
        }
        // south wall (inside z=depth-2, facing SOUTH)
        wallTorch = withFacingIfPossible(Blocks.WALL_TORCH.getDefaultState(), Direction.SOUTH);
        for (int x = 2; x <= width - 3; x += spacing) {
            if (doorSide == Direction.SOUTH && isNearDoor(Direction.SOUTH, x, depth - 1, width, depth)) continue;
            blocks.add(new PlannedBlock(origin.add(x, y, depth - 2), wallTorch));
        }
        // west wall (inside x=1, facing WEST)
        wallTorch = withFacingIfPossible(Blocks.WALL_TORCH.getDefaultState(), Direction.WEST);
        for (int z = 2; z <= depth - 3; z += spacing) {
            if (doorSide == Direction.WEST && isNearDoor(Direction.WEST, 0, z, width, depth)) continue;
            blocks.add(new PlannedBlock(origin.add(1, y, z), wallTorch));
        }
        // east wall (inside x=width-2, facing EAST)
        wallTorch = withFacingIfPossible(Blocks.WALL_TORCH.getDefaultState(), Direction.EAST);
        for (int z = 2; z <= depth - 3; z += spacing) {
            if (doorSide == Direction.EAST && isNearDoor(Direction.EAST, width - 1, z, width, depth)) continue;
            blocks.add(new PlannedBlock(origin.add(width - 2, y, z), wallTorch));
        }
    }

    private static BlockPos doorTorchPos(BlockPos origin, Direction doorSide, int axis, int y, int width, int depth) {
        return switch (doorSide) {
            case NORTH -> origin.add(axis, y, 1);
            case SOUTH -> origin.add(axis, y, depth - 2);
            case WEST -> origin.add(1, y, axis);
            case EAST -> origin.add(width - 2, y, axis);
            default -> origin.add(axis, y, 1);
        };
    }

    private static void placeLanternPost(List<PlannedBlock> blocks, BlockPos origin, BlockState foundation,
                                         Direction doorSide, int axis, int lanternY, int width, int depth) {
        // outside coordinate (just outside wall), with a small post and lantern
        BlockPos base = switch (doorSide) {
            case NORTH -> origin.add(axis, 0, -1);
            case SOUTH -> origin.add(axis, 0, depth);
            case WEST -> origin.add(-1, 0, axis);
            case EAST -> origin.add(width, 0, axis);
            default -> origin.add(axis, 0, -1);
        };
        blocks.add(new PlannedBlock(base, foundation));
        blocks.add(new PlannedBlock(base.up(), Blocks.OAK_FENCE.getDefaultState()));
        blocks.add(new PlannedBlock(base.up(2), Blocks.LANTERN.getDefaultState()));
    }

    private static void addDoorBanners(List<PlannedBlock> blocks, BlockPos origin, int width, int depth,
                                       Direction doorSide, String bannerColor, String paletteId, ServerWorld world) {
        // Place 1-2 wall banners on the inside face near the door side, attached to the wall.
        boolean onNorthSouth = (doorSide == Direction.NORTH || doorSide == Direction.SOUTH);
        int center = onNorthSouth ? (width / 2) : (depth / 2);
        int y = 3;
        int off = 3;
        int a0 = center - off;
        int a1 = center + off;
        if (onNorthSouth) {
            a0 = Math.max(2, Math.min(width - 3, a0));
            a1 = Math.max(2, Math.min(width - 3, a1));
        } else {
            a0 = Math.max(2, Math.min(depth - 3, a0));
            a1 = Math.max(2, Math.min(depth - 3, a1));
        }

        // Priority: explicit bannerColor > paletteId(BANNER) > red_wall_banner.
        BlockState banner;
        if (bannerColor != null && !bannerColor.isBlank()) {
            String c = bannerColor.trim().toLowerCase();
            String id = "minecraft:red_wall_banner";
            if (c.matches("^[a-z_]{3,20}$")) id = "minecraft:" + c + "_wall_banner";
            banner = com.formacraft.server.material.PaletteResolver.stateFromId(world, id);
            if (banner == null) banner = com.formacraft.server.material.PaletteResolver.stateFromId(world, "minecraft:red_wall_banner");
        } else if (paletteId != null && !paletteId.isBlank()) {
            // deterministic pick based on position
            BlockPos p0 = doorTorchPos(origin, doorSide, a0, y, width, depth);
            long salt = (p0.getX() * 31L) ^ (p0.getZ() * 17L) ^ (p0.getY() * 13L);
            banner = com.formacraft.server.material.PaletteResolver.pick(world, paletteId, "BANNER", p0, salt,
                    com.formacraft.server.material.PaletteResolver.stateFromId(world, "minecraft:red_wall_banner"));
        } else {
            banner = com.formacraft.server.material.PaletteResolver.stateFromId(world, "minecraft:red_wall_banner");
        }
        if (banner == null) banner = Blocks.RED_WOOL.getDefaultState();
        banner = withFacingIfPossible(banner, doorSide);

        BlockPos p0 = doorTorchPos(origin, doorSide, a0, y, width, depth);
        blocks.add(new PlannedBlock(p0, banner));
        if (a1 != a0) {
            BlockPos p1 = doorTorchPos(origin, doorSide, a1, y, width, depth);
            blocks.add(new PlannedBlock(p1, banner));
        }
    }

    private static Direction resolveDoorSide(BuildingSpec spec) {
        if (spec == null || spec.getExtra() == null) return Direction.NORTH;

        // Layout IR: extra.layout.entranceFacing overrides legacy doorSide/facing
        try {
            Object layoutObj = spec.getExtra().get("layout");
            if (layoutObj instanceof java.util.Map<?, ?> m) {
                Object ef = m.get("entranceFacing");
                if (ef != null) {
                    String s = String.valueOf(ef).trim().toUpperCase();
                    switch (s) {
                        case "N", "NORTH", "北", "朝北" -> { return Direction.NORTH; }
                        case "S", "SOUTH", "南", "朝南" -> { return Direction.SOUTH; }
                        case "E", "EAST", "东", "朝东" -> { return Direction.EAST; }
                        case "W", "WEST", "西", "朝西" -> { return Direction.WEST; }
                        default -> {}
                    }
                }
            }
        } catch (Throwable ignored) {}
        Object v = spec.getExtra().get("doorSide");
        if (v == null) v = spec.getExtra().get("facing"); // tolerate reuse of facing as "front side"
        if (v == null) return Direction.NORTH;
        String s = String.valueOf(v).trim().toUpperCase();
        return switch (s) {
            case "N", "NORTH", "北", "朝北" -> Direction.NORTH;
            case "S", "SOUTH", "南", "朝南" -> Direction.SOUTH;
            case "E", "EAST", "东", "朝东" -> Direction.EAST;
            case "W", "WEST", "西", "朝西" -> Direction.WEST;
            default -> Direction.NORTH;
        };
    }

    private static String resolveLayoutSymmetry(BuildingSpec spec) {
        if (spec == null || spec.getExtra() == null) return "NONE";
        try {
            Object layoutObj = spec.getExtra().get("layout");
            if (layoutObj instanceof java.util.Map<?, ?> m) {
                Object sym = m.get("symmetry");
                if (sym != null) {
                    String s = String.valueOf(sym).trim().toUpperCase(java.util.Locale.ROOT);
                    if (s.equals("NONE") || s.equals("X") || s.equals("Z") || s.equals("BOTH")) return s;
                }
            }
        } catch (Throwable ignored) {}
        return "NONE";
    }

    private static String resolveLayoutPlan(BuildingSpec spec) {
        if (spec == null || spec.getExtra() == null) return "none";
        try {
            Object layoutObj = spec.getExtra().get("layout");
            if (layoutObj instanceof java.util.Map<?, ?> m) {
                Object plan = m.get("plan");
                if (plan != null) {
                    String p = String.valueOf(plan).trim().toLowerCase(java.util.Locale.ROOT);
                    if (p.isEmpty()) return "none";
                    switch (p) {
                        case "none", "no", "false", "0", "off" -> {
                            return "none";
                        }
                        case "front_back", "frontback", "front-back", "front/back", "前后", "前后分区", "前后布局",
                             "前厅后室" -> {
                            return "front_back";
                        }
                        case "left_right", "leftright", "left-right", "left/right", "左右", "左右分区", "左右布局" -> {
                            return "left_right";
                        }
                        case "ring_corridor", "ring", "courtyard_corridor", "gallery", "cloister", "回廊", "环廊",
                             "环形走廊", "围绕中庭", "回字形", "回字布局", "回字走廊" -> {
                            return "ring_corridor";
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return "none";
    }

    private static boolean isLayoutPlanRingCorridor(String plan) {
        if (plan == null) return false;
        String p = plan.trim().toLowerCase(java.util.Locale.ROOT);
        return p.equals("ring_corridor");
    }

    private static void addInteriorPartitions(List<PlannedBlock> blocks, BlockPos origin,
                                              int width, int depth, int height,
                                              int floors, int floorHeight,
                                              BlockState wall, BlockState trim,
                                              Direction doorSide, String plan) {
        if (plan == null) return;
        String p = plan.trim().toLowerCase(java.util.Locale.ROOT);
        if (p.isEmpty() || p.equals("none")) return;

        // Need a minimum interior to partition meaningfully.
        if (width < 8 || depth < 8) return;

        // y range inside each floor cell: leave floor at y0 and ceiling at y0+floorHeight.
        int yMin = 1;
        int yMax = Math.max(2, floorHeight - 1); // exclusive upper bound for walls
        int doorH = Math.min(3, Math.max(2, yMax - yMin)); // 2~3 blocks door opening

        boolean splitZ = p.equals("front_back");   // partition line is Z constant (splits front/back)
        boolean splitX = p.equals("left_right");   // partition line is X constant (splits left/right)
        if (!splitZ && !splitX) return;

        boolean doorOnNS = (doorSide == Direction.NORTH || doorSide == Direction.SOUTH);
        int doorAxisCenter = doorOnNS ? (width / 2) : (depth / 2);

        // Partition position: slightly biased so "front" zone (near entrance) is smaller than "back" zone.
        double frontRatio = 0.42;
        int interiorW = width - 2;
        int interiorD = depth - 2;
        int zSplitInterior = 1 + (int) Math.round((interiorD - 1) * frontRatio);
        int xSplitInterior = 1 + (int) Math.round((interiorW - 1) * frontRatio);

        if (doorSide == Direction.SOUTH) {
            // entrance at far Z: mirror split
            zSplitInterior = (depth - 2) - (zSplitInterior - 1);
        }
        if (doorSide == Direction.EAST) {
            // entrance at far X: mirror split
            xSplitInterior = (width - 2) - (xSplitInterior - 1);
        }

        // clamp
        zSplitInterior = Math.max(2, Math.min(depth - 3, zSplitInterior));
        xSplitInterior = Math.max(2, Math.min(width - 3, xSplitInterior));

        // opening width: 1 for narrow, 2 for wide.
        int openW = (doorOnNS ? width : depth) >= 12 ? 2 : 1;
        int open0 = Math.max(2, doorAxisCenter - (openW / 2));
        int open1 = Math.min((doorOnNS ? width - 3 : depth - 3), open0 + openW - 1);

        for (int f = 0; f < floors; f++) {
            int y0 = f * floorHeight;
            if (y0 >= height) break;

            int yTop = Math.min(height - 1, y0 + yMax);
            for (int y = y0 + yMin; y < yTop; y++) {
                if (splitZ) {
                    for (int x = 1; x < width - 1; x++) {
                        boolean opening = (doorOnNS && x >= open0 && x <= open1 && y < (y0 + yMin + doorH));
                        if (opening) continue;
                        BlockState w = wall;
                        // add a subtle trim at top of partition wall
                        if (y == yTop - 1) w = trim;
                        blocks.add(new PlannedBlock(origin.add(x, y, zSplitInterior), w));
                    }
                } else if (splitX) {
                    for (int z = 1; z < depth - 1; z++) {
                        boolean opening = (!doorOnNS && z >= open0 && z <= open1 && y < (y0 + yMin + doorH));
                        if (opening) continue;
                        BlockState w = wall;
                        if (y == yTop - 1) w = trim;
                        blocks.add(new PlannedBlock(origin.add(xSplitInterior, y, z), w));
                    }
                }
            }
        }
    }

    private static void addRingCorridorPartitions(List<PlannedBlock> blocks, BlockPos origin,
                                                  int width, int depth, int height,
                                                  int floors, int floorHeight,
                                                  BlockState wall, BlockState trim,
                                                  LayoutCourtyard courtyard, Direction doorSide) {
        if (courtyard == null || !courtyard.enabled) return;
        if (width < 11 || depth < 11) return; // too tight for courtyard + ring corridor + rooms

        // corridor thickness: 1 for small, 2 for larger footprints
        int t = (width >= 21 && depth >= 21) ? 2 : 1;

        // ring wall rectangle is offset from courtyard by (t+1), leaving a corridor strip between courtyard void and ring wall.
        int rx0 = courtyard.x0 - (t + 1);
        int rx1 = courtyard.x1 + (t + 1);
        int rz0 = courtyard.z0 - (t + 1);
        int rz1 = courtyard.z1 + (t + 1);

        // clamp inside interior (keep 1-block margin to outer walls)
        rx0 = Math.max(2, rx0);
        rz0 = Math.max(2, rz0);
        rx1 = Math.min(width - 3, rx1);
        rz1 = Math.min(depth - 3, rz1);

        if (rx1 - rx0 < 3 || rz1 - rz0 < 3) return;

        int yMin = 1;
        int yMax = Math.max(2, floorHeight - 1);
        int doorH = Math.min(3, Math.max(2, yMax - yMin));

        // openings on ring wall: 4 midpoints (N/S/E/W)
        int midX = (rx0 + rx1) / 2;
        int midZ = (rz0 + rz1) / 2;
        int openW = (width >= 14 && depth >= 14) ? 2 : 1;
        int openX0 = midX - (openW / 2);
        int openX1 = openX0 + openW - 1;
        int openZ0 = midZ - (openW / 2);
        int openZ1 = openZ0 + openW - 1;

        for (int f = 0; f < floors; f++) {
            int y0 = f * floorHeight;
            if (y0 >= height) break;
            int yTop = Math.min(height - 1, y0 + yMax);

            for (int y = y0 + yMin; y < yTop; y++) {
                boolean openingY = y < (y0 + yMin + doorH);

                BlockState w = (y == yTop - 1) ? trim : wall;

                // north (z=rz0): opening at midpoint
                for (int x = rx0; x <= rx1; x++) {
                    boolean opening = openingY && x >= openX0 && x <= openX1;
                    if (opening) continue;
                    blocks.add(new PlannedBlock(origin.add(x, y, rz0), w));
                }
                // south (z=rz1)
                for (int x = rx0; x <= rx1; x++) {
                    boolean opening = openingY && x >= openX0 && x <= openX1;
                    if (opening) continue;
                    blocks.add(new PlannedBlock(origin.add(x, y, rz1), w));
                }
                // west (x=rx0)
                for (int z = rz0; z <= rz1; z++) {
                    boolean opening = openingY && z >= openZ0 && z <= openZ1;
                    if (opening) continue;
                    blocks.add(new PlannedBlock(origin.add(rx0, y, z), w));
                }
                // east (x=rx1)
                for (int z = rz0; z <= rz1; z++) {
                    boolean opening = openingY && z >= openZ0 && z <= openZ1;
                    if (opening) continue;
                    blocks.add(new PlannedBlock(origin.add(rx1, y, z), w));
                }
            }
        }

        // Optional: two radial walls to split the outer zone into 4 rooms (best-effort)
        int cx = width / 2;
        int cz = depth / 2;
        boolean doorOnNS = (doorSide == Direction.NORTH || doorSide == Direction.SOUTH);
        int doorTopRel = 3;

        for (int f = 0; f < floors; f++) {
            int y0 = f * floorHeight;
            if (y0 >= height) break;
            int yTop = Math.min(height - 1, y0 + (floorHeight - 1));

            for (int y = y0 + 1; y < yTop; y++) {
                boolean openingY = y < (y0 + doorTopRel);
                if (doorOnNS) {
                    // split left/right: wall at x=cx from ring wall to outer walls, with an opening at the ring boundary
                    for (int z = rz0; z >= 1; z--) {
                        boolean opening = openingY && z == rz0;
                        if (opening) continue;
                        blocks.add(new PlannedBlock(origin.add(cx, y, z), wall));
                    }
                    for (int z = rz1; z <= depth - 2; z++) {
                        boolean opening = openingY && z == rz1;
                        if (opening) continue;
                        blocks.add(new PlannedBlock(origin.add(cx, y, z), wall));
                    }
                } else {
                    // split front/back: wall at z=cz from ring wall to outer walls, with an opening at the ring boundary
                    for (int x = rx0; x >= 1; x--) {
                        boolean opening = openingY && x == rx0;
                        if (opening) continue;
                        blocks.add(new PlannedBlock(origin.add(x, y, cz), wall));
                    }
                    for (int x = rx1; x <= width - 2; x++) {
                        boolean opening = openingY && x == rx1;
                        if (opening) continue;
                        blocks.add(new PlannedBlock(origin.add(x, y, cz), wall));
                    }
                }
            }
        }
    }

    private record LayoutCourtyard(boolean enabled, int x0, int x1, int z0, int z1) {
        boolean containsInterior(int x, int z) {
            return enabled && x >= x0 && x <= x1 && z >= z0 && z <= z1;
        }
    }

    private static LayoutCourtyard resolveLayoutCourtyard(BuildingSpec spec, int width, int depth) {
        // Only meaningful when there is enough interior space.
        if (width < 9 || depth < 9) return new LayoutCourtyard(false, 0, -1, 0, -1);
        if (spec == null || spec.getExtra() == null) return new LayoutCourtyard(false, 0, -1, 0, -1);

        boolean enabled = false;
        double ratio = 0.45; // default when enabled
        try {
            Object layoutObj = spec.getExtra().get("layout");
            if (layoutObj instanceof java.util.Map<?, ?> m) {
                Object ct = m.get("courtyard");
                if (ct instanceof Boolean b) enabled = b;
                else if (ct != null) {
                    String s = String.valueOf(ct).trim().toLowerCase(java.util.Locale.ROOT);
                    if (!s.isEmpty()) enabled = (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on"));
                }
                Object cr = m.get("courtyardRatio");
                if (cr != null) {
                    try {
                        ratio = Double.parseDouble(String.valueOf(cr).trim());
                    } catch (Exception ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        if (!enabled) return new LayoutCourtyard(false, 0, -1, 0, -1);

        if (ratio < 0.2) ratio = 0.2;
        if (ratio > 0.8) ratio = 0.8;

        int interiorW = width - 2;
        int interiorD = depth - 2;
        int cw = Math.max(3, (int) Math.round(interiorW * ratio));
        int cd = Math.max(3, (int) Math.round(interiorD * ratio));
        // keep at least 1 block margin to outer walls for structural stability
        cw = Math.min(cw, interiorW - 2);
        cd = Math.min(cd, interiorD - 2);
        if (cd < 3) return new LayoutCourtyard(false, 0, -1, 0, -1);

        int x0 = 1 + (interiorW - cw) / 2;
        int z0 = 1 + (interiorD - cd) / 2;
        int x1 = x0 + cw - 1;
        int z1 = z0 + cd - 1;
        return new LayoutCourtyard(true, x0, x1, z0, z1);
    }

    private static boolean isDoorEdge(Direction doorSide, int x, int z, int width, int depth) {
        if (doorSide == null) return (z == 0);
        return switch (doorSide) {
            case NORTH -> z == 0;
            case SOUTH -> z == depth - 1;
            case WEST -> x == 0;
            case EAST -> x == width - 1;
            default -> z == 0;
        };
    }

    private static boolean isNearDoor(Direction doorSide, int x, int z, int width, int depth) {
        int cx = width / 2;
        int cz = depth / 2;
        if (doorSide == null) doorSide = Direction.NORTH;
        return switch (doorSide) {
            case NORTH -> (z == 0) && (x == cx || x == cx - 1);
            case SOUTH -> (z == depth - 1) && (x == cx || x == cx - 1);
            case WEST -> (x == 0) && (z == cz || z == cz - 1);
            case EAST -> (x == width - 1) && (z == cz || z == cz - 1);
            default -> (z == 0) && (x == cx || x == cx - 1);
        };
    }

    private static BlockState applyWallPattern(BlockState wall, BlockState trim, BlockState foundation, String pattern, int y, int height) {
        String p = (pattern == null) ? "uniform" : pattern.trim().toLowerCase();
        // gradient：底部更“厚重”、顶部更“收边”
        switch (p) {
            case "gradient" -> {
                if (y <= 1) return foundation != null ? foundation : wall;
                if (y >= height - 2) return trim != null ? trim : wall;
                return wall;
            }

            // striped：每 3 层一条横向条带
            case "striped" -> {
                if (y % 3 == 0) return trim != null ? trim : wall;
                return wall;
            }

            // random：对 stone_bricks 加一点 cracked/mossy 变化
            case "random" -> {
                Block b = wall != null ? wall.getBlock() : null;
                if (b == Blocks.STONE_BRICKS) {
                    int r = (y * 31 + height * 17) & 7;
                    if (r == 0) return Blocks.CRACKED_STONE_BRICKS.getDefaultState();
                    if (r == 1) return Blocks.MOSSY_STONE_BRICKS.getDefaultState();
                }
                return wall;
            }
        }
        return wall;
    }

    private static BlockState applyFacadeProfileToWallCell(BlockState current,
                                                           BlockState wall,
                                                           BlockState trim,
                                                           BlockState foundation,
                                                           String facadeProfile,
                                                           Direction doorSide,
                                                           int x,
                                                           int y,
                                                           int z,
                                                           int width,
                                                           int depth,
                                                           boolean hasDoor,
                                                           int floorHeight) {
        if (current == null) return null;
        String fp = (facadeProfile == null) ? "" : facadeProfile.trim().toLowerCase(java.util.Locale.ROOT);
        if (fp.isBlank()) return current;

        boolean isEdgeX = (x == 0 || x == width - 1);
        boolean isEdgeZ = (z == 0 || z == depth - 1);
        if (!(isEdgeX || isEdgeZ)) return current;

        // Don't clobber non-wall materials (trim/foundation already placed earlier)
        if (wall != null && current != wall) return current;

        boolean nearDoor = isNearDoor(doorSide, x, z, width, depth);
        if (hasDoor && nearDoor) return current;

        // base plinth: heavier base band
        if (fp.contains("base_plinth")) {
            if (y == 1) return foundation != null ? foundation : current;
            return current;
        }

        // vertical pilasters: periodic vertical trim strips
        if (fp.contains("vertical_pilasters")) {
            int cadence = 3;
            if (isEdgeZ) {
                if (x > 0 && x < width - 1 && (x % cadence == 0) && y > 0) return trim != null ? trim : current;
            } else {
                if (z > 0 && z < depth - 1 && (z % cadence == 0) && y > 0) return trim != null ? trim : current;
            }
            return current;
        }

        // mullion grid: stronger floor bands + subtle vertical mullions
        if (fp.contains("mullion_grid")) {
            int localY = (floorHeight > 0) ? (y % floorHeight) : 0;
            if (y > 0 && localY == 0) return trim != null ? trim : current;
            int cadence = 2;
            if (isEdgeZ) {
                if (x > 0 && x < width - 1 && (x % cadence == 0) && y > 0) return trim != null ? trim : current;
            } else {
                if (z > 0 && z < depth - 1 && (z % cadence == 0) && y > 0) return trim != null ? trim : current;
            }
            return current;
        }

        // module grid: brutalist-ish panelization (light touch)
        if (fp.contains("module_grid")) {
            if (y > 0 && (y % 3 == 0)) return trim != null ? trim : current;
            if (isEdgeZ) {
                if (x > 0 && x < width - 1 && (x % 3 == 0) && y > 0) return trim != null ? trim : current;
            } else {
                if (z > 0 && z < depth - 1 && (z % 3 == 0) && y > 0) return trim != null ? trim : current;
            }
            return current;
        }

        return current;
    }

    private static void addFacadeComponents(List<PlannedBlock> blocks,
                                           BlockPos origin,
                                           ServerWorld world,
                                           BuildingSpec spec,
                                           int width,
                                           int depth,
                                           int height,
                                           BlockState wall,
                                           BlockState trim,
                                           BlockState foundation,
                                           BlockState pillar,
                                           BlockState roof,
                                           BlockState roofStairs,
                                           BlockState roofSlab,
                                           BlockState windowBlock,
                                           String paletteId,
                                           com.formacraft.common.style.profile.DetailPreferences details) {
        if (blocks == null || origin == null || details == null) return;
        if (width < 9 || depth < 9 || height < 6) return; // too small

        Direction doorSide = resolveDoorSide(spec);
        String layoutSymmetry = resolveLayoutSymmetry(spec);

        // --- Entry / portal feature (cross-style) ---
        if (details.portalStyle != null && !details.portalStyle.isBlank()) {
            addPortalFeature(blocks, origin, width, depth, height, doorSide, foundation, trim, roofSlab, roofStairs, details.portalStyle);
        }

        // --- Ornaments / props (cross-style) ---
        if (details.ornamentProfile != null && !details.ornamentProfile.isBlank()) {
            addOrnamentProfile(blocks, origin, world, width, depth, height, doorSide, foundation, trim, roofSlab,
                    paletteId, details.ornamentProfile, details, layoutSymmetry);
        }

        // --- Classical stylobate / podium ring ---
        if (details.stylobate) {
            addStylobate(blocks, origin, width, depth, doorSide, foundation, roofSlab, roofStairs);
        }

        // --- Greco-Roman: colonnade + pediment ---
        if (details.peristyle) {
            addPeristyleColonnade(blocks, origin, width, depth, height, doorSide, foundation, pillar, roofSlab,
                    details.entablature ? trim : null,
                    Math.max(2, Math.min(4, details.colonnadeSpacing)),
                    details.classicalColumnOrder);
            if (details.pediment) {
                addPediment(blocks, origin, width, depth, height, doorSide, trim, roofSlab);
            }
        } else if (details.colonnade || details.pediment) {
            // only build a front portico to avoid heavy intrusion
            addFrontColonnade(blocks, origin, width, depth, height, doorSide, foundation, pillar, roofSlab,
                    details.entablature ? trim : null,
                    Math.max(2, Math.min(4, details.colonnadeSpacing)),
                    details.classicalColumnOrder);
            if (details.pediment) {
                addPediment(blocks, origin, width, depth, height, doorSide, trim, roofSlab);
            }
        }

        // --- Gothic: rose window + buttresses ---
        if (details.roseWindow) {
            addRoseWindow(blocks, origin, width, depth, height, doorSide, windowBlock, trim);
        }
        if (details.buttresses) {
            addFlyingButtresses(blocks, origin, width, depth, height, doorSide, foundation, trim, roofStairs);
        }
        if (details.pointedArches || details.arches) {
            addPointedDoorPortal(blocks, origin, width, depth, height, doorSide, trim, roofStairs);
        }
    }

    private static void addPortalFeature(List<PlannedBlock> blocks,
                                         BlockPos origin,
                                         int width,
                                         int depth,
                                         int height,
                                         Direction doorSide,
                                         BlockState foundation,
                                         BlockState trim,
                                         BlockState roofSlab,
                                         BlockState roofStairs,
                                         String portalStyle) {
        if (blocks == null || origin == null || portalStyle == null) return;
        if (width < 7 || depth < 7 || height < 5) return;
        if (doorSide == null) doorSide = Direction.NORTH;
        final BlockState t = (trim != null) ? trim : (foundation != null ? foundation : Blocks.STONE_BRICKS.getDefaultState());
        final BlockState slab = (roofSlab != null) ? roofSlab : t;
        final BlockState stairs = (roofStairs != null) ? roofStairs : t;

        String ps = portalStyle.trim().toLowerCase(java.util.Locale.ROOT);

        boolean onNS = (doorSide == Direction.NORTH || doorSide == Direction.SOUTH);
        int center = onNS ? (width / 2) : (depth / 2);
        int a0 = center - 1;
        int a1 = center;
        int a2 = center + 1;

        // Outside plane adjacent to wall (within the "cleared" buffer [-1..+1])
        int ox = (doorSide == Direction.WEST) ? -1 : (doorSide == Direction.EAST ? width : 0);
        int oz = (doorSide == Direction.NORTH) ? -1 : (doorSide == Direction.SOUTH ? depth : 0);

        // Helper to place a post + beam in outside plane
        java.util.function.BiConsumer<Integer, Integer> post = (axis, yMax) -> {
            if (onNS) {
                BlockPos b = origin.add(axis, 0, oz);
                for (int y = 0; y <= yMax; y++) blocks.add(new PlannedBlock(b.up(y), t));
            } else {
                BlockPos b = origin.add(ox, 0, axis);
                for (int y = 0; y <= yMax; y++) blocks.add(new PlannedBlock(b.up(y), t));
            }
        };

        if (ps.contains("gothic")) {
            // Reuse existing pointed portal (inside wall) for strong silhouette
            addPointedDoorPortal(blocks, origin, width, depth, height, doorSide, t, stairs);
            return;
        }

        if (ps.contains("torii")) {
            // Two posts + top beam (best-effort)
            post.accept(a0, 3);
            post.accept(a2, 3);
            for (int y = 4; y <= 4; y++) {
                if (onNS) {
                    for (int x = a0; x <= a2; x++) blocks.add(new PlannedBlock(origin.add(x, y, oz), slab));
                } else {
                    for (int z = a0; z <= a2; z++) blocks.add(new PlannedBlock(origin.add(ox, y, z), slab));
                }
            }
            return;
        }

        if (ps.contains("paifang")) {
            // Slightly taller: posts + double beam
            post.accept(a0, 4);
            post.accept(a2, 4);
            if (onNS) {
                for (int x = a0; x <= a2; x++) blocks.add(new PlannedBlock(origin.add(x, 4, oz), t));
                for (int x = a0; x <= a2; x++) blocks.add(new PlannedBlock(origin.add(x, 5, oz), slab));
            } else {
                for (int z = a0; z <= a2; z++) blocks.add(new PlannedBlock(origin.add(ox, 4, z), t));
                for (int z = a0; z <= a2; z++) blocks.add(new PlannedBlock(origin.add(ox, 5, z), slab));
            }
            return;
        }

        if (ps.contains("neon")) {
            // Simple glowing frame: use trim (often stained glass in Cyberpunk) around door axis outside
            post.accept(a0, 3);
            post.accept(a2, 3);
            if (onNS) {
                blocks.add(new PlannedBlock(origin.add(a0, 3, oz), t));
                blocks.add(new PlannedBlock(origin.add(a1, 3, oz), t));
                blocks.add(new PlannedBlock(origin.add(a2, 3, oz), t));
            } else {
                blocks.add(new PlannedBlock(origin.add(ox, 3, a0), t));
                blocks.add(new PlannedBlock(origin.add(ox, 3, a1), t));
                blocks.add(new PlannedBlock(origin.add(ox, 3, a2), t));
            }
            return;
        }

        if (ps.contains("modern")) {
            // Minimal frame + small canopy
            post.accept(a0, 2);
            post.accept(a2, 2);
            if (onNS) {
                blocks.add(new PlannedBlock(origin.add(a0, 3, oz), slab));
                blocks.add(new PlannedBlock(origin.add(a1, 3, oz), slab));
                blocks.add(new PlannedBlock(origin.add(a2, 3, oz), slab));
            } else {
                blocks.add(new PlannedBlock(origin.add(ox, 3, a0), slab));
                blocks.add(new PlannedBlock(origin.add(ox, 3, a1), slab));
                blocks.add(new PlannedBlock(origin.add(ox, 3, a2), slab));
            }
            return;
        }

        if (ps.contains("steampunk")) {
            // Riveted frame vibe: iron trapdoors around outside door axis (best-effort)
            BlockState td = Blocks.IRON_TRAPDOOR.getDefaultState();
            post.accept(a0, 2);
            post.accept(a2, 2);
            if (onNS) {
                blocks.add(new PlannedBlock(origin.add(a0, 1, oz), td));
                blocks.add(new PlannedBlock(origin.add(a2, 1, oz), td));
                blocks.add(new PlannedBlock(origin.add(a0, 2, oz), td));
                blocks.add(new PlannedBlock(origin.add(a2, 2, oz), td));
            } else {
                blocks.add(new PlannedBlock(origin.add(ox, 1, a0), td));
                blocks.add(new PlannedBlock(origin.add(ox, 1, a2), td));
                blocks.add(new PlannedBlock(origin.add(ox, 2, a0), td));
                blocks.add(new PlannedBlock(origin.add(ox, 2, a2), td));
            }
            return;
        }

        if (ps.contains("organic")) {
            // Soft arch hint: posts + curved top with stairs
            post.accept(a0, 2);
            post.accept(a2, 2);
            Direction inward = (doorSide == Direction.NORTH) ? Direction.SOUTH
                    : (doorSide == Direction.SOUTH) ? Direction.NORTH
                    : (doorSide == Direction.WEST) ? Direction.EAST
                    : Direction.WEST;
            BlockState s = withFacingIfPossible(stairs, inward);
            if (onNS) {
                blocks.add(new PlannedBlock(origin.add(a0, 3, oz), s));
                blocks.add(new PlannedBlock(origin.add(a2, 3, oz), s));
                blocks.add(new PlannedBlock(origin.add(a1, 4, oz), t));
            } else {
                blocks.add(new PlannedBlock(origin.add(ox, 3, a0), s));
                blocks.add(new PlannedBlock(origin.add(ox, 3, a2), s));
                blocks.add(new PlannedBlock(origin.add(ox, 4, a1), t));
            }
            return;
        }

        // Default: stone arch-ish frame
        // posts + small arch top
        post.accept(a0, 2);
        post.accept(a2, 2);
        Direction inward = (doorSide == Direction.NORTH) ? Direction.SOUTH
                : (doorSide == Direction.SOUTH) ? Direction.NORTH
                : (doorSide == Direction.WEST) ? Direction.EAST
                : Direction.WEST;
        BlockState s = withFacingIfPossible(stairs, inward);
        if (onNS) {
            blocks.add(new PlannedBlock(origin.add(a0, 3, oz), s));
            blocks.add(new PlannedBlock(origin.add(a2, 3, oz), s));
            blocks.add(new PlannedBlock(origin.add(a1, 3, oz), slab));
        } else {
            blocks.add(new PlannedBlock(origin.add(ox, 3, a0), s));
            blocks.add(new PlannedBlock(origin.add(ox, 3, a2), s));
            blocks.add(new PlannedBlock(origin.add(ox, 3, a1), slab));
        }
    }

    private static void applyEavesProfile(List<PlannedBlock> blocks,
                                          BlockPos origin,
                                          int width,
                                          int depth,
                                          int height,
                                          BuildingStyle style,
                                          String eavesProfile,
                                          BlockState trim,
                                          BlockState roof,
                                          BlockState roofSlab) {
        if (blocks == null || origin == null || eavesProfile == null) return;
        String ep = eavesProfile.trim().toLowerCase(java.util.Locale.ROOT);
        if (trim == null) trim = Blocks.STONE_BRICKS.getDefaultState();
        if (roof == null) roof = trim;
        if (roofSlab == null) roofSlab = trim;

        // battlement: simple crenels on the very top ring for defensive silhouettes
        if (ep.contains("battlement")) {
            int y = height + 1;
            for (int x = -1; x <= width; x++) {
                if ((x & 1) == 0) {
                    blocks.add(new PlannedBlock(origin.add(x, y, -1), trim));
                    blocks.add(new PlannedBlock(origin.add(x, y, depth), trim));
                }
            }
            for (int z = -1; z <= depth; z++) {
                if ((z & 1) == 0) {
                    blocks.add(new PlannedBlock(origin.add(-1, y, z), trim));
                    blocks.add(new PlannedBlock(origin.add(width, y, z), trim));
                }
            }
            return;
        }

        // neon strip: use trim material as a "light band" (cyberpunk often uses stained-glass trim)
        if (ep.contains("neon")) {
            for (int x = -1; x <= width; x++) {
                blocks.add(new PlannedBlock(origin.add(x, height, -1), trim));
                blocks.add(new PlannedBlock(origin.add(x, height, depth), trim));
            }
            for (int z = -1; z <= depth; z++) {
                blocks.add(new PlannedBlock(origin.add(-1, height, z), trim));
                blocks.add(new PlannedBlock(origin.add(width, height, z), trim));
            }
            return;
        }

        // parapet: emphasize flat-roof edge ring by extending it one more layer
        if (ep.contains("parapet")) {
            int y = height + 2;
            for (int x = -1; x <= width; x++) {
                blocks.add(new PlannedBlock(origin.add(x, y, -1), trim));
                blocks.add(new PlannedBlock(origin.add(x, y, depth), trim));
            }
            for (int z = -1; z <= depth; z++) {
                blocks.add(new PlannedBlock(origin.add(-1, y, z), trim));
                blocks.add(new PlannedBlock(origin.add(width, y, z), trim));
            }
            return;
        }

        // organic vines: a soft edge band with leaves (best-effort, non-invasive)
        if (ep.contains("vine") || ep.contains("organic")) {
            int y = height - 1;
            BlockState leaf = Blocks.OAK_LEAVES.getDefaultState();
            for (int x = -1; x <= width; x++) {
                if ((x & 1) == 0) {
                    blocks.add(new PlannedBlock(origin.add(x, y, -2), leaf));
                    blocks.add(new PlannedBlock(origin.add(x, y, depth + 1), leaf));
                }
            }
            for (int z = -1; z <= depth; z++) {
                if ((z & 1) == 0) {
                    blocks.add(new PlannedBlock(origin.add(-2, y, z), leaf));
                    blocks.add(new PlannedBlock(origin.add(width + 1, y, z), leaf));
                }
            }
            return;
        }

        // flying eaves: add an extra slab ring one block further out for stronger silhouette
        if (ep.contains("flying")) {
            int y = height - 1;
            for (int x = -2; x <= width + 1; x++) {
                blocks.add(new PlannedBlock(origin.add(x, y, -2), roofSlab));
                blocks.add(new PlannedBlock(origin.add(x, y, depth + 1), roofSlab));
            }
            for (int z = -2; z <= depth + 1; z++) {
                blocks.add(new PlannedBlock(origin.add(-2, y, z), roofSlab));
                blocks.add(new PlannedBlock(origin.add(width + 1, y, z), roofSlab));
            }
        }
    }

    private static void addOrnamentProfile(List<PlannedBlock> blocks,
                                          BlockPos origin,
                                          ServerWorld world,
                                          int width,
                                          int depth,
                                          int height,
                                          Direction doorSide,
                                          BlockState foundation,
                                          BlockState trim,
                                          BlockState roofSlab,
                                          String paletteId,
                                          String ornamentProfile,
                                          com.formacraft.common.style.profile.DetailPreferences details,
                                          String layoutSymmetry) {
        if (blocks == null || origin == null || ornamentProfile == null) return;
        if (width < 7 || depth < 7 || height < 5) return;
        if (doorSide == null) doorSide = Direction.NORTH;
        BlockState t = (trim != null) ? trim : (foundation != null ? foundation : Blocks.STONE_BRICKS.getDefaultState());
        BlockState slab = (roofSlab != null) ? roofSlab : t;

        String op = ornamentProfile.trim().toLowerCase(java.util.Locale.ROOT);
        String sym = (layoutSymmetry == null) ? "NONE" : layoutSymmetry.trim().toUpperCase(java.util.Locale.ROOT);
        boolean onNS = (doorSide == Direction.NORTH || doorSide == Direction.SOUTH);
        int center = onNS ? (width / 2) : (depth / 2);
        int a0 = center - 1;
        int a2 = center + 1;
        int ox = (doorSide == Direction.WEST) ? -1 : (doorSide == Direction.EAST ? width : 0);
        int oz = (doorSide == Direction.NORTH) ? -1 : (doorSide == Direction.SOUTH ? depth : 0);

        // --- Chinese plaque: a sign-like lintel above the door axis (outside plane)
        if (op.contains("chinese") || op.contains("plaque")) {
            BlockState sign = Blocks.DARK_OAK_WALL_SIGN.getDefaultState();
            if (paletteId != null && !paletteId.isBlank() && world != null) {
                // Prefer palette semantic signage; keep wall sign as fallback.
                sign = PaletteResolver.pick(world, paletteId, "ROAD_SIGNAGE", origin, 0xA005E001L, sign);
                sign = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xA005E002L, sign);
            }
            sign = withFacingIfPossible(sign, doorSide);
            int y = 3;
            if (onNS) {
                blocks.add(new PlannedBlock(origin.add(center, y, oz), sign));
                blocks.add(new PlannedBlock(origin.add(a0, y, oz), slab));
                blocks.add(new PlannedBlock(origin.add(a2, y, oz), slab));
            } else {
                blocks.add(new PlannedBlock(origin.add(ox, y, center), sign));
                blocks.add(new PlannedBlock(origin.add(ox, y, a0), slab));
                blocks.add(new PlannedBlock(origin.add(ox, y, a2), slab));
            }
            return;
        }

        // --- Castle banners: two wall banners flanking the door (outside plane)
        if (op.contains("castle") || op.contains("banner")) {
            BlockState wb = resolveWallBannerState(details != null ? details.bannerColor : null);
            if ((details == null || details.bannerColor == null || details.bannerColor.isBlank())
                    && paletteId != null && !paletteId.isBlank() && world != null) {
                // Prefer palette banner when no explicit banner color was chosen.
                wb = PaletteResolver.pick(world, paletteId, "BANNER", origin, 0xA005E003L, wb);
            }
            wb = withFacingIfPossible(wb, doorSide);
            int y = 2;
            if (onNS) {
                blocks.add(new PlannedBlock(origin.add(a0, y, oz), wb));
                blocks.add(new PlannedBlock(origin.add(a2, y, oz), wb));
            } else {
                blocks.add(new PlannedBlock(origin.add(ox, y, a0), wb));
                blocks.add(new PlannedBlock(origin.add(ox, y, a2), wb));
            }
            return;
        }

        // --- Steampunk pipes: vertical pipes on one corner + tiny chimney hint on roof edge
        if (op.contains("steam") || op.contains("pipe")) {
            BlockState pipe = Blocks.COPPER_BLOCK.getDefaultState();
            BlockState rib = Blocks.IRON_BARS.getDefaultState();
            BlockState chimney = Blocks.CAMPFIRE.getDefaultState();
            if (paletteId != null && !paletteId.isBlank() && world != null) {
                pipe = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xA005E100L, pipe);
                pipe = PaletteResolver.pick(world, paletteId, "FRAME", origin, 0xA005E101L, pipe);
                rib = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xA005E102L, rib);
                chimney = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xA005E103L, chimney);
            }
            BlockPos base = origin.add(-1, 0, -1);
            for (int y = 1; y <= Math.min(height + 1, 8); y++) {
                blocks.add(new PlannedBlock(base.up(y), pipe));
                if ((y & 1) == 0) blocks.add(new PlannedBlock(base.up(y).east(), rib));
            }
            // chimney on roof corner
            BlockPos top = origin.add(1, height + 1, 1);
            blocks.add(new PlannedBlock(top, chimney));
            return;
        }

        // --- Cyber signage: neon-ish plate using trim (often stained glass) above/side of door
        if (op.contains("cyber") || op.contains("sign")) {
            int y = 4;
            if (onNS) {
                blocks.add(new PlannedBlock(origin.add(center, y, oz), t));
                blocks.add(new PlannedBlock(origin.add(a2 + 1, y, oz), t));
                // symmetry X/BOTH: mirror to the left side as well
                if (sym.equals("X") || sym.equals("BOTH")) {
                    blocks.add(new PlannedBlock(origin.add(a0 - 1, y, oz), t));
                    blocks.add(new PlannedBlock(origin.add(a0, y, oz), t));
                }
            } else {
                blocks.add(new PlannedBlock(origin.add(ox, y, center), t));
                blocks.add(new PlannedBlock(origin.add(ox, y, a2 + 1), t));
                if (sym.equals("Z") || sym.equals("BOTH")) {
                    blocks.add(new PlannedBlock(origin.add(ox, y, a0 - 1), t));
                    blocks.add(new PlannedBlock(origin.add(ox, y, a0), t));
                }
            }
            return;
        }

        // --- Shrine lanterns (Japanese-ish): two small lanterns flanking the door axis
        if (op.contains("shrine_lantern") || op.contains("shrine-lantern") || op.contains("shrine lantern")) {
            BlockState post = t;
            BlockState lantern = Blocks.LANTERN.getDefaultState();
            if (paletteId != null && !paletteId.isBlank() && world != null) {
                post = PaletteResolver.pick(world, paletteId, "STRUCTURAL_BEAM", origin, 0xA005E0A1L, post);
                post = PaletteResolver.pick(world, paletteId, "FRAME", origin, 0xA005E0A2L, post);
                lantern = PaletteResolver.pick(world, paletteId, "LIGHTING", origin, 0xA005E0A3L, lantern);
                lantern = PaletteResolver.pick(world, paletteId, "ROAD_LIGHT", origin, 0xA005E0A4L, lantern);
            }
            int yPost = 1;
            int yLantern = 3;
            if (onNS) {
                blocks.add(new PlannedBlock(origin.add(a0, yPost, oz), post));
                blocks.add(new PlannedBlock(origin.add(a2, yPost, oz), post));
                blocks.add(new PlannedBlock(origin.add(a0, yLantern, oz), lantern));
                blocks.add(new PlannedBlock(origin.add(a2, yLantern, oz), lantern));
            } else {
                blocks.add(new PlannedBlock(origin.add(ox, yPost, a0), post));
                blocks.add(new PlannedBlock(origin.add(ox, yPost, a2), post));
                blocks.add(new PlannedBlock(origin.add(ox, yLantern, a0), lantern));
                blocks.add(new PlannedBlock(origin.add(ox, yLantern, a2), lantern));
            }
            return;
        }

        // --- Organic lanterns: leaves + lantern near door
        if (op.contains("organic") || op.contains("lantern") || op.contains("vine")) {
            BlockState leaf = Blocks.OAK_LEAVES.getDefaultState();
            BlockState lantern = Blocks.LANTERN.getDefaultState();
            if (paletteId != null && !paletteId.isBlank() && world != null) {
                leaf = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xA005E004L, leaf);
                lantern = PaletteResolver.pick(world, paletteId, "LIGHTING", origin, 0xA005E005L, lantern);
                lantern = PaletteResolver.pick(world, paletteId, "ROAD_LIGHT", origin, 0xA005E006L, lantern);
            }
            int y = 3;
            if (onNS) {
                blocks.add(new PlannedBlock(origin.add(a0, y, oz), leaf));
                blocks.add(new PlannedBlock(origin.add(a2, y, oz), leaf));
                blocks.add(new PlannedBlock(origin.add(center, y + 1, oz), lantern));
            } else {
                blocks.add(new PlannedBlock(origin.add(ox, y, a0), leaf));
                blocks.add(new PlannedBlock(origin.add(ox, y, a2), leaf));
                blocks.add(new PlannedBlock(origin.add(ox, y + 1, center), lantern));
            }
        }
    }

    private static BlockState resolveWallBannerState(String color) {
        String c = (color == null) ? "" : color.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (c) {
            case "black" -> Blocks.BLACK_WALL_BANNER.getDefaultState();
            case "white" -> Blocks.WHITE_WALL_BANNER.getDefaultState();
            case "blue" -> Blocks.BLUE_WALL_BANNER.getDefaultState();
            case "green" -> Blocks.GREEN_WALL_BANNER.getDefaultState();
            case "yellow" -> Blocks.YELLOW_WALL_BANNER.getDefaultState();
            case "purple" -> Blocks.PURPLE_WALL_BANNER.getDefaultState();
            case "cyan" -> Blocks.CYAN_WALL_BANNER.getDefaultState();
            default -> Blocks.RED_WALL_BANNER.getDefaultState();
        };
    }

    private static void addStylobate(List<PlannedBlock> blocks,
                                     BlockPos origin,
                                     int width,
                                     int depth,
                                     Direction doorSide,
                                     BlockState foundation,
                                     BlockState capSlab,
                                     BlockState capStairs) {
        if (blocks == null || origin == null || foundation == null) return;
        if (capSlab == null) capSlab = foundation;
        if (capStairs == null) capStairs = capSlab;

        // Outer ring expands 1 block around footprint: x [-1..width], z [-1..depth]
        for (int x = -1; x <= width; x++) {
            for (int z = -1; z <= depth; z++) {
                boolean inside = (x >= 0 && x <= width - 1 && z >= 0 && z <= depth - 1);
                if (inside) continue;
                blocks.add(new PlannedBlock(origin.add(x, 0, z), foundation));
                blocks.add(new PlannedBlock(origin.add(x, 1, z), capSlab));
            }
        }

        // Front steps: 5-wide (clamped) aligned to door axis; placed just outside the ring.
        int cx = width / 2;
        int cz = depth / 2;
        if (doorSide == null) doorSide = Direction.NORTH;

        if (doorSide == Direction.NORTH || doorSide == Direction.SOUTH) {
            int x0 = Math.max(1, cx - 2);
            int x1 = Math.min(width - 2, cx + 2);
            int zOutside = (doorSide == Direction.NORTH) ? -2 : depth + 1;
            Direction stairFacing = (doorSide == Direction.NORTH) ? Direction.SOUTH : Direction.NORTH;
            for (int x = x0; x <= x1; x++) {
                blocks.add(new PlannedBlock(origin.add(x, 0, zOutside), withFacingIfPossible(capStairs, stairFacing)));
            }
        } else {
            int z0 = Math.max(1, cz - 2);
            int z1 = Math.min(depth - 2, cz + 2);
            int xOutside = (doorSide == Direction.WEST) ? -2 : width + 1;
            Direction stairFacing = (doorSide == Direction.WEST) ? Direction.EAST : Direction.WEST;
            for (int z = z0; z <= z1; z++) {
                blocks.add(new PlannedBlock(origin.add(xOutside, 0, z), withFacingIfPossible(capStairs, stairFacing)));
            }
        }
    }

    private static void addFrontColonnade(List<PlannedBlock> blocks,
                                          BlockPos origin,
                                          int width,
                                          int depth,
                                          int height,
                                          Direction doorSide,
                                          BlockState foundation,
                                          BlockState pillar,
                                          BlockState roofSlab,
                                          BlockState entablatureBlock,
                                          int spacing,
                                          String columnOrder) {
        if (width < 11 && depth < 11) return;
        int colH = Math.max(4, Math.min(height - 2, 8));
        int sp = Math.max(2, Math.min(4, spacing));

        // place 1 block outside the door side
        int zOutside = (doorSide == Direction.NORTH) ? -2 : (doorSide == Direction.SOUTH ? depth + 1 : -2);
        int xOutside = (doorSide == Direction.WEST) ? -2 : (doorSide == Direction.EAST ? width + 1 : -2);

        if (doorSide == Direction.NORTH || doorSide == Direction.SOUTH) {
            for (int x = 1; x <= width - 2; x += sp) {
                BlockPos base = origin.add(x, 0, zOutside);
                blocks.add(new PlannedBlock(base, foundation));
                for (int y = 1; y <= colH; y++) blocks.add(new PlannedBlock(base.up(y), pillar));
                // simple column base/capital to read more "classical"
                blocks.add(new PlannedBlock(base.up(colH + 1), pickCapitalBlock(roofSlab, entablatureBlock, columnOrder)));
            }
            if (entablatureBlock != null) {
                int y = colH + 1;
                for (int x = 1; x <= width - 2; x++) {
                    blocks.add(new PlannedBlock(origin.add(x, y, zOutside), entablatureBlock));
                }
            }
        } else {
            for (int z = 1; z <= depth - 2; z += sp) {
                BlockPos base = origin.add(xOutside, 0, z);
                blocks.add(new PlannedBlock(base, foundation));
                for (int y = 1; y <= colH; y++) blocks.add(new PlannedBlock(base.up(y), pillar));
                blocks.add(new PlannedBlock(base.up(colH + 1), pickCapitalBlock(roofSlab, entablatureBlock, columnOrder)));
            }
            if (entablatureBlock != null) {
                int y = colH + 1;
                for (int z = 1; z <= depth - 2; z++) {
                    blocks.add(new PlannedBlock(origin.add(xOutside, y, z), entablatureBlock));
                }
            }
        }
    }

    private static BlockState pickCapitalBlock(BlockState roofSlab, BlockState entablatureBlock, String columnOrder) {
        // Best-effort: keep it simple and consistent with available materials.
        String o = (columnOrder == null) ? "" : columnOrder.trim().toLowerCase();
        if (o.contains("corinth")) {
            // "leafy" suggestion using entablature/trim when present
            return entablatureBlock != null ? entablatureBlock : roofSlab;
        }
        if (o.contains("ionic")) {
            return entablatureBlock != null ? entablatureBlock : roofSlab;
        }
        return roofSlab; // doric/simple
    }

    private static void addPeristyleColonnade(List<PlannedBlock> blocks,
                                              BlockPos origin,
                                              int width,
                                              int depth,
                                              int height,
                                              Direction doorSide,
                                              BlockState foundation,
                                              BlockState pillar,
                                              BlockState roofSlab,
                                              BlockState entablatureBlock,
                                              int spacing,
                                              String columnOrder) {
        if (blocks == null || origin == null) return;
        // Size gate: avoid clogging small houses
        if (width < 9 || depth < 9) return;

        int colH = Math.max(3, Math.min(6, height - 2));
        int sp = Math.max(2, spacing);

        int zNorth = -2;
        int zSouth = depth + 1;
        int xWest = -2;
        int xEast = width + 1;

        int cx = width / 2;
        int cz = depth / 2;

        java.util.function.BiPredicate<Integer, Integer> shouldSkipForDoor =
                (x, z) -> {
                    // Create a 3-wide opening centered on the door axis on the door side only
                    if (doorSide == Direction.NORTH && z == zNorth) return Math.abs(x - cx) <= 1;
                    if (doorSide == Direction.SOUTH && z == zSouth) return Math.abs(x - cx) <= 1;
                    if (doorSide == Direction.WEST && x == xWest) return Math.abs(z - cz) <= 1;
                    if (doorSide == Direction.EAST && x == xEast) return Math.abs(z - cz) <= 1;
                    return false;
                };

        // North/South sides
        for (int x = 1; x <= width - 2; x += sp) {
            if (!shouldSkipForDoor.test(x, zNorth)) placeColumn(blocks, origin.add(x, 0, zNorth), colH, foundation, pillar, roofSlab, entablatureBlock, columnOrder);
            if (!shouldSkipForDoor.test(x, zSouth)) placeColumn(blocks, origin.add(x, 0, zSouth), colH, foundation, pillar, roofSlab, entablatureBlock, columnOrder);
        }
        // West/East sides
        for (int z = 1; z <= depth - 2; z += sp) {
            if (!shouldSkipForDoor.test(xWest, z)) placeColumn(blocks, origin.add(xWest, 0, z), colH, foundation, pillar, roofSlab, entablatureBlock, columnOrder);
            if (!shouldSkipForDoor.test(xEast, z)) placeColumn(blocks, origin.add(xEast, 0, z), colH, foundation, pillar, roofSlab, entablatureBlock, columnOrder);
        }

        // Optional entablature ring (continuous beam) at capital level
        if (entablatureBlock != null) {
            int y = colH + 1;
            for (int x = 0; x <= width - 1; x++) {
                if (!shouldSkipForDoor.test(x, zNorth)) blocks.add(new PlannedBlock(origin.add(x, y, zNorth), entablatureBlock));
                if (!shouldSkipForDoor.test(x, zSouth)) blocks.add(new PlannedBlock(origin.add(x, y, zSouth), entablatureBlock));
            }
            for (int z = 0; z <= depth - 1; z++) {
                if (!shouldSkipForDoor.test(xWest, z)) blocks.add(new PlannedBlock(origin.add(xWest, y, z), entablatureBlock));
                if (!shouldSkipForDoor.test(xEast, z)) blocks.add(new PlannedBlock(origin.add(xEast, y, z), entablatureBlock));
            }
        }
    }

    private static void placeColumn(List<PlannedBlock> blocks,
                                    BlockPos base,
                                    int colH,
                                    BlockState foundation,
                                    BlockState pillar,
                                    BlockState roofSlab,
                                    BlockState entablatureBlock,
                                    String columnOrder) {
        blocks.add(new PlannedBlock(base, foundation));
        for (int y = 1; y <= colH; y++) blocks.add(new PlannedBlock(base.up(y), pillar));
        blocks.add(new PlannedBlock(base.up(colH + 1), pickCapitalBlock(roofSlab, entablatureBlock, columnOrder)));
    }

    private static void addPediment(List<PlannedBlock> blocks,
                                    BlockPos origin,
                                    int width,
                                    int depth,
                                    int height,
                                    Direction doorSide,
                                    BlockState trim,
                                    BlockState roofSlab) {
        // Pediment only looks right on a gable end; for MVP, attach to door-facing side if it's N/S, else north.
        boolean onNS = (doorSide == Direction.NORTH || doorSide == Direction.SOUTH);
        int z = onNS ? (doorSide == Direction.NORTH ? -1 : depth) : -1;
        int yBase = Math.max(3, height - 1);
        int mid = width / 2;
        int pedH = Math.max(3, Math.min(7, width / 2));

        // Outline triangle
        for (int i = 0; i < pedH; i++) {
            int x0 = Math.max(0, mid - i);
            int x1 = Math.min(width - 1, mid + i);
            int y = yBase + i;
            blocks.add(new PlannedBlock(origin.add(x0, y, z), trim));
            blocks.add(new PlannedBlock(origin.add(x1, y, z), trim));
            if (i == pedH - 1) {
                for (int x = x0; x <= x1; x++) blocks.add(new PlannedBlock(origin.add(x, y + 1, z), roofSlab));
            }
        }
        // Base line
        for (int x = 0; x < width; x++) blocks.add(new PlannedBlock(origin.add(x, yBase, z), trim));
    }

    private static void addRoseWindow(List<PlannedBlock> blocks,
                                      BlockPos origin,
                                      int width,
                                      int depth,
                                      int height,
                                      Direction doorSide,
                                      BlockState windowBlock,
                                      BlockState trim) {
        // place on door side if N/S else on north wall
        boolean onNS = (doorSide == Direction.NORTH || doorSide == Direction.SOUTH);
        Direction face = onNS ? doorSide : Direction.NORTH;
        int zWall = (face == Direction.NORTH) ? 0 : (face == Direction.SOUTH ? depth - 1 : 0);

        int cx = width / 2;
        int cy = Math.max(4, Math.min(height - 2, height / 2));
        int r = Math.max(2, Math.min(4, Math.min(width, height) / 4));

        // carve + fill a simple circle on the wall plane (1-block thick)
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                int d2 = dx * dx + dy * dy;
                if (d2 > r * r) continue;
                boolean edge = d2 >= (r - 1) * (r - 1);
                int x = cx + dx;
                int y = cy + dy;
                if (x <= 0 || x >= width - 1 || y <= 2 || y >= height - 1) continue;
                BlockPos p = origin.add(x, y, zWall);
                blocks.add(new PlannedBlock(p, edge ? trim : windowBlock));
            }
        }
    }

    private static void addFlyingButtresses(List<PlannedBlock> blocks,
                                           BlockPos origin,
                                           int width,
                                           int depth,
                                           int height,
                                           Direction doorSide,
                                           BlockState foundation,
                                           BlockState trim,
                                           BlockState roofStairs) {
        if (width < 13 || depth < 13 || height < 10) return;
        // Put buttresses on the two long sides (avoid door side so entrance remains clean)
        boolean alongX = width >= depth;
        int step = 4;
        int yTop = Math.max(6, height - 2);
        if (alongX) {
            // buttress on north/south walls (z = -1 and z = depth)
            for (int x = 2; x <= width - 3; x += step) {
                if (doorSide == Direction.NORTH) continue;
                placeButtress(blocks, origin.add(x, 0, -2), yTop, foundation, trim, roofStairs, Direction.SOUTH);
                if (doorSide == Direction.SOUTH) continue;
                placeButtress(blocks, origin.add(x, 0, depth + 1), yTop, foundation, trim, roofStairs, Direction.NORTH);
            }
        } else {
            // buttress on west/east walls (x=-1 and x=width)
            for (int z = 2; z <= depth - 3; z += step) {
                if (doorSide == Direction.WEST) continue;
                placeButtress(blocks, origin.add(-2, 0, z), yTop, foundation, trim, roofStairs, Direction.EAST);
                if (doorSide == Direction.EAST) continue;
                placeButtress(blocks, origin.add(width + 1, 0, z), yTop, foundation, trim, roofStairs, Direction.WEST);
            }
        }
    }

    private static void placeButtress(List<PlannedBlock> blocks,
                                      BlockPos base,
                                      int yTop,
                                      BlockState foundation,
                                      BlockState trim,
                                      BlockState roofStairs,
                                      Direction towardWall) {
        // vertical pier
        blocks.add(new PlannedBlock(base, foundation));
        for (int y = 1; y <= yTop - 3; y++) blocks.add(new PlannedBlock(base.up(y), trim));
        // diagonal-ish arm using stairs (2 steps)
        BlockPos a0 = base.up(yTop - 3).offset(towardWall, 1);
        BlockPos a1 = base.up(yTop - 2).offset(towardWall, 2);
        blocks.add(new PlannedBlock(a0, withFacingIfPossible(roofStairs, towardWall)));
        blocks.add(new PlannedBlock(a1, withFacingIfPossible(roofStairs, towardWall)));
    }

    private static void addPointedDoorPortal(List<PlannedBlock> blocks,
                                             BlockPos origin,
                                             int width,
                                             int depth,
                                             int height,
                                             Direction doorSide,
                                             BlockState trim,
                                             BlockState roofStairs) {
        if (trim == null || roofStairs == null) return;
        if (width < 9 || depth < 9 || height < 6) return;
        boolean onNS = (doorSide == Direction.NORTH || doorSide == Direction.SOUTH);
        int center = onNS ? (width / 2) : (depth / 2);
        int x = onNS ? center : (doorSide == Direction.EAST ? width - 1 : 0);
        int z = onNS ? (doorSide == Direction.SOUTH ? depth - 1 : 0) : center;

        // Frame around the door on the wall plane:
        // - two vertical trims, then a pointed top using stairs
        int y0 = 1;
        int yTop = Math.min(height - 2, 5);
        for (int y = y0; y <= yTop; y++) {
            if (onNS) {
                blocks.add(new PlannedBlock(origin.add(x - 1, y, z), trim));
                blocks.add(new PlannedBlock(origin.add(x + 1, y, z), trim));
            } else {
                blocks.add(new PlannedBlock(origin.add(x, y, z - 1), trim));
                blocks.add(new PlannedBlock(origin.add(x, y, z + 1), trim));
            }
        }
        // pointed apex at yTop+1
        int apexY = Math.min(height - 1, yTop + 1);
        if (onNS) {
            Direction fL = (doorSide == Direction.NORTH) ? Direction.EAST : Direction.EAST;
            Direction fR = (doorSide == Direction.NORTH) ? Direction.WEST : Direction.WEST;
            blocks.add(new PlannedBlock(origin.add(x - 1, apexY, z), withFacingIfPossible(roofStairs, fL)));
            blocks.add(new PlannedBlock(origin.add(x + 1, apexY, z), withFacingIfPossible(roofStairs, fR)));
            blocks.add(new PlannedBlock(origin.add(x, apexY + 1 <= height ? (apexY + 1) : apexY, z), trim));
        } else {
            Direction fL = (doorSide == Direction.EAST) ? Direction.SOUTH : Direction.SOUTH;
            Direction fR = (doorSide == Direction.EAST) ? Direction.NORTH : Direction.NORTH;
            blocks.add(new PlannedBlock(origin.add(x, apexY, z - 1), withFacingIfPossible(roofStairs, fR)));
            blocks.add(new PlannedBlock(origin.add(x, apexY, z + 1), withFacingIfPossible(roofStairs, fL)));
            blocks.add(new PlannedBlock(origin.add(x, apexY + 1 <= height ? (apexY + 1) : apexY, z), trim));
        }
    }

    private static boolean isPointedArchWindowSafe(BuildStrategy wallStrategy,
                                                   double windowRatio,
                                                   boolean preferSymmetry,
                                                   int x,
                                                   int z,
                                                   int width,
                                                   int depth,
                                                   Direction doorSide) {
        // avoid door vicinity and corners
        if (isNearDoor(doorSide, x, z, width, depth)) return false;
        boolean corner = (x == 0 || x == width - 1) && (z == 0 || z == depth - 1);
        if (corner) return false;

        boolean onNorthSouth = (z == 0 || z == depth - 1);
        boolean onWestEast = (x == 0 || x == width - 1);
        if (!onNorthSouth && !onWestEast) return false;

        // need lateral neighbors to form an arch; ensure they are not also windows
        if (onNorthSouth) {
            if (x - 1 <= 1 || x + 1 >= width - 2) return false;
            boolean leftWouldBeWindow = isShouldPlaceWindow(wallStrategy, windowRatio, preferSymmetry, x - 1, z, width, depth);
            boolean rightWouldBeWindow = isShouldPlaceWindow(wallStrategy, windowRatio, preferSymmetry, x + 1, z, width, depth);
            return !leftWouldBeWindow && !rightWouldBeWindow;
        }
        // onWestEast
        if (z - 1 <= 1 || z + 1 >= depth - 2) return false;
        boolean a = isShouldPlaceWindow(wallStrategy, windowRatio, preferSymmetry, x, z - 1, width, depth);
        boolean b = isShouldPlaceWindow(wallStrategy, windowRatio, preferSymmetry, x, z + 1, width, depth);
        return !a && !b;
    }

    private static void addPointedWindowFrame(List<PlannedBlock> blocks,
                                             BlockPos origin,
                                             int x,
                                             int yFrame,
                                             int z,
                                             int width,
                                             int depth,
                                             BlockState trim,
                                             BlockState roofStairs) {
        if (blocks == null || origin == null || trim == null || roofStairs == null) return;
        // Determine inward-facing direction (so stairs slope toward the interior)
        Direction inward;
        if (z == 0) inward = Direction.SOUTH;
        else if (z == depth - 1) inward = Direction.NORTH;
        else if (x == 0) inward = Direction.EAST;
        else if (x == width - 1) inward = Direction.WEST;
        else return;

        // Two side "arch shoulders" + a small lintel line for stronger silhouette
        if (z == 0 || z == depth - 1) {
            blocks.add(new PlannedBlock(origin.add(x - 1, yFrame, z), withFacingIfPossible(roofStairs, inward)));
            blocks.add(new PlannedBlock(origin.add(x + 1, yFrame, z), withFacingIfPossible(roofStairs, inward)));
            blocks.add(new PlannedBlock(origin.add(x, yFrame, z), trim));
        } else {
            blocks.add(new PlannedBlock(origin.add(x, yFrame, z - 1), withFacingIfPossible(roofStairs, inward)));
            blocks.add(new PlannedBlock(origin.add(x, yFrame, z + 1), withFacingIfPossible(roofStairs, inward)));
            blocks.add(new PlannedBlock(origin.add(x, yFrame, z), trim));
        }
        // Apex
        blocks.add(new PlannedBlock(origin.add(x, yFrame + 1, z), trim));
    }

    private static void addMullionBehindWindow(List<PlannedBlock> blocks,
                                               BlockPos origin,
                                               int x,
                                               int y,
                                               int z,
                                               int width,
                                               int depth,
                                               Direction doorSide) {
        if (blocks == null || origin == null) return;
        if (isNearDoor(doorSide, x, z, width, depth)) return;
        boolean onNorth = (z == 0);
        boolean onSouth = (z == depth - 1);
        boolean onWest = (x == 0);
        boolean onEast = (x == width - 1);
        if (!(onNorth || onSouth || onWest || onEast)) return;

        // inside-adjacent cell
        int ix = x;
        int iz = z;
        if (onNorth) iz = z + 1;
        else if (onSouth) iz = z - 1;
        else if (onWest) ix = x + 1;
        else if (onEast) ix = x - 1;

        if (ix <= 0 || ix >= width - 1 || iz <= 0 || iz >= depth - 1) return;
        blocks.add(new PlannedBlock(origin.add(ix, y, iz), Blocks.IRON_BARS.getDefaultState()));
    }

    // ========== Ming/Qing courtyard ==========

    private static void generateMingQingCourtyard(BuildingSpec spec, BlockPos origin, ServerWorld world, List<PlannedBlock> blocks, int width, int depth) {
        // palette：红墙灰瓦/黄瓦（imperial 可通过 extra/roof material 控制）
        BlockState wall = Blocks.RED_TERRACOTTA.getDefaultState();                 // 红墙
        BlockState foundation = Blocks.STONE_BRICKS.getDefaultState();            // 台基/基座
        BlockState courtyardFloor = Blocks.STONE_BRICKS.getDefaultState();        // 院落地面（先用石砖，后续可加青砖纹理）
        BlockState pillar = Blocks.DARK_OAK_LOG.getDefaultState();                // 木柱
        BlockState trim = Blocks.DARK_OAK_PLANKS.getDefaultState();               // 额枋/梁/檐口
        BlockState roofMain = Blocks.DEEPSLATE_TILES.getDefaultState();           // 灰瓦（用深板岩瓦近似）
        BlockState roofSlab = Blocks.DEEPSLATE_TILE_SLAB.getDefaultState();
        BlockState roofStairs = Blocks.DEEPSLATE_TILE_STAIRS.getDefaultState();

        // imperial: 黄瓦（可选）
        boolean imperial = false;
        try {
            if (spec != null && spec.getExtra() != null) {
                Object v = spec.getExtra().get("imperial");
                if (v instanceof Boolean b) imperial = b;
                if (v instanceof String s && (s.equalsIgnoreCase("true") || s.equals("1"))) imperial = true;
            }
        } catch (Throwable ignored) {}
        if (imperial) {
            roofMain = Blocks.YELLOW_GLAZED_TERRACOTTA.getDefaultState();
            roofSlab = Blocks.SMOOTH_SANDSTONE_SLAB.getDefaultState();
            roofStairs = Blocks.SMOOTH_SANDSTONE_STAIRS.getDefaultState();
        }

        // 1) 清空空间（避免山体干扰）
        int clearH = 14;
        for (int x = -1; x <= width + 1; x++) {
            for (int z = -1; z <= depth + 1; z++) {
                for (int y = 0; y <= clearH; y++) {
                    blocks.add(new PlannedBlock(origin.add(x, y, z), Blocks.AIR.getDefaultState()));
                }
            }
        }

        // 2) 外围围墙（红墙 + 台基）
        int wallH = 4;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < wallH; y++) {
                blocks.add(new PlannedBlock(origin.add(x, 0, 0), foundation));
                blocks.add(new PlannedBlock(origin.add(x, 0, depth - 1), foundation));
                if (y > 0) {
                    blocks.add(new PlannedBlock(origin.add(x, y, 0), wall));
                    blocks.add(new PlannedBlock(origin.add(x, y, depth - 1), wall));
                }
            }
        }
        for (int z = 0; z < depth; z++) {
            for (int y = 0; y < wallH; y++) {
                blocks.add(new PlannedBlock(origin.add(0, 0, z), foundation));
                blocks.add(new PlannedBlock(origin.add(width - 1, 0, z), foundation));
                if (y > 0) {
                    blocks.add(new PlannedBlock(origin.add(0, y, z), wall));
                    blocks.add(new PlannedBlock(origin.add(width - 1, y, z), wall));
                }
            }
        }

        // 3) 门楼（南侧中门，z=0）
        int gateW = 4;
        int gateX0 = width / 2 - gateW / 2;
        int gateH = 3;
        for (int x = gateX0; x < gateX0 + gateW; x++) {
            for (int y = 1; y <= gateH; y++) {
                blocks.add(new PlannedBlock(origin.add(x, y, 0), Blocks.AIR.getDefaultState()));
            }
        }
        // 门柱
        blocks.add(new PlannedBlock(origin.add(gateX0 - 1, 1, 0), pillar));
        blocks.add(new PlannedBlock(origin.add(gateX0 - 1, 2, 0), pillar));
        blocks.add(new PlannedBlock(origin.add(gateX0 + gateW, 1, 0), pillar));
        blocks.add(new PlannedBlock(origin.add(gateX0 + gateW, 2, 0), pillar));
        // 门楣
        for (int x = gateX0 - 1; x <= gateX0 + gateW; x++) {
            blocks.add(new PlannedBlock(origin.add(x, gateH + 1, 0), trim));
        }
        // 门楼小屋顶（hipped）
        addHippedRoof(blocks, origin.add(gateX0 - 2, 1, 0), gateW + 4, 3, gateH + 1, roofMain, roofStairs, roofSlab, trim, false, false);

        // 4) 院落地面（石砖，留出主殿/厢房占地）
        for (int x = 1; x < width - 1; x++) {
            for (int z = 1; z < depth - 1; z++) {
                blocks.add(new PlannedBlock(origin.add(x, 0, z), courtyardFloor));
            }
        }
        // 中轴道路（更像官式）
        int cx = width / 2;
        for (int z = 1; z < depth - 2; z++) {
            blocks.add(new PlannedBlock(origin.add(cx, 0, z), Blocks.POLISHED_ANDESITE.getDefaultState()));
            blocks.add(new PlannedBlock(origin.add(cx - 1, 0, z), Blocks.POLISHED_ANDESITE.getDefaultState()));
        }

        // 5) 主殿（北侧，面向院落）
        int hallW = Math.max(8, width - 6);
        int hallD = 6;
        int hallX0 = (width - hallW) / 2;
        int hallZ0 = depth - hallD - 2;
        int hallH = 6;
        addMingQingHall(blocks, origin.add(hallX0, 0, hallZ0), hallW, hallD, hallH,
                wall, foundation, pillar, trim, roofMain, roofStairs, roofSlab, true);

        // 6) 东西厢房（可选：宽度足够时加）
        if (width >= 20) {
            int wingW = 6;
            int wingD = depth - hallD - 6;
            int wingH = 5;
            int leftX = 2;
            int rightX = width - wingW - 2;
            int wingZ = 4;
            addMingQingHall(blocks, origin.add(leftX, 0, wingZ), wingW, wingD, wingH,
                    wall, foundation, pillar, trim, roofMain, roofStairs, roofSlab, false);
            addMingQingHall(blocks, origin.add(rightX, 0, wingZ), wingW, wingD, wingH,
                    wall, foundation, pillar, trim, roofMain, roofStairs, roofSlab, false);
        }
    }

    private static void addMingQingHall(List<PlannedBlock> blocks, BlockPos o, int w, int d, int h,
                                        BlockState wall, BlockState foundation, BlockState pillar, BlockState trim,
                                        BlockState roofMain, BlockState roofStairs, BlockState roofSlab,
                                        boolean mainHall) {
        // 台基
        for (int x = -1; x <= w; x++) {
            for (int z = -1; z <= d; z++) {
                blocks.add(new PlannedBlock(o.add(x, 0, z), foundation));
            }
        }

        // 柱网 + 墙体（留门洞）
        int doorW = mainHall ? 3 : 2;
        int dx0 = w / 2 - doorW / 2;
        for (int y = 1; y <= h; y++) {
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    boolean edge = (x == 0 || x == w - 1 || z == 0 || z == d - 1);
                    if (!edge) continue;

                    // 柱（四角+门廊柱）
                    boolean isCorner = (x == 0 || x == w - 1) && (z == 0 || z == d - 1);
                    boolean isPorchPillar = (z == 0) && (x == dx0 - 1 || x == dx0 + doorW);
                    if (isCorner || isPorchPillar) {
                        blocks.add(new PlannedBlock(o.add(x, y, z), pillar));
                        continue;
                    }

                    // 门洞（朝院落方向：z==0）
                    boolean inDoor = (z == 0) && (x >= dx0 && x < dx0 + doorW) && (y <= 3);
                    if (inDoor) {
                        blocks.add(new PlannedBlock(o.add(x, y, z), Blocks.AIR.getDefaultState()));
                        continue;
                    }

                    // 窗带（格栅窗）
                    boolean windowBand = (y == 2);
                    boolean inWindow = windowBand && (z == 0 || z == d - 1 || x == 0 || x == w - 1);
                    if (inWindow && !((z == 0) && (x >= dx0 - 1 && x <= dx0 + doorW))) {
                        blocks.add(new PlannedBlock(o.add(x, y, z), Blocks.DARK_OAK_TRAPDOOR.getDefaultState()));
                        continue;
                    }

                    // 腰线/额枋
                    if (y == 4 || y == h) {
                        blocks.add(new PlannedBlock(o.add(x, y, z), trim));
                        continue;
                    }

                    blocks.add(new PlannedBlock(o.add(x, y, z), wall));
                }
            }
        }

        // 地面（室内）
        for (int x = 1; x < w - 1; x++) {
            for (int z = 1; z < d - 1; z++) {
                blocks.add(new PlannedBlock(o.add(x, 0, z), Blocks.OAK_PLANKS.getDefaultState()));
            }
        }

        // 屋顶（官式：hipped）
        // 主殿开启飞檐角 + 更细檐口层次；厢房不开启（避免过花）
        addHippedRoof(blocks, o, w, d, h, roofMain, roofStairs, roofSlab, trim, true, mainHall);

        // 彩画点缀：檐下绿/蓝条带（少量即可）
        for (int x = 0; x < w; x++) {
            blocks.add(new PlannedBlock(o.add(x, h - 1, -1), Blocks.GREEN_GLAZED_TERRACOTTA.getDefaultState()));
            blocks.add(new PlannedBlock(o.add(x, h - 1, d), Blocks.BLUE_GLAZED_TERRACOTTA.getDefaultState()));
        }
    }

    @SuppressWarnings("unused")
    private static void addHippedRoof(List<PlannedBlock> blocks, BlockPos o, int w, int d, int baseH,
                                      BlockState roofMain, BlockState roofStairs, BlockState roofSlab, BlockState trim) {
        addHippedRoof(blocks, o, w, d, baseH, roofMain, roofStairs, roofSlab, trim, true, false);
    }

    /**
     * Spire roof (MVP):
     * - Build a steep gable as base.
     * - Add 4 corner spires for a gothic silhouette.
     */
    private static void addSpireRoof(List<PlannedBlock> blocks, BlockPos origin, int width, int depth, int height,
                                     BlockState roofMain, BlockState roofStairs, BlockState roofSlab, BlockState trim) {
        int roofHeight = Math.min(Math.min(width, depth) / 2 + 2, 9);
        // Steep gable (along X)
        for (int i = 0; i < roofHeight; i++) {
            int rightX = width - 1 - i;
            if (i > rightX) break;
            for (int z = -1; z <= depth; z++) {
                blocks.add(new PlannedBlock(origin.add(i, height + i, z), withFacingIfPossible(roofStairs, Direction.EAST)));
                blocks.add(new PlannedBlock(origin.add(rightX, height + i, z), withFacingIfPossible(roofStairs, Direction.WEST)));
            }
        }
        // Ridge spike line (slab + trim)
        int ridgeY = height + roofHeight - 1;
        int midLeft = (width - 1) / 2;
        int midRight = width / 2;
        for (int z = -1; z <= depth; z++) {
            blocks.add(new PlannedBlock(origin.add(midLeft, ridgeY + 1, z), roofSlab));
            blocks.add(new PlannedBlock(origin.add(midRight, ridgeY + 1, z), roofSlab));
            if ((z & 1) == 0) {
                blocks.add(new PlannedBlock(origin.add(midLeft, ridgeY + 2, z), trim));
            }
        }

        // Corner spires (small vertical tapers)
        int spireBaseY = height + 1;
        int spireH = Math.max(4, Math.min(10, roofHeight + 1));
        addCornerSpire(blocks, origin.add(0, spireBaseY, 0), spireH, roofMain, trim);
        addCornerSpire(blocks, origin.add(width - 1, spireBaseY, 0), spireH, roofMain, trim);
        addCornerSpire(blocks, origin.add(0, spireBaseY, depth - 1), spireH, roofMain, trim);
        addCornerSpire(blocks, origin.add(width - 1, spireBaseY, depth - 1), spireH, roofMain, trim);
    }

    private static void addCornerSpire(List<PlannedBlock> blocks, BlockPos base, int h, BlockState body, BlockState tip) {
        for (int i = 0; i < h; i++) {
            int r = Math.max(0, 1 - (i / 3)); // taper quickly (2x2 then 1x1)
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = base.add(dx, i, dz);
                    blocks.add(new PlannedBlock(p, (i == h - 1) ? tip : body));
                }
            }
        }
    }

    /**
     * 官式四坡屋顶（近似庑殿/歇山的屋面表达）
     * - overhang: 出檐（扩大一圈）
     * - flyingEaves: 飞檐角（四角上翘）+ 更细檐口层次
     */
    private static void addHippedRoof(List<PlannedBlock> blocks, BlockPos o, int w, int d, int baseH,
                                      BlockState roofMain, BlockState roofStairs, BlockState roofSlab, BlockState trim,
                                      boolean overhang, boolean flyingEaves) {
        int ox = overhang ? -1 : 0;
        int oz = overhang ? -1 : 0;
        int ow = w + (overhang ? 2 : 0);
        int od = d + (overhang ? 2 : 0);

        // 更细的檐口层次：屋顶起坡之前先做两道“檐口线脚”（更像官式）
        if (overhang) {
            int y0 = baseH - 1;
            // 外圈：roofSlab（滴水线）
            for (int x = ox - 1; x <= ox + ow; x++) {
                blocks.add(new PlannedBlock(o.add(x, y0, oz - 1), roofSlab));
                blocks.add(new PlannedBlock(o.add(x, y0, oz + od), roofSlab));
            }
            for (int z = oz - 1; z <= oz + od; z++) {
                blocks.add(new PlannedBlock(o.add(ox - 1, y0, z), roofSlab));
                blocks.add(new PlannedBlock(o.add(ox + ow, y0, z), roofSlab));
            }
            // 内圈：trim（额枋/檐口线）
            for (int x = ox; x <= ox + ow - 1; x++) {
                blocks.add(new PlannedBlock(o.add(x, y0, oz), trim));
                blocks.add(new PlannedBlock(o.add(x, y0, oz + od - 1), trim));
            }
            for (int z = oz; z <= oz + od - 1; z++) {
                blocks.add(new PlannedBlock(o.add(ox, y0, z), trim));
                blocks.add(new PlannedBlock(o.add(ox + ow - 1, y0, z), trim));
            }
        }

        int layers = Math.min(Math.min(ow, od) / 2 + 1, 7);
        for (int i = 0; i < layers; i++) {
            int x0 = ox + i;
            int x1 = ox + ow - 1 - i;
            int z0 = oz + i;
            int z1 = oz + od - 1 - i;
            if (x0 > x1 || z0 > z1) break;

            int y = baseH + i;

            // 屋面边缘用 stairs，内部用 roofMain（近似瓦面层次）
            for (int x = x0; x <= x1; x++) {
                blocks.add(new PlannedBlock(o.add(x, y, z0), withFacingIfPossible(roofStairs, Direction.SOUTH)));
                blocks.add(new PlannedBlock(o.add(x, y, z1), withFacingIfPossible(roofStairs, Direction.NORTH)));
            }
            for (int z = z0; z <= z1; z++) {
                blocks.add(new PlannedBlock(o.add(x0, y, z), withFacingIfPossible(roofStairs, Direction.EAST)));
                blocks.add(new PlannedBlock(o.add(x1, y, z), withFacingIfPossible(roofStairs, Direction.WEST)));
            }

            for (int x = x0 + 1; x <= x1 - 1; x++) {
                for (int z = z0 + 1; z <= z1 - 1; z++) {
                    blocks.add(new PlannedBlock(o.add(x, y, z), roofMain));
                }
            }

            // 檐口收边（每层下沿一圈 trim）
            if (i == 0 && overhang) {
                for (int x = x0; x <= x1; x++) {
                    blocks.add(new PlannedBlock(o.add(x, baseH - 1, z0), trim));
                    blocks.add(new PlannedBlock(o.add(x, baseH - 1, z1), trim));
                }
                for (int z = z0; z <= z1; z++) {
                    blocks.add(new PlannedBlock(o.add(x0, baseH - 1, z), trim));
                    blocks.add(new PlannedBlock(o.add(x1, baseH - 1, z), trim));
                }
            }
        }

        // 顶部封顶
        int topY = baseH + layers;
        int cx = ox + ow / 2;
        int cz = oz + od / 2;
        blocks.add(new PlannedBlock(o.add(cx, topY, cz), roofSlab));

        // 飞檐角：四角外挑 + 上翘（用 slab 逐级抬升，稳定且不会出现“空洞”）
        if (flyingEaves && overhang) {
            addFlyingEavesCorners(blocks, o, baseH, ox, oz, ow, od, roofSlab);
        }
    }

    /**
     * 歇山顶（hip-and-gable, MVP）
     * 在四坡屋顶基础上“收短屋脊 + 两端斜收”，形成明显区别于纯四坡的轮廓。
     *
     * 约束：
     * - 仍保持轴对齐（不做任意角旋转）
     * - 以 Z 方向为“进深”，在两端（z=-1 / z=depth）做斜收
     */
    private static void addXieShanRoof(List<PlannedBlock> blocks, BlockPos o, int w, int d, int baseH,
                                       BlockState roofMain, BlockState roofStairs, BlockState roofSlab, BlockState trim,
                                       boolean overhang, boolean flyingEaves) {
        int ox = overhang ? -1 : 0;
        int oz = overhang ? -1 : 0;
        int ow = w + (overhang ? 2 : 0);
        int od = d + (overhang ? 2 : 0);

        // reuse the same eaves/cornice layering as hipped roof for consistency
        if (overhang) {
            int y0 = baseH - 1;
            for (int x = ox - 1; x <= ox + ow; x++) {
                blocks.add(new PlannedBlock(o.add(x, y0, oz - 1), roofSlab));
                blocks.add(new PlannedBlock(o.add(x, y0, oz + od), roofSlab));
            }
            for (int z = oz - 1; z <= oz + od; z++) {
                blocks.add(new PlannedBlock(o.add(ox - 1, y0, z), roofSlab));
                blocks.add(new PlannedBlock(o.add(ox + ow, y0, z), roofSlab));
            }
            for (int x = ox; x <= ox + ow - 1; x++) {
                blocks.add(new PlannedBlock(o.add(x, y0, oz), trim));
                blocks.add(new PlannedBlock(o.add(x, y0, oz + od - 1), trim));
            }
            for (int z = oz; z <= oz + od - 1; z++) {
                blocks.add(new PlannedBlock(o.add(ox, y0, z), trim));
                blocks.add(new PlannedBlock(o.add(ox + ow - 1, y0, z), trim));
            }
        }

        // Roof height: similar to gable, but capped for stability
        int layers = Math.min(Math.min(ow, od) / 2 + 1, 8);
        int capDepth = Math.max(2, Math.min(4, layers)); // how much to "hip" the gable ends

        // Build per-Z slices: full height in the middle, reduced height near both ends -> creates hip ends
        for (int z = oz; z <= oz + od - 1; z++) {
            int distToEnd = Math.min(z - oz, (oz + od - 1) - z);
            int reduce = Math.max(0, (capDepth - 1) - distToEnd);
            int hSlice = layers - reduce;
            if (hSlice <= 0) continue;

            for (int i = 0; i < hSlice; i++) {
                int x0 = ox + i;
                int x1 = ox + ow - 1 - i;
                if (x0 > x1) break;
                int y = baseH + i;

                // side slopes (along X) using stairs; these define the main gable silhouette
                blocks.add(new PlannedBlock(o.add(x0, y, z), withFacingIfPossible(roofStairs, Direction.EAST)));
                blocks.add(new PlannedBlock(o.add(x1, y, z), withFacingIfPossible(roofStairs, Direction.WEST)));

                // fill between slopes for this slice
                for (int x = x0 + 1; x <= x1 - 1; x++) {
                    blocks.add(new PlannedBlock(o.add(x, y, z), roofMain));
                }
            }

            // ridge line: shorter at ends due to reduced hSlice -> distinctive xie-shan roof ridge
            int ridgeY = baseH + hSlice;
            int midLeft = ox + (ow - 1) / 2;
            int midRight = ox + (ow / 2);
            blocks.add(new PlannedBlock(o.add(midLeft, ridgeY, z), roofSlab));
            blocks.add(new PlannedBlock(o.add(midRight, ridgeY, z), roofSlab));
        }

        // flying eaves corners: reuse existing horn for imperial silhouette emphasis
        if (flyingEaves && overhang) {
            addFlyingEavesCorners(blocks, o, baseH, ox, oz, ow, od, roofSlab);
        }
    }


    private static boolean isJapaneseTraditionalStyle(BuildingSpec spec, String paletteId) {
        try {
            if (spec != null && spec.getExtra() != null) {
                Object spid = spec.getExtra().get("styleProfileId");
                if (spid != null) {
                    String s = String.valueOf(spid).trim();
                    if (s.equals("Japanese_Traditional")) return true;
                }
            }
        } catch (Throwable ignored) {}
        // Current Japanese_Traditional defaults use PALETTE_EAST_ASIAN_WOOD_A, so we can't disambiguate by paletteId.
        // We still allow palette-based opt-in for future dedicated Japanese palettes.
        if (paletteId == null) return false;
        String p = paletteId.trim().toUpperCase(java.util.Locale.ROOT);
        return p.contains("JAPAN") || p.contains("JAPANESE");
    }

    /**
     * 唐破风（kara-hafu）MVP：在正面山墙做一个“拱形/曲线”屋檐轮廓。
     * - 仅做识别性，不追求精确曲线（方块锯齿近似）
     * - 依赖 roofSlab/trim，保持材质一致
     */
    private static void addKaraHafuGable(List<PlannedBlock> blocks, BlockPos origin,
                                         int width, int depth, int height, int roofHeight,
                                         Direction doorSide, BlockState roofSlab, BlockState trim) {
        if (blocks == null || origin == null) return;
        if (width < 9 || depth < 7) return;
        if (doorSide == null) doorSide = Direction.SOUTH;
        if (roofSlab == null) roofSlab = Blocks.STONE_BRICK_SLAB.getDefaultState();
        if (trim == null) trim = Blocks.STONE_BRICKS.getDefaultState();

        // Front gable plane: z=-1 for NORTH, z=depth for SOUTH (matches roof overhang loops)
        int zFront = (doorSide == Direction.NORTH) ? -1 : depth;
        int yBase = height + Math.max(1, roofHeight / 2);
        int yPeak = height + roofHeight + 2;

        int mid = width / 2;
        int span = Math.min(6, Math.max(4, width / 3));
        int x0 = Math.max(1, mid - span);
        int x1 = Math.min(width - 2, mid + span);

        // build a stepped "arc": higher in the center, lower toward sides
        for (int x = x0; x <= x1; x++) {
            int dx = Math.abs(x - mid);
            int y = yPeak - (dx / 2); // pseudo curve
            y = Math.max(yBase, Math.min(yPeak, y));
            blocks.add(new PlannedBlock(origin.add(x, y, zFront), roofSlab));
            // add a small vertical trim drop to emphasize the curve silhouette
            if ((dx & 1) == 0) {
                blocks.add(new PlannedBlock(origin.add(x, y - 1, zFront), trim));
            }
        }

        // center pendant (like a small ridge ornament)
        blocks.add(new PlannedBlock(origin.add(mid, yPeak - 1, zFront), trim));
    }


    private static void addFlyingEavesCorners(List<PlannedBlock> blocks, BlockPos o, int baseH,
                                              int ox, int oz, int ow, int od, BlockState roofSlab) {
        // 四角坐标（以 overhang 扩展后的外轮廓为基准）
        int x1 = ox + ow - 1;
        int z1 = oz + od - 1;

        // 每个角向外延伸 2 格，并逐级抬升 2 格
        // 角1：(-,-)
        addCornerHorn(blocks, o, ox, oz, -1, -1, baseH, roofSlab);
        // 角2：(+, -)
        addCornerHorn(blocks, o, x1, oz, +1, -1, baseH, roofSlab);
        // 角3：(-, +)
        addCornerHorn(blocks, o, ox, z1, -1, +1, baseH, roofSlab);
        // 角4：(+, +)
        addCornerHorn(blocks, o, x1, z1, +1, +1, baseH, roofSlab);
    }

    private static void addCornerHorn(List<PlannedBlock> blocks, BlockPos o,
                                      int cx, int cz, int sx, int sz, int baseH, BlockState slab) {
        // level 0：角点本身稍微抬起一点（让角更“翘”）
        blocks.add(new PlannedBlock(o.add(cx, baseH + 1, cz), slab));

        // level 1：向外 1 格（对角），抬升 1
        blocks.add(new PlannedBlock(o.add(cx + sx, baseH + 2, cz), slab));
        blocks.add(new PlannedBlock(o.add(cx, baseH + 2, cz + sz), slab));
        blocks.add(new PlannedBlock(o.add(cx + sx, baseH + 2, cz + sz), slab));

        // level 2：向外 2 格（对角），再抬升 1
        blocks.add(new PlannedBlock(o.add(cx + sx * 2, baseH + 3, cz + sz), slab));
        blocks.add(new PlannedBlock(o.add(cx + sx, baseH + 3, cz + sz * 2), slab));
        blocks.add(new PlannedBlock(o.add(cx + sx * 2, baseH + 3, cz + sz * 2), slab));
    }

    private static void addDougongAndPainting(List<PlannedBlock> blocks, BlockPos origin, int width, int depth, int height, BlockState trim) {
        // 檐下“斗拱”简化：每隔 2 格在外墙下沿挂一块（stairs/slab/栅栏都可），这里用 trapdoor/柱材太花，先用 trim
        int y = height - 2;
        for (int x = 0; x < width; x += 2) {
            blocks.add(new PlannedBlock(origin.add(x, y, -1), trim));
            blocks.add(new PlannedBlock(origin.add(x, y, depth), trim));
        }
        for (int z = 0; z < depth; z += 2) {
            blocks.add(new PlannedBlock(origin.add(-1, y, z), trim));
            blocks.add(new PlannedBlock(origin.add(width, y, z), trim));
        }

        // 彩画点缀：檐下用少量绿/蓝，避免过花
        for (int x = 1; x < width - 1; x += 3) {
            blocks.add(new PlannedBlock(origin.add(x, height - 2, -1), Blocks.GREEN_TERRACOTTA.getDefaultState()));
        }
        for (int z = 1; z < depth - 1; z += 3) {
            blocks.add(new PlannedBlock(origin.add(width, height - 2, z), Blocks.BLUE_TERRACOTTA.getDefaultState()));
        }
    }

    private static BlockState defaultWall(BuildingStyle style) {
        if (style == null) style = BuildingStyle.DEFAULT;
        return switch (style) {
            case MODERN -> Blocks.WHITE_CONCRETE.getDefaultState();
            case ASIAN ->
                // 明清官式默认红墙
                    Blocks.RED_TERRACOTTA.getDefaultState();
            case FUTURISTIC -> Blocks.QUARTZ_BLOCK.getDefaultState();
            case RUSTIC -> Blocks.SPRUCE_PLANKS.getDefaultState();
            case MEDIEVAL -> Blocks.STONE_BRICKS.getDefaultState();
            default -> Blocks.OAK_PLANKS.getDefaultState();
        };
    }

    private static BlockState defaultFloor(BuildingStyle style) {
        if (style == null) style = BuildingStyle.DEFAULT;
        return switch (style) {
            case MODERN, FUTURISTIC -> Blocks.SMOOTH_QUARTZ.getDefaultState();
            case ASIAN, MEDIEVAL, DEFAULT -> Blocks.OAK_PLANKS.getDefaultState();
            case RUSTIC -> Blocks.SPRUCE_PLANKS.getDefaultState();
            default -> Blocks.OAK_PLANKS.getDefaultState();
        };
    }

    private static BlockState defaultWindow(BuildingStyle style) {
        if (style == null) style = BuildingStyle.DEFAULT;
        return switch (style) {
            case MODERN, FUTURISTIC -> Blocks.GLASS.getDefaultState();
            default -> Blocks.GLASS_PANE.getDefaultState();
        };
    }

    private static BlockState defaultRoof(BuildingStyle style) {
        if (style == null) style = BuildingStyle.DEFAULT;
        return switch (style) {
            case MODERN -> Blocks.BLACK_CONCRETE.getDefaultState();
            case FUTURISTIC -> Blocks.QUARTZ_BLOCK.getDefaultState();
            case ASIAN ->
                // 官式灰瓦（近似）：深板岩瓦
                    Blocks.DEEPSLATE_TILES.getDefaultState();
            case MEDIEVAL, DEFAULT -> Blocks.DARK_OAK_PLANKS.getDefaultState();
            case RUSTIC -> Blocks.SPRUCE_PLANKS.getDefaultState();
            default -> Blocks.DARK_OAK_PLANKS.getDefaultState();
        };
    }

    private static BlockState defaultTrim(BuildingStyle style, BlockState wall) {
        // 如果墙材本身是 stone bricks，trim 用石砖更一致
        if (wall != null && wall.getBlock() == Blocks.STONE_BRICKS) {
            return Blocks.CHISELED_STONE_BRICKS.getDefaultState();
        }
        if (style == null) style = BuildingStyle.DEFAULT;
        return switch (style) {
            case MODERN -> Blocks.BLACK_CONCRETE.getDefaultState();
            case FUTURISTIC -> Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState();
            case ASIAN -> Blocks.RED_TERRACOTTA.getDefaultState();
            default -> Blocks.SPRUCE_LOG.getDefaultState();
        };
    }

    private static BlockState defaultFoundation(BuildingStyle style, BlockState wall) {
        if (wall != null && (wall.getBlock() == Blocks.WHITE_CONCRETE || wall.getBlock() == Blocks.QUARTZ_BLOCK)) {
            return Blocks.SMOOTH_STONE.getDefaultState();
        }
        return switch (style) {
            case MODERN, FUTURISTIC -> Blocks.SMOOTH_STONE.getDefaultState();
            case ASIAN -> Blocks.POLISHED_BLACKSTONE.getDefaultState();
            case RUSTIC, MEDIEVAL, DEFAULT -> Blocks.COBBLESTONE.getDefaultState();
        };
    }

    private static BlockState defaultPillar(BuildingStyle style) {
        return switch (style) {
            case MODERN, FUTURISTIC -> Blocks.QUARTZ_PILLAR.getDefaultState();
            case ASIAN -> Blocks.DARK_OAK_LOG.getDefaultState();
            case RUSTIC -> Blocks.SPRUCE_LOG.getDefaultState();
            case MEDIEVAL, DEFAULT -> Blocks.OAK_LOG.getDefaultState();
        };
    }

    private static BlockState defaultRoofStairs(BuildingStyle style, BlockState roof) {
        Block b = roof != null ? roof.getBlock() : null;
        if (b == Blocks.DARK_OAK_PLANKS) return Blocks.DARK_OAK_STAIRS.getDefaultState();
        if (b == Blocks.SPRUCE_PLANKS) return Blocks.SPRUCE_STAIRS.getDefaultState();
        if (b == Blocks.OAK_PLANKS) return Blocks.OAK_STAIRS.getDefaultState();
        if (b == Blocks.BRICKS) return Blocks.BRICK_STAIRS.getDefaultState();
        if (b == Blocks.STONE_BRICKS) return Blocks.STONE_BRICK_STAIRS.getDefaultState();
        if (b == Blocks.BLACK_CONCRETE) return Blocks.POLISHED_BLACKSTONE_STAIRS.getDefaultState();
        return switch (style) {
            case MEDIEVAL, ASIAN, DEFAULT -> Blocks.DARK_OAK_STAIRS.getDefaultState();
            case RUSTIC -> Blocks.SPRUCE_STAIRS.getDefaultState();
            case MODERN, FUTURISTIC -> Blocks.POLISHED_BLACKSTONE_STAIRS.getDefaultState();
        };
    }

    private static BlockState defaultRoofSlab(BuildingStyle style, BlockState roof) {
        Block b = roof != null ? roof.getBlock() : null;
        if (b == Blocks.DARK_OAK_PLANKS) return Blocks.DARK_OAK_SLAB.getDefaultState();
        if (b == Blocks.SPRUCE_PLANKS) return Blocks.SPRUCE_SLAB.getDefaultState();
        if (b == Blocks.OAK_PLANKS) return Blocks.OAK_SLAB.getDefaultState();
        if (b == Blocks.BLACK_CONCRETE) return Blocks.POLISHED_BLACKSTONE_SLAB.getDefaultState();
        if (b == Blocks.STONE_BRICKS) return Blocks.STONE_BRICK_SLAB.getDefaultState();
        return switch (style) {
            case MODERN, FUTURISTIC -> Blocks.SMOOTH_STONE_SLAB.getDefaultState();
            case ASIAN, MEDIEVAL, DEFAULT -> Blocks.DARK_OAK_SLAB.getDefaultState();
            case RUSTIC -> Blocks.SPRUCE_SLAB.getDefaultState();
        };
    }

    private static BlockState defaultDoor(BuildingStyle style) {
        return switch (style) {
            case MODERN, FUTURISTIC -> Blocks.IRON_DOOR.getDefaultState();
            case ASIAN -> Blocks.DARK_OAK_DOOR.getDefaultState();
            case RUSTIC -> Blocks.SPRUCE_DOOR.getDefaultState();
            case MEDIEVAL, DEFAULT -> Blocks.OAK_DOOR.getDefaultState();
        };
    }

    private BlockState resolveWindowByStyleOption(ServerWorld world,
                                                BuildingStyle style,
                                                BuildingSpec spec,
                                                StyleGenome genome,
                                                StyleProfile profile,
                                                BlockState fallback,
                                                BlockState pillar,
                                                BlockState trim) {
        String windowStyle = resolveEffectiveWindowStyle(spec, genome, profile, style);
        String ws = (windowStyle == null) ? "" : windowStyle.trim().toLowerCase();

        switch (ws) {
            case "shoji", "paper" -> {
                return Blocks.WHITE_STAINED_GLASS_PANE.getDefaultState();
            }
            case "fence" -> {
                // lattice window: pick wood fence matching pillars if possible
                String pid = (profile != null && profile.palette() != null) ? profile.palette().pillar : null;
                if ((pid == null || pid.isBlank()) && pillar != null) {
                    pid = Registries.BLOCK.getId(pillar.getBlock()).toString();
                }
                String fenceId;
                if (pid != null && pid.contains("dark_oak")) fenceId = "minecraft:dark_oak_fence";
                else if (pid != null && pid.contains("spruce")) fenceId = "minecraft:spruce_fence";
                else fenceId = (style == BuildingStyle.ASIAN) ? "minecraft:oak_fence" : "minecraft:oak_fence";
                return getStateOrDefault(world, fenceId, Blocks.OAK_FENCE.getDefaultState());
            }
            case "bars", "iron_bars" -> {
                // medieval/castle: iron bar windows
                return Blocks.IRON_BARS.getDefaultState();
            }
            case "slit" -> {
                // Use bars for thin openings; density is handled by windowRatio clamps.
                return Blocks.IRON_BARS.getDefaultState();
            }
            case "stained" -> {
                // stained glass pane: derive color from trim first (if it's stained glass)
                String tid = (profile != null && profile.palette() != null) ? profile.palette().trim : null;
                if ((tid == null || tid.isBlank()) && trim != null) {
                    tid = Registries.BLOCK.getId(trim.getBlock()).toString();
                }
                String paneId = deriveStainedPaneId(tid);
                return getStateOrDefault(world, paneId, Blocks.LIGHT_BLUE_STAINED_GLASS_PANE.getDefaultState());
            }
            case "curtain_wall", "curtain" -> {
                // Modern curtain wall: prefer panes for thin facade, fallback to glass pane.
                try {
                    if (fallback != null && fallback.getBlock() == Blocks.TINTED_GLASS) {
                        return Blocks.TINTED_GLASS.getDefaultState();
                    }
                } catch (Throwable ignored) {}
                return Blocks.GLASS_PANE.getDefaultState();
            }
            default -> {
                // pane: if fallback is full glass, use glass pane for better proportions
                try {
                    if (fallback != null && fallback.getBlock() == Blocks.GLASS) {
                        return Blocks.GLASS_PANE.getDefaultState();
                    }
                } catch (Throwable ignored) {}
                return fallback != null ? fallback : Blocks.GLASS_PANE.getDefaultState();
            }
        }
    }

    private static String resolveEffectiveWindowStyle(BuildingSpec spec,
                                                     StyleGenome genome,
                                                     StyleProfile profile,
                                                     BuildingStyle style) {
        // Priority: StyleOptions（显式） > genome.params.windowStyle > styleProfile.details.windowStyle > heuristic default
        String windowStyle = (spec != null && spec.getStyleOptions() != null) ? spec.getStyleOptions().getWindowStyle() : null;
        if (windowStyle == null || windowStyle.isBlank()) {
            windowStyle = (genome != null && genome.params != null) ? genome.params.windowStyle : null;
        }
        if (windowStyle == null || windowStyle.isBlank()) {
            try {
                if (profile != null && profile.details() != null) {
                    windowStyle = profile.details().windowStyle;
                }
            } catch (Throwable ignored) {}
        }
        if (windowStyle == null || windowStyle.isBlank()) {
            windowStyle = switch (style) {
                case ASIAN -> "fence";
                case MEDIEVAL -> "bars";
                case MODERN, FUTURISTIC -> "pane";
                case RUSTIC -> "pane";
                default -> "pane";
            };
        }
        return windowStyle;
    }

    private static boolean isFenceLikeWindow(BlockState windowBlock) {
        if (windowBlock == null) return false;
        String id = Registries.BLOCK.getId(windowBlock.getBlock()).toString();
        return id.endsWith("_fence") && !id.endsWith("_fence_gate");
    }

    private static void tryCollectFenceFrame(Set<BlockPos> out,
                                             BlockPos origin,
                                             BuildStrategy wallStrategy,
                                             double windowRatio,
                                             boolean preferSymmetry,
                                             int x,
                                             int y,
                                             int z,
                                             int width,
                                             int depth) {
        if (x < 0 || z < 0 || x >= width || z >= depth) return;
        // avoid corner pillars
        if ((x == 0 || x == width - 1) && (z == 0 || z == depth - 1)) return;
        // avoid door area (front center)
        boolean nearDoor = (z == 0) && (x == width / 2 || x == width / 2 - 1);
        if (nearDoor) return;
        // avoid overwriting adjacent windows
        boolean wouldBeWindow = isShouldPlaceWindow(wallStrategy, windowRatio, preferSymmetry, x, z, width, depth);
        if (wouldBeWindow) return;
        out.add(origin.add(x, y, z));
    }

    private static String deriveStainedPaneId(String id) {
        if (id == null || id.isBlank()) return "minecraft:light_blue_stained_glass_pane";
        String s = id.trim();
        if (s.endsWith("_stained_glass_pane")) return s;
        if (s.endsWith("_stained_glass")) return s + "_pane";
        // best-effort: unknown stained id, use safe fallback
        return "minecraft:light_blue_stained_glass_pane";
    }

    /**
     * 将字符串 blockId 转为 BlockState（比如 "minecraft:stone_bricks"）
     * 与 TowerGenerator 使用相同的解析逻辑
     */
    private BlockState getState(ServerWorld world, String id) {
        if (id == null || id.isEmpty()) {
            return Blocks.OAK_PLANKS.getDefaultState();
        }

        try {
            // 解析 Identifier
            Identifier identifier;
            if (id.contains(":")) {
                identifier = Identifier.of(id);
            } else {
                identifier = Identifier.of("minecraft", id);
            }

            // 从注册表获取 Block（使用静态 Registries）
            Block block = Registries.BLOCK.get(identifier);
            return block.getDefaultState();

            // 如果找不到，尝试使用简单的字符串匹配作为回退
        } catch (Exception e) {
            // 如果解析失败，使用回退方案
            return resolveBlockFallback(id);
        }
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
        
        // 检测附近水体
        WaterDetector.WaterDetectionResult waterResult = WaterDetector.detectNearbyWater(
            world, origin, width, depth, 8, 8
        );
        
        if (!waterResult.hasWater()) {
            return; // 没有附近水体
        }
        
        // 计算建筑出入口位置
        BlockPos buildingExit = calculateDoorExitPosition(origin, width, depth, doorSide);
        
        // 寻找最佳接驳点
        WaterDetector.PierAnchor anchor = WaterDetector.findBestPierAnchor(
            world, buildingExit, doorSide, 8
        );
        
        if (anchor == null) {
            return; // 未找到合适的接驳点
        }
        
        // 生成码头
        List<PlannedBlock> pierBlocks = WaterfrontPierGenerator.generate(
            world, anchor, paletteId, 3  // 默认宽度3格
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
            case NORTH -> origin.add(cx, 0, -1);  // 门外1格
            case SOUTH -> origin.add(cx, 0, depth);
            case EAST -> origin.add(width, 0, cz);
            case WEST -> origin.add(-1, 0, cz);
            default -> origin.add(cx, 0, -1);
        };
    }

    /**
     * 回退方案：通过字符串匹配解析常用方块
     */
    private BlockState resolveBlockFallback(String material) {
        if (material == null) return Blocks.OAK_PLANKS.getDefaultState();
        
        String lower = material.toLowerCase();
        
        // 石头类
        if (lower.contains("stone_brick") || lower.contains("stonebrick")) {
            return Blocks.STONE_BRICKS.getDefaultState();
        }
        if (lower.contains("cobblestone")) {
            return Blocks.COBBLESTONE.getDefaultState();
        }
        if (lower.contains("stone") && !lower.contains("brick")) {
            return Blocks.STONE.getDefaultState();
        }
        
        // 砖类
        if (lower.contains("brick") && !lower.contains("stone")) {
            return Blocks.BRICKS.getDefaultState();
        }
        
        // 木头类
        if (lower.contains("dark_oak")) {
            return Blocks.DARK_OAK_PLANKS.getDefaultState();
        }
        if (lower.contains("oak_plank") || lower.contains("oak_wood")) {
            return Blocks.OAK_PLANKS.getDefaultState();
        }
        if (lower.contains("spruce")) {
            return Blocks.SPRUCE_PLANKS.getDefaultState();
        }
        if (lower.contains("birch")) {
            return Blocks.BIRCH_PLANKS.getDefaultState();
        }
        if (lower.contains("jungle")) {
            return Blocks.JUNGLE_PLANKS.getDefaultState();
        }
        if (lower.contains("acacia")) {
            return Blocks.ACACIA_PLANKS.getDefaultState();
        }
        if (lower.contains("wood") || lower.contains("plank") || lower.contains("oak")) {
            return Blocks.OAK_PLANKS.getDefaultState();
        }
        
        // 玻璃类
        if (lower.contains("glass_pane")) {
            return Blocks.GLASS_PANE.getDefaultState();
        }
        if (lower.contains("glass")) {
            return Blocks.GLASS.getDefaultState();
        }
        
        // 默认返回橡木木板
        return Blocks.OAK_PLANKS.getDefaultState();
    }

    private BlockState getStateOrDefault(ServerWorld world, String id, BlockState defaultState) {
        if (id == null || id.isBlank()) return defaultState;
        try {
            return getState(world, id);
        } catch (Throwable ignored) {
            return defaultState;
        }
    }
}

