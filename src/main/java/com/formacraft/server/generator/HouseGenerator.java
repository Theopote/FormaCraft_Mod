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
import com.formacraft.server.terrain.TerrainFit;
import com.formacraft.server.terrain.TerrainPolicy;
import com.formacraft.server.terrain.TerrainPolicyResolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 房屋生成器
 * 支持矩形建筑、外墙、内墙、门、窗户、多层楼结构、地板、屋顶
 * 可扩展 features（如阳台、柱子、装饰）
 */
public class HouseGenerator implements StructureGenerator {


    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> blocks = new ArrayList<>();
        Set<BlockPos> fenceFramePositions = new HashSet<>();

        // 1. 初始化上下文
        HouseGenerationContext ctx = initializeContext(spec, origin, world);

        // 2. 清空内部空间
        clearInteriorSpace(blocks, ctx);

        // 3. 地坪平整（在生成建筑之前）
        BlockPos adjustedOrigin = flattenTerrain(blocks, ctx);

        // 4. 生成地基和转角柱
        generateFoundationAndPillars(blocks, adjustedOrigin, ctx);

        // 5. 生成墙体（包含门、窗、腰线等）
        generateWalls(blocks, fenceFramePositions, adjustedOrigin, ctx);

        // 6. 添加栅窗窗套
        addFenceFrames(blocks, fenceFramePositions, adjustedOrigin, ctx);

        // 7. 生成地板和天花
        generateFloorsAndCeilings(blocks, adjustedOrigin, ctx);

        // 8. 生成内部分区
        generatePartitions(blocks, adjustedOrigin, ctx);

        // 9. 生成屋顶
        generateRoof(blocks, adjustedOrigin, ctx);

        // 10. 生成门口照明
        generateLighting(blocks, adjustedOrigin, ctx);

        // 11. 生成装饰元素
        generateDecorations(blocks, adjustedOrigin, ctx);

        // 12. 生成临水码头（如果满足条件）
        generateWaterfrontPier(blocks, adjustedOrigin, ctx);

        String description = String.format("House (%s, %dx%dx%d, floors=%d)", 
                spec.getType(), ctx.width(), ctx.height(), ctx.depth(), ctx.floors());

