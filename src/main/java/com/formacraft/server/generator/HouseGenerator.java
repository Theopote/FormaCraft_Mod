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
        StyleProfile profile = StyleProfileRegistry.forStyle(style);

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

        BlockState wall = getStateOrDefault(world, wallId,
                getStateOrDefault(world, genome != null && genome.palette != null ? genome.palette.wall : null, defaultWall(style)));
        BlockState floor = getStateOrDefault(world, floorId,
                getStateOrDefault(world, genome != null && genome.palette != null ? genome.palette.floor : null, defaultFloor(style)));
        BlockState window = getStateOrDefault(world, windowId,
                getStateOrDefault(world, genome != null && genome.palette != null ? genome.palette.window : null, defaultWindow(style)));
        BlockState roof = getStateOrDefault(world, roofId,
                getStateOrDefault(world, genome != null && genome.palette != null ? genome.palette.roof : null, defaultRoof(style)));

        // 装饰/细节材质（不要求模型显式提供，但能显著提升观感）
        BlockState trim = getStateOrDefault(world,
                genome != null && genome.palette != null ? genome.palette.trim : null,
                defaultTrim(style, wall));
        BlockState foundation = getStateOrDefault(world,
                genome != null && genome.palette != null ? genome.palette.foundation : null,
                defaultFoundation(style, wall));
        BlockState pillar = getStateOrDefault(world,
                genome != null && genome.palette != null ? genome.palette.pillar : null,
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

        // 2.1 地基（y=0 一圈）
        for (int x = 0; x < width; x++) {
            blocks.add(new PlannedBlock(origin.add(x, 0, 0), foundation));
            blocks.add(new PlannedBlock(origin.add(x, 0, depth - 1), foundation));
        }
        for (int z = 0; z < depth; z++) {
            blocks.add(new PlannedBlock(origin.add(0, 0, z), foundation));
            blocks.add(new PlannedBlock(origin.add(width - 1, 0, z), foundation));
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

                        // 门位置逻辑（根据 doorStyle）
                        if (hasDoor && z == 0) {
                            // 单门：width/2；双门：width/2 与 width/2-1；拱门：同双门但上方更高留空
                            boolean doorX = (doorStyle.equalsIgnoreCase("double") || doorStyle.equalsIgnoreCase("arched"))
                                    ? (x == width / 2 || x == width / 2 - 1)
                                    : (x == width / 2);
                            if (doorX) {
                                if (y == 0) {
                                    // 放门（下半）
                                    blocks.add(new PlannedBlock(pos, withDoorState(doorLower, DoubleBlockHalf.LOWER, x < width / 2)));
                                    continue;
                                }
                                if (y == 1) {
                                    // 放门（上半）
                                    blocks.add(new PlannedBlock(pos, withDoorState(doorLower, DoubleBlockHalf.UPPER, x < width / 2)));
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
                                // 避免在门附近开窗
                                boolean nearDoor = (z == 0) && (x == width / 2 || x == width / 2 - 1);
                                if (shouldPlaceWindow && !nearDoor) {
                                    blocks.add(new PlannedBlock(pos, windowBlock));
                                    // 窗套/窗框（v1）：
                                    // 只在窗带“上下边缘”放 trim，避免双层窗时互相覆盖。
                                    int localYForBand = y % floorHeight;
                                    boolean isExtraHighBand = (wallStrategy != BuildStrategy.SOLID_WALL) && height >= 9 && y == height - 3;
                                    boolean isTwoHighBand = (wallStrategy != BuildStrategy.SOLID_WALL)
                                            && !isExtraHighBand
                                            && (localYForBand == 1 || localYForBand == 2);
                                    boolean isBottomOfBand = isTwoHighBand ? (localYForBand == 1) : true;
                                    boolean isTopOfBand = isTwoHighBand ? (localYForBand == 2) : true;

                                    if (isBottomOfBand && y > 0) blocks.add(new PlannedBlock(origin.add(x, y - 1, z), trim));
                                    if (isTopOfBand && y + 1 < height) blocks.add(new PlannedBlock(origin.add(x, y + 1, z), trim));

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
                    blocks.add(new PlannedBlock(origin.add(x, y0, z), floor));
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
        // 4. 屋顶（根据 roofType；加入：檐口/楼梯屋顶/平顶女儿墙）
        // -------------------------------------
        if (hasRoof) {
            // 从 styleOptions 获取屋顶类型（向后兼容 extra）
            String actualRoofType = roofType;
            BuildStrategy roofStrategy = BuildStrategy.ROOF_SLOPE;
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
                        actualRoofType = (style == BuildingStyle.ASIAN) ? "hipped" : "gable";
                    }
                }
            }
            // 如果不是显式指定（StyleOptions/extra），则允许 StyleProfile 修正“平/坡”大方向
            if (!roofExplicit && !roofFromExtra) {
                if (roofStrategy == BuildStrategy.ROOF_FLAT) {
                    actualRoofType = "flat";
                } else if (roofStrategy == BuildStrategy.ROOF_SLOPE && "flat".equalsIgnoreCase(actualRoofType)) {
                    actualRoofType = (style == BuildingStyle.ASIAN) ? "hipped" : "gable";
                }
            }
            
            // 四坡/攒尖（hipped/pyramid）
            if ("hipped".equalsIgnoreCase(actualRoofType) || "pyramid".equalsIgnoreCase(actualRoofType)) {
                addHippedRoof(blocks, origin, width, depth, height, roof, roofStairs, roofSlab, trim);
            } else if ("gable".equalsIgnoreCase(actualRoofType) || roofStrategy == BuildStrategy.ROOF_SLOPE
                    || style == BuildingStyle.MEDIEVAL || style == BuildingStyle.RUSTIC) {
                // 双坡屋顶（沿 X 方向上升）；优先用 stairs/slab，视觉明显更好
                int roofHeight = Math.min(width / 2 + 1, 7);

                for (int i = 0; i < roofHeight; i++) {
                    int rightX = width - 1 - i;
                    if (i > rightX) break;

                    for (int z = -1; z <= depth; z++) {
                        // 左坡
                        blocks.add(new PlannedBlock(origin.add(i, height + i, z), withFacingIfPossible(roofStairs, Direction.EAST)));
                        // 右坡
                        blocks.add(new PlannedBlock(origin.add(rightX, height + i, z), withFacingIfPossible(roofStairs, Direction.WEST)));
                    }
                }

                // 屋脊：用 slab
                int ridgeY = height + roofHeight - 1;
                int midLeft = (width - 1) / 2;
                int midRight = width / 2;
                for (int z = -1; z <= depth; z++) {
                    blocks.add(new PlannedBlock(origin.add(midLeft, ridgeY + 1, z), roofSlab));
                    blocks.add(new PlannedBlock(origin.add(midRight, ridgeY + 1, z), roofSlab));
                }

            } else {
                // 平顶（现代/未来风格）：屋面 + 女儿墙边框
                for (int x = -1; x <= width; x++) {
                    for (int z = -1; z <= depth; z++) {
                        boolean edge = (x == -1 || x == width || z == -1 || z == depth);
                        if (edge) {
                            blocks.add(new PlannedBlock(origin.add(x, height, z), trim));
                            blocks.add(new PlannedBlock(origin.add(x, height + 1, z), trim));
                        } else {
                            blocks.add(new PlannedBlock(origin.add(x, height, z), roof));
                        }
                    }
                }

                // 现代风格：简单天窗（中间一条玻璃）
                if (style == BuildingStyle.MODERN || style == BuildingStyle.FUTURISTIC) {
                    int midZ = depth / 2;
                    for (int x = 2; x < width - 2; x++) {
                        blocks.add(new PlannedBlock(origin.add(x, height, midZ), Blocks.GLASS.getDefaultState()));
                    }
                }
            }

            // 檐口装饰（y=height-1 一圈 slab）
            for (int x = -1; x <= width; x++) {
                blocks.add(new PlannedBlock(origin.add(x, height - 1, -1), roofSlab));
                blocks.add(new PlannedBlock(origin.add(x, height - 1, depth), roofSlab));
            }
            for (int z = -1; z <= depth; z++) {
                blocks.add(new PlannedBlock(origin.add(-1, height - 1, z), roofSlab));
                blocks.add(new PlannedBlock(origin.add(width, height - 1, z), roofSlab));
            }

            // 额外一圈线脚（更有“屋檐层次”）：仅在“偏层次屋顶”的风格下启用
            if (height >= 4 && profile != null && profile.rules() != null && profile.rules().layeredRoof) {
                int y = height - 2;
                for (int x = -1; x <= width; x++) {
                    blocks.add(new PlannedBlock(origin.add(x, y, -1), trim));
                    blocks.add(new PlannedBlock(origin.add(x, y, depth), trim));
                }
                for (int z = -1; z <= depth; z++) {
                    blocks.add(new PlannedBlock(origin.add(-1, y, z), trim));
                    blocks.add(new PlannedBlock(origin.add(width, y, z), trim));
                }
            }

            // 中式：简化斗拱/雀替（檐下 1 格）+ 彩画点缀
            if (style == BuildingStyle.ASIAN) {
                addDougongAndPainting(blocks, origin, width, depth, height, trim);
            }
        }

        // -------------------------------------
        // 4.5 门口装饰（火把/墙灯）
        // -------------------------------------
        if (hasDoor) {
            int dx = width / 2;
            // 贴墙火把（更稳定，不需要额外支撑）
            BlockState wallTorch = Blocks.WALL_TORCH.getDefaultState();
            if (wallTorch.contains(Properties.HORIZONTAL_FACING)) {
                wallTorch = wallTorch.with(Properties.HORIZONTAL_FACING, Direction.SOUTH);
            }
            int y = 2;
            blocks.add(new PlannedBlock(origin.add(dx - 2, y, 0), wallTorch));
            if (dx + 2 <= width - 2) blocks.add(new PlannedBlock(origin.add(dx + 2, y, 0), wallTorch));
        }

        // -------------------------------------
        // 5. 返回结构对象
        // -------------------------------------
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

    private static BlockState withDoorState(BlockState door, DoubleBlockHalf half, boolean leftSide) {
        BlockState s = door;
        try {
            if (s.contains(Properties.HORIZONTAL_FACING)) s = s.with(Properties.HORIZONTAL_FACING, Direction.NORTH);
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

    private static void addHippedRoof(List<PlannedBlock> blocks, BlockPos o, int w, int d, int baseH,
                                      BlockState roofMain, BlockState roofStairs, BlockState roofSlab, BlockState trim) {
        addHippedRoof(blocks, o, w, d, baseH, roofMain, roofStairs, roofSlab, trim, true, false);
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
        return switch (style) {
            case MODERN -> Blocks.WHITE_CONCRETE.getDefaultState();
            // 明清官式默认红墙
            case ASIAN -> Blocks.RED_TERRACOTTA.getDefaultState();
            case FUTURISTIC -> Blocks.QUARTZ_BLOCK.getDefaultState();
            case RUSTIC -> Blocks.SPRUCE_PLANKS.getDefaultState();
            case MEDIEVAL -> Blocks.STONE_BRICKS.getDefaultState();
            case DEFAULT -> Blocks.OAK_PLANKS.getDefaultState();
        };
    }

    private static BlockState defaultFloor(BuildingStyle style) {
        return switch (style) {
            case MODERN, FUTURISTIC -> Blocks.SMOOTH_QUARTZ.getDefaultState();
            case ASIAN, MEDIEVAL, DEFAULT -> Blocks.OAK_PLANKS.getDefaultState();
            case RUSTIC -> Blocks.SPRUCE_PLANKS.getDefaultState();
        };
    }

    private static BlockState defaultWindow(BuildingStyle style) {
        return switch (style) {
            case MODERN, FUTURISTIC -> Blocks.GLASS.getDefaultState();
            default -> Blocks.GLASS_PANE.getDefaultState();
        };
    }

    private static BlockState defaultRoof(BuildingStyle style) {
        return switch (style) {
            case MODERN -> Blocks.BLACK_CONCRETE.getDefaultState();
            case FUTURISTIC -> Blocks.QUARTZ_BLOCK.getDefaultState();
            // 官式灰瓦（近似）：深板岩瓦
            case ASIAN -> Blocks.DEEPSLATE_TILES.getDefaultState();
            case MEDIEVAL, DEFAULT -> Blocks.DARK_OAK_PLANKS.getDefaultState();
            case RUSTIC -> Blocks.SPRUCE_PLANKS.getDefaultState();
        };
    }

    private static BlockState defaultTrim(BuildingStyle style, BlockState wall) {
        // 如果墙材本身是 stone bricks，trim 用石砖更一致
        if (wall != null && wall.getBlock() == Blocks.STONE_BRICKS) {
            return Blocks.CHISELED_STONE_BRICKS.getDefaultState();
        }
        return switch (style) {
            case MODERN -> Blocks.BLACK_CONCRETE.getDefaultState();
            case FUTURISTIC -> Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState();
            case ASIAN -> Blocks.RED_TERRACOTTA.getDefaultState();
            case RUSTIC, MEDIEVAL, DEFAULT -> Blocks.SPRUCE_LOG.getDefaultState();
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
        // Effective windowStyle:
        // StyleOptions（显式） > genome.params.windowStyle > heuristic default
        String windowStyle = (spec != null && spec.getStyleOptions() != null) ? spec.getStyleOptions().getWindowStyle() : null;
        if (windowStyle == null || windowStyle.isBlank()) {
            windowStyle = (genome != null && genome.params != null) ? genome.params.windowStyle : null;
        }
        if (windowStyle == null || windowStyle.isBlank()) {
            windowStyle = switch (style) {
                case ASIAN -> "fence";
                case MEDIEVAL -> "pane";
                case MODERN, FUTURISTIC -> "pane";
                case RUSTIC -> "pane";
                default -> "pane";
            };
        }
        String ws = windowStyle.trim().toLowerCase();

        switch (ws) {
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
            case "stained" -> {
                // stained glass pane: derive color from trim first (if it's stained glass)
                String tid = (profile != null && profile.palette() != null) ? profile.palette().trim : null;
                if ((tid == null || tid.isBlank()) && trim != null) {
                    tid = Registries.BLOCK.getId(trim.getBlock()).toString();
                }
                String paneId = deriveStainedPaneId(tid);
                return getStateOrDefault(world, paneId, Blocks.LIGHT_BLUE_STAINED_GLASS_PANE.getDefaultState());
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