        return new GeneratedStructure(
                null, // owner 将在 BuildExecutionService 中设置
                adjustedOrigin,
                description,
                blocks
        );
    }

    /**
     * 初始化生成上下文
     */
    private HouseGenerationContext initializeContext(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        // 获取参数
        int width = Math.max(6, spec.getFootprint() != null ? spec.getFootprint().getWidth() : 8);
        int depth = Math.max(6, spec.getFootprint() != null ? spec.getFootprint().getDepth() : 6);
        int height = Math.max(4, spec.getHeight());
        int floors = Math.max(1, spec.getFloors());

        BuildingStyle style = (spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.DEFAULT;

        // 风格"基因"（数据驱动）
        StyleGenome genome = StyleGenomeRegistry.forStyle(style);
        StyleProfile profile = StyleProfileRegistry.resolve(spec);

        // 解析材质
        HouseMaterialResolver.MaterialSet materials = HouseMaterialResolver.resolveMaterials(world, spec, style, genome, profile);

        // 获取特性
        boolean hasWindows = spec.getFeatures() != null && spec.getFeatures().hasWindows();
        boolean hasDoor = spec.getFeatures() != null && spec.getFeatures().hasDoor();
        boolean hasRoof = spec.getFeatures() != null && spec.getFeatures().hasRoof();
        
        // 获取风格选项
        String doorStyle = HouseStyleOptionsResolver.resolveDoorStyle(spec, genome);
        String roofType = HouseStyleOptionsResolver.resolveRoofType(spec, genome, profile, style);
        double windowRatio = HouseStyleOptionsResolver.resolveWindowRatio(spec, genome, profile, style);
        String wallPattern = HouseStyleOptionsResolver.resolveWallPattern(spec, genome);

        // 中式官式调整
        if (style == BuildingStyle.ASIAN) {
            if (roofType == null || roofType.isBlank() || "flat".equalsIgnoreCase(roofType)) {
                roofType = "hipped";
            }
            if (windowRatio < 0.15) windowRatio = 0.18;
        }

        // 墙体策略
        BuildStrategy wallStrategy = BuildStrategy.WINDOWED_WALL;
        if (profile != null) {
            wallStrategy = profile.resolve("WALL", java.util.Collections.emptySet());
        }
        if (hasWindows && wallStrategy == BuildStrategy.SOLID_WALL) {
            windowRatio = Math.min(windowRatio, 0.22);
        }

        // 计算楼层高度
        int floorHeight = calculateFloorHeight(height, floors, profile);

        // 获取调色板ID
        String paletteId = HouseStyleOptionsResolver.resolvePaletteId(spec, profile);

        // Door side 和 Layout
        Direction doorSide = HouseGeneratorUtils.resolveDoorSide(spec);
        HouseLayoutGenerator.LayoutInfo layoutInfo = HouseLayoutGenerator.resolveLayout(spec, width, depth);

        return new HouseGenerationContext(
                spec, origin, world, width, depth, height, floors,
                style, genome, profile, materials,
                hasWindows, hasDoor, hasRoof,
                doorStyle, roofType, windowRatio, wallPattern,
                wallStrategy, floorHeight, paletteId, doorSide, layoutInfo
        );
    }

    /**
     * 计算楼层高度
     */
    private int calculateFloorHeight(int height, int floors, StyleProfile profile) {
        int baseFloorHeight = Math.max(3, height / floors);
        int maxFloorHeight = (floors > 1) ? Math.max(3, (height - 1) / (floors - 1)) : Math.max(3, height);
        int floorHeight = Math.min(baseFloorHeight, maxFloorHeight);
        if (profile != null && profile.rules() != null && profile.rules().floorHeight > 0) {
            int pref = profile.rules().floorHeight;
            floorHeight = Math.max(3, Math.min(pref, maxFloorHeight));
        }
        return floorHeight;
    }

    /**
     * 应用调色板到屋顶材质（返回新数组，避免修改原始材质）
     */
    private BlockState[] applyPaletteToRoof(ServerWorld world, BlockPos origin, 
                                             HouseMaterialResolver.MaterialSet materials, 
                                             String paletteId, BuildingSpec spec) {
        BlockState roofStairs = materials.roofStairs();
        BlockState roofSlab = materials.roofSlab();
        
        if (paletteId != null && !paletteId.isBlank()) {
            String roofIdFromSpec = spec.getMaterials() != null ? spec.getMaterials().getRoof() : null;
            if (roofIdFromSpec == null || roofIdFromSpec.isBlank()) {
                roofStairs = PaletteResolver.pick(world, paletteId, "ROOF_SLOPE", origin, 0xA501001L, roofStairs);
                roofStairs = PaletteResolver.pick(world, paletteId, "ROOF_TILE", origin, 0xA501002L, roofStairs);
                roofSlab = PaletteResolver.pick(world, paletteId, "FLOOR_SLAB", origin, 0xA501003L, roofSlab);
                roofSlab = PaletteResolver.pick(world, paletteId, "ROOF_TILE", origin, 0xA501004L, roofSlab);
            }
        }
        
        return new BlockState[]{roofStairs, roofSlab};
    }

    /**
     * 清空内部空间
     */
    private void clearInteriorSpace(List<PlannedBlock> blocks, HouseGenerationContext ctx) {
        for (int x = -1; x <= ctx.width() + 1; x++) {
            for (int z = -1; z <= ctx.depth() + 1; z++) {
                for (int y = 0; y <= ctx.height() + 6; y++) {
                    blocks.add(new PlannedBlock(ctx.origin().add(x, y, z), Blocks.AIR.getDefaultState()));
                }
            }
        }
    }

    /**
     * 地坪平整（确保单体建筑的每层地板都是平的）
     */
    private BlockPos flattenTerrain(List<PlannedBlock> blocks, HouseGenerationContext ctx) {
        ctx.origin().getY();
        int baseY;
        java.util.Map<String, Object> extra = ctx.spec().getExtra() != null ? ctx.spec().getExtra() : java.util.Collections.emptyMap();
        TerrainPolicy terrainPolicy = TerrainPolicyResolver.resolve(extra);
        
        // 获取平整参数
        int padDepth = 4;
        int clearHeight = 8;
        Object padDepthObj = extra.get("terrainPadDepth");
        Object clearHeightObj = extra.get("terrainClearHeight");
        if (padDepthObj instanceof Number) padDepth = ((Number) padDepthObj).intValue();
        if (clearHeightObj instanceof Number) clearHeight = ((Number) clearHeightObj).intValue();
        
        // 如果地形策略是 ADAPTIVE 或 FLATTEN_AREA，进行地坪平整
        if (terrainPolicy == TerrainPolicy.ADAPTIVE || terrainPolicy == TerrainPolicy.FLATTEN_AREA) {
            // 调整 origin 高度
            BlockPos adjustedOrigin = TerrainFit.snapOrigin(ctx.world(), ctx.origin(), ctx.width(), ctx.depth());
            adjustedOrigin.getY();

            // 计算目标高度
            int targetY = TerrainFit.averageFootprintHeight(ctx.world(), adjustedOrigin, ctx.width(), ctx.depth()) + 1;
            baseY = targetY;
            
            // 分析地形
            TerrainFit.FootprintAnalysis analysis = TerrainFit.analyze(ctx.world(), adjustedOrigin, ctx.width(), ctx.depth());
            
            // 根据地形策略选择平整方式
            if (terrainPolicy == TerrainPolicy.FLATTEN_AREA) {
                List<PlannedBlock> pad = TerrainFit.balancedPad(
                        ctx.world(), adjustedOrigin, ctx.width(), ctx.depth(), targetY, ctx.materials().foundation(),
                        padDepth, clearHeight, true, true);
                blocks.addAll(pad);
            } else if (analysis.range() > 1) {
                int adaptivePadDepth = Math.min(padDepth, 4);
                int adaptiveClearHeight = Math.min(clearHeight, 8);
                List<PlannedBlock> pad = TerrainFit.adaptivePad(
                        ctx.world(), adjustedOrigin, ctx.width(), ctx.depth(), targetY, ctx.materials().foundation(),
                        adaptivePadDepth, adaptiveClearHeight, true, true);
                blocks.addAll(pad);
            }
            
            // 返回调整后的 origin
            return new BlockPos(ctx.origin().getX(), baseY, ctx.origin().getZ());
        }
        
        return ctx.origin();
    }

    /**
     * 生成地基和转角柱
     */
    private void generateFoundationAndPillars(List<PlannedBlock> blocks, BlockPos origin, HouseGenerationContext ctx) {
        BlockState foundation = ctx.materials().foundation();
        BlockState pillar = ctx.materials().pillar();
        String paletteId = ctx.paletteId();
        ServerWorld world = ctx.world();
        
        // 地基（y=0 一圈）
        for (int x = 0; x < ctx.width(); x++) {
            BlockPos p1 = origin.add(x, 0, 0);
            BlockPos p2 = origin.add(x, 0, ctx.depth() - 1);
            BlockState f1 = foundation;
            BlockState f2 = foundation;
            if (paletteId != null && !paletteId.isBlank()) {
                f1 = PaletteResolver.pick(world, paletteId, "WALL_FOUNDATION", p1, (x * 31L) ^ 0xBEEF, foundation);
                f2 = PaletteResolver.pick(world, paletteId, "WALL_FOUNDATION", p2, (x * 31L) ^ 0xCAFE, foundation);
            }
            blocks.add(new PlannedBlock(p1, f1));
            blocks.add(new PlannedBlock(p2, f2));
        }
        for (int z = 0; z < ctx.depth(); z++) {
            BlockPos p1 = origin.add(0, 0, z);
            BlockPos p2 = origin.add(ctx.width() - 1, 0, z);
            BlockState f1 = foundation;
            BlockState f2 = foundation;
            if (paletteId != null && !paletteId.isBlank()) {
                f1 = PaletteResolver.pick(world, paletteId, "WALL_FOUNDATION", p1, (z * 31L) ^ 0xD00D, foundation);
                f2 = PaletteResolver.pick(world, paletteId, "WALL_FOUNDATION", p2, (z * 31L) ^ 0xF00D, foundation);
            }
            blocks.add(new PlannedBlock(p1, f1));
            blocks.add(new PlannedBlock(p2, f2));
        }

        // 转角柱（四角贯穿到檐口）
        for (int y = 0; y < ctx.height(); y++) {
            blocks.add(new PlannedBlock(origin.add(0, y, 0), pillar));
            blocks.add(new PlannedBlock(origin.add(ctx.width() - 1, y, 0), pillar));
            blocks.add(new PlannedBlock(origin.add(0, y, ctx.depth() - 1), pillar));
            blocks.add(new PlannedBlock(origin.add(ctx.width() - 1, y, ctx.depth() - 1), pillar));
        }
    }

    /**
     * 生成墙体（包含门、窗、腰线等）
     */
    private void generateWalls(List<PlannedBlock> blocks, Set<BlockPos> fenceFramePositions, 
                               BlockPos origin, HouseGenerationContext ctx) {
        for (int y = 0; y < ctx.height(); y++) {
            boolean isBandY = (y > 0 && (y % ctx.floorHeight() == 0)) || (y == ctx.height() - 1);
            for (int x = 0; x < ctx.width(); x++) {
                for (int z = 0; z < ctx.depth(); z++) {
                    boolean isEdgeX = (x == 0 || x == ctx.width() - 1);
                    boolean isEdgeZ = (z == 0 || z == ctx.depth() - 1);

                    if (isEdgeX || isEdgeZ) {
                        BlockPos pos = origin.add(x, y, z);

                        // 处理门
                        if (ctx.hasDoor() && processDoor(blocks, pos, x, y, z, origin, ctx)) {
                            continue;
                        }

                        // 处理窗户
                        if (ctx.hasWindows() && processWindow(blocks, fenceFramePositions, pos, x, y, z, origin, ctx)) {
                            continue;
                        }

                        // 腰线/檐口
                        if (isBandY) {
                            blocks.add(new PlannedBlock(pos, ctx.materials().trim()));
                            continue;
                        }

                        // 普通墙体
                        processWallCell(blocks, pos, x, y, z, origin, ctx);
                    }
                }
            }
        }
    }

    /**
     * 处理门
     */
    private boolean processDoor(List<PlannedBlock> blocks, BlockPos pos, int x, int y, int z, 
                                BlockPos origin, HouseGenerationContext ctx) {
        if (!HouseGeneratorUtils.isDoorEdge(ctx.doorSide(), x, z, ctx.width(), ctx.depth())) {
            return false;
        }

        boolean onNorthSouth = (ctx.doorSide() == Direction.NORTH || ctx.doorSide() == Direction.SOUTH);
        int center = onNorthSouth ? (ctx.width() / 2) : (ctx.depth() / 2);
        boolean doorAxis = (ctx.doorStyle().equalsIgnoreCase("double") || ctx.doorStyle().equalsIgnoreCase("arched"))
                ? ((onNorthSouth ? x : z) == center || (onNorthSouth ? x : z) == center - 1)
                : ((onNorthSouth ? x : z) == center);
        
        if (!doorAxis) {
            return false;
        }

        boolean leftSide = ((onNorthSouth ? x : z) < center);
        if (y == 0) {
            blocks.add(new PlannedBlock(pos, HouseGeneratorUtils.withDoorState(
                    ctx.materials().door(), DoubleBlockHalf.LOWER, leftSide, ctx.doorSide())));
            return true;
        }
        if (y == 1) {
            blocks.add(new PlannedBlock(pos, HouseGeneratorUtils.withDoorState(
                    ctx.materials().door(), DoubleBlockHalf.UPPER, leftSide, ctx.doorSide())));
            return true;
        }
        if (ctx.doorStyle().equalsIgnoreCase("arched") && y == 2) {
            blocks.add(new PlannedBlock(pos, Blocks.AIR.getDefaultState()));
            return true;
        }
        if (ctx.doorStyle().equalsIgnoreCase("arched") && y == 3) {
            blocks.add(new PlannedBlock(pos, ctx.materials().trim()));
            return true;
        }

        return false;
    }

    /**
     * 处理窗户
     */
    private boolean processWindow(List<PlannedBlock> blocks, Set<BlockPos> fenceFramePositions, 
                                  BlockPos pos, int x, int y, int z, BlockPos origin, HouseGenerationContext ctx) {
        int localY = y % ctx.floorHeight();
        boolean inWindowBand = (localY == 1 || localY == 2);
        if (ctx.wallStrategy() == BuildStrategy.SOLID_WALL) {
            inWindowBand = (localY == 1);
        }
        if (ctx.wallStrategy() != BuildStrategy.SOLID_WALL && !inWindowBand && ctx.height() >= 9 && y == ctx.height() - 3) {
            inWindowBand = true;
        }

        if (!inWindowBand) {
            return false;
        }

        boolean preferSymmetry = (ctx.profile() != null && ctx.profile().rules() != null && ctx.profile().rules().preferSymmetry);
        boolean shouldPlaceWindow = HouseGeneratorUtils.isShouldPlaceWindow(
                ctx.wallStrategy(), ctx.windowRatio(), preferSymmetry, x, z, ctx.width(), ctx.depth());
        boolean nearDoor = HouseGeneratorUtils.isNearDoor(ctx.doorSide(), x, z, ctx.width(), ctx.depth());
        
        if (!shouldPlaceWindow || nearDoor) {
            return false;
        }

        blocks.add(new PlannedBlock(pos, ctx.materials().windowBlock()));

        // Gothic mullions
        if (ctx.profile() != null && ctx.profile().details() != null && ctx.profile().details().mullions) {
            HouseDecorator.addMullionBehindWindow(blocks, origin, x, y, z, ctx.width(), ctx.depth(), ctx.doorSide());
        }

        // 窗套/窗框
        int localYForBand = y % ctx.floorHeight();
        boolean isExtraHighBand = (ctx.wallStrategy() != BuildStrategy.SOLID_WALL) && ctx.height() >= 9 && y == ctx.height() - 3;
        boolean isTwoHighBand = (ctx.wallStrategy() != BuildStrategy.SOLID_WALL)
                && !isExtraHighBand
                && (localYForBand == 1 || localYForBand == 2);
        boolean isBottomOfBand = !isTwoHighBand || (localYForBand == 1);
        boolean isTopOfBand = !isTwoHighBand || (localYForBand == 2);

        if (isBottomOfBand && y > 0) {
            blocks.add(new PlannedBlock(origin.add(x, y - 1, z), ctx.materials().trim()));
        }
        if (isTopOfBand && y + 1 < ctx.height()) {
            boolean pointed = (ctx.profile() != null && ctx.profile().details() != null && ctx.profile().details().pointedArches);
            if (pointed && HouseGeneratorUtils.isPointedArchWindowSafe(
                    ctx.wallStrategy(), ctx.windowRatio(), preferSymmetry, ctx.doorSide(), x, z, ctx.width(), ctx.depth())) {
                HouseDecorator.addPointedWindowFrame(blocks, origin, x, y + 1, z, ctx.width(), ctx.depth(), 
                        ctx.materials().trim(), ctx.materials().roofStairs());
            } else {
                blocks.add(new PlannedBlock(origin.add(x, y + 1, z), ctx.materials().trim()));
            }
        }

        // fence 栅窗窗套
        if (HouseGeneratorUtils.isFenceLikeWindow(ctx.materials().windowBlock())) {
            boolean isEdgeZ = (z == 0 || z == ctx.depth() - 1);
            if (isEdgeZ) {
                HouseGeneratorUtils.tryCollectFenceFrame(fenceFramePositions, origin, ctx.wallStrategy(), 
                        ctx.windowRatio(), preferSymmetry, x - 1, y, z, ctx.width(), ctx.depth());
                HouseGeneratorUtils.tryCollectFenceFrame(fenceFramePositions, origin, ctx.wallStrategy(), 
                        ctx.windowRatio(), preferSymmetry, x + 1, y, z, ctx.width(), ctx.depth());
            } else {
                HouseGeneratorUtils.tryCollectFenceFrame(fenceFramePositions, origin, ctx.wallStrategy(), 
                        ctx.windowRatio(), preferSymmetry, x, y, z - 1, ctx.width(), ctx.depth());
                HouseGeneratorUtils.tryCollectFenceFrame(fenceFramePositions, origin, ctx.wallStrategy(), 
                        ctx.windowRatio(), preferSymmetry, x, y, z + 1, ctx.width(), ctx.depth());
            }
        }

        return true;
    }

    /**
     * 处理墙体单元格
     */
    private void processWallCell(List<PlannedBlock> blocks, BlockPos pos, int x, int y, int z, 
                                 BlockPos origin, HouseGenerationContext ctx) {
        BlockState wallToUse = HouseGeneratorUtils.applyWallPattern(
                ctx.materials().wall(), ctx.materials().trim(), ctx.materials().foundation(), 
                ctx.wallPattern(), y, ctx.height());

        // Palette override
        if (ctx.paletteId() != null && !ctx.paletteId().isBlank() && wallToUse == ctx.materials().wall()) {
            long salt = (x * 1315423911L) ^ (z * 2654435761L) ^ (y * 97531L);
            wallToUse = PaletteResolver.pick(ctx.world(), ctx.paletteId(), "WALL_BASE", pos, salt, wallToUse);
        }

        // Facade composition hint
        String facadeProfile = null;
        try {
            if (ctx.profile() != null && ctx.profile().details() != null) {
                facadeProfile = ctx.profile().details().facadeProfile;
            }
        } catch (Throwable ignored) {}
        if (facadeProfile != null && !facadeProfile.isBlank()) {
            wallToUse = HouseGeneratorUtils.applyFacadeProfileToWallCell(
                    wallToUse, ctx.materials().wall(), ctx.materials().trim(), ctx.materials().foundation(),
                    facadeProfile, ctx.doorSide(), x, y, z, ctx.width(), ctx.depth(),
                    ctx.hasDoor(), ctx.floorHeight()
            );
        }

        blocks.add(new PlannedBlock(pos, wallToUse));
    }

    /**
     * 添加栅窗窗套
     */
    private void addFenceFrames(List<PlannedBlock> blocks, Set<BlockPos> fenceFramePositions, 
                                BlockPos origin, HouseGenerationContext ctx) {
        if (!fenceFramePositions.isEmpty()) {
            for (BlockPos fp : fenceFramePositions) {
                blocks.add(new PlannedBlock(fp, ctx.materials().trim()));
            }
        }
    }

    /**
     * 生成地板和天花
     */
    private void generateFloorsAndCeilings(List<PlannedBlock> blocks, BlockPos origin, HouseGenerationContext ctx) {
        int baseY = origin.getY();
        HouseLayoutGenerator.LayoutCourtyard courtyard = ctx.layoutInfo().courtyard();

        for (int f = 0; f < ctx.floors(); f++) {
            int floorY = baseY + f * ctx.floorHeight();

            // 地板
            for (int x = 1; x < ctx.width() - 1; x++) {
                for (int z = 1; z < ctx.depth() - 1; z++) {
                    if (courtyard.enabled() && courtyard.containsInterior(x, z)) continue;
                    BlockPos fp = new BlockPos(origin.getX() + x, floorY, origin.getZ() + z);
                    BlockState fl = ctx.materials().floor();
                    if (ctx.paletteId() != null && !ctx.paletteId().isBlank()) {
                        long salt = (x * 31L) ^ (z * 17L) ^ (floorY * 13L);
                        fl = PaletteResolver.pick(ctx.world(), ctx.paletteId(), "FLOORING", fp, salt, fl);
                    }
                    blocks.add(new PlannedBlock(fp, fl));
                }
            }

            // 天花（除了顶层）
            if (f < ctx.floors() - 1) {
                int cy = floorY + ctx.floorHeight();
                if (cy > 0 && cy < ctx.height()) {
                    for (int x = 1; x < ctx.width() - 1; x++) {
                        blocks.add(new PlannedBlock(origin.add(x, cy, 1), ctx.materials().trim()));
                        blocks.add(new PlannedBlock(origin.add(x, cy, ctx.depth() - 2), ctx.materials().trim()));
                    }
                    for (int z = 1; z < ctx.depth() - 1; z++) {
                        blocks.add(new PlannedBlock(origin.add(1, cy, z), ctx.materials().trim()));
                        blocks.add(new PlannedBlock(origin.add(ctx.width() - 2, cy, z), ctx.materials().trim()));
                    }
                }
            }
        }
    }

    /**
     * 生成内部分区
     */
    private void generatePartitions(List<PlannedBlock> blocks, BlockPos origin, HouseGenerationContext ctx) {
        HouseLayoutGenerator.generatePartitions(blocks, origin, ctx.width(), ctx.depth(), ctx.height(), 
                ctx.floors(), ctx.floorHeight(), ctx.materials().wall(), ctx.materials().trim(), 
                ctx.doorSide(), ctx.layoutInfo());
    }

    /**
     * 生成屋顶
     */
    private void generateRoof(List<PlannedBlock> blocks, BlockPos origin, HouseGenerationContext ctx) {
        if (!ctx.hasRoof()) {
            return;
        }

        String actualRoofType = resolveActualRoofType(ctx);
        BlockState[] roofMaterials = applyPaletteToRoof(ctx.world(), origin, ctx.materials(), ctx.paletteId(), ctx.spec());
        
        // 使用 HouseRoofGenerator 生成屋顶
        HouseRoofGenerator.generateRoof(blocks, origin, ctx.width(), ctx.depth(), ctx.height(), actualRoofType,
                ctx.materials().roof(), roofMaterials[0], roofMaterials[1], ctx.materials().trim(), 
                ctx.style(), ctx.profile(), ctx.doorSide(), ctx.spec(), ctx.paletteId());
        
        // 徽派马头墙
        BuildStrategy roofStrategy = (ctx.profile() != null) 
                ? ctx.profile().resolve("ROOF", java.util.Collections.emptySet())
                : ("flat".equalsIgnoreCase(actualRoofType)) ? BuildStrategy.ROOF_FLAT : BuildStrategy.ROOF_SLOPE;
        
        if (HorseHeadWallGenerator.isHuizhouStyle(ctx.spec(), ctx.paletteId()) 
                && ("gable".equalsIgnoreCase(actualRoofType) || roofStrategy == BuildStrategy.ROOF_SLOPE
                || ctx.style() == BuildingStyle.MEDIEVAL || ctx.style() == BuildingStyle.RUSTIC)) {
            int roofHeight = Math.min(ctx.width() / 2 + 1, 7);
            HorseHeadWallGenerator.generate(blocks, origin, ctx.width(), ctx.depth(), ctx.height(), roofHeight, 
                    ctx.materials().wall(), ctx.materials().trim(), roofMaterials[1], ctx.doorSide());
        }

        // 庭院开口
        HouseLayoutGenerator.LayoutCourtyard courtyard = ctx.layoutInfo().courtyard();
        if (courtyard.enabled()) {
            int yMax = ctx.height() + 14;
            for (int y = 1; y <= yMax; y++) {
                for (int x = courtyard.x0(); x <= courtyard.x1(); x++) {
                    for (int z = courtyard.z0(); z <= courtyard.z1(); z++) {
                        blocks.add(new PlannedBlock(origin.add(x, y, z), Blocks.AIR.getDefaultState()));
                    }
                }
            }
        }
    }

    /**
     * 解析实际的屋顶类型
     */
    private String resolveActualRoofType(HouseGenerationContext ctx) {
        String actualRoofType = ctx.roofType();
        BuildStrategy roofStrategy = (ctx.profile() != null) 
                ? ctx.profile().resolve("ROOF", java.util.Collections.emptySet())
                : ("flat".equalsIgnoreCase(actualRoofType)) ? BuildStrategy.ROOF_FLAT : BuildStrategy.ROOF_SLOPE;

        boolean roofExplicit = (ctx.spec().getStyleOptions() != null && ctx.spec().getStyleOptions().getRoofType() != null);
        boolean roofFromExtra = false;
        if (actualRoofType == null || actualRoofType.isEmpty()) {
            if (ctx.spec().getExtra() != null && ctx.spec().getExtra().containsKey("roofType")) {
                actualRoofType = String.valueOf(ctx.spec().getExtra().get("roofType"));
                roofFromExtra = true;
            } else {
                if (roofStrategy == BuildStrategy.ROOF_FLAT) {
                    actualRoofType = "flat";
                } else {
                    if (ctx.profile() != null && ctx.profile().rules() != null 
                            && ctx.profile().rules().roofTypeHint != null && !ctx.profile().rules().roofTypeHint.isBlank()) {
                        actualRoofType = ctx.profile().rules().roofTypeHint;
                    } else {
                        actualRoofType = (ctx.style() == BuildingStyle.ASIAN) ? "hipped" : "gable";
                    }
                }
            }
        }

        // normalize aliases
        if ("hip".equalsIgnoreCase(actualRoofType)) actualRoofType = "hipped";
        if (actualRoofType != null) {
            String rt = actualRoofType.trim().toLowerCase(java.util.Locale.ROOT);
            if (rt.equals("xie_shan") || rt.equals("xieshan") || rt.equals("xie-shan") || rt.equals("xie shan") || rt.contains("xie")) {
                actualRoofType = "xie_shan";
            }
        }

        if (!roofExplicit && !roofFromExtra) {
            if (roofStrategy == BuildStrategy.ROOF_FLAT) {
                actualRoofType = "flat";
            } else if (roofStrategy == BuildStrategy.ROOF_SLOPE && "flat".equalsIgnoreCase(actualRoofType)) {
                actualRoofType = (ctx.style() == BuildingStyle.ASIAN) ? "hipped" : "gable";
            }
        }

        return actualRoofType;
    }

    /**
     * 生成门口照明
     */
    private void generateLighting(List<PlannedBlock> blocks, BlockPos origin, HouseGenerationContext ctx) {
        if (!ctx.hasDoor()) {
            return;
        }

        String lightingMode = "door";
        String lightingType = "torch";
        int lightingSpacing = 6;
        boolean banner = false;
        String bannerColor = "red";

        if (ctx.spec().getExtra() != null) {
            Object lm = ctx.spec().getExtra().get("lighting");
            Object lt = ctx.spec().getExtra().get("lightingType");
            Object ls = ctx.spec().getExtra().get("lightingSpacing");
            Object bn = ctx.spec().getExtra().get("banner");
            Object bc = ctx.spec().getExtra().get("bannerColor");
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

        HouseDecorator.generateLighting(blocks, origin, ctx.width(), ctx.depth(), ctx.materials().foundation(), 
                ctx.doorSide(), lightingMode, lightingType, lightingSpacing, banner ? bannerColor : null, 
                ctx.paletteId(), ctx.world());
    }

    /**
     * 生成装饰元素
     */
    private void generateDecorations(List<PlannedBlock> blocks, BlockPos origin, HouseGenerationContext ctx) {
        try {
            if (ctx.profile() != null && ctx.profile().details() != null) {
                HouseDecorator.decorate(blocks, origin, ctx.world(), ctx.spec(), ctx.width(), ctx.depth(), ctx.height(),
                        ctx.materials().wall(), ctx.materials().trim(), ctx.materials().foundation(), ctx.materials().pillar(),
                        ctx.materials().roof(), ctx.materials().roofStairs(), ctx.materials().roofSlab(), 
                        ctx.materials().windowBlock(), ctx.paletteId(), ctx.profile().details(), ctx.layoutInfo());
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 生成临水码头（如果满足条件）
     */
    private void generateWaterfrontPier(List<PlannedBlock> blocks, BlockPos origin, HouseGenerationContext ctx) {
        try {
            generateWaterfrontPierIfNeeded(blocks, ctx.world(), ctx.spec(), origin, ctx.width(), ctx.depth(), 
                    ctx.height(), ctx.doorSide(), ctx.paletteId());
        } catch (Exception e) {
            // 静默失败，不影响主建筑生成
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
}
