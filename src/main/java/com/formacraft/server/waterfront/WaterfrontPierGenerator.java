package com.formacraft.server.waterfront;

import com.formacraft.server.build.PlannedBlock;
import com.formacraft.server.material.PaletteResolver;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * 临水码头生成器
 * 
 * 根据建筑出入口和水体位置，自动生成码头结构。
 * 支持三种码头形态：
 * - 平铺式驳岸（高差 ≤ 1）
 * - 阶梯式码头（高差 2-4）
 * - 挑空木栈桥（高差 > 4）
 */
public class WaterfrontPierGenerator {
    
    /**
     * 生成码头
     * 
     * @param world 世界对象
     * @param anchor 码头锚点信息
     * @param paletteId 调色板ID（用于材质变量替换）
     * @param pierWidth 码头宽度（默认3格）
     * @return 生成的方块列表
     */
    public static List<PlannedBlock> generate(
            ServerWorld world, WaterDetector.PierAnchor anchor, String paletteId, int pierWidth) {
        
        if (anchor == null || world == null) {
            return List.of();
        }
        
        List<PlannedBlock> blocks = new ArrayList<>();
        
        // 确定码头类型
        int heightDiff = anchor.heightDiff;
        PierType type = determinePierType(heightDiff);
        
        // 获取材质
        PierMaterials materials = resolveMaterials(world, paletteId);
        
        // 根据类型生成码头
        switch (type) {
            case FLAT -> generateFlatPier(blocks, world, anchor, materials, pierWidth);
            case STEPPED -> generateSteppedPier(blocks, world, anchor, materials, pierWidth, heightDiff);
            case ELEVATED -> generateElevatedPier(blocks, world, anchor, materials, pierWidth, heightDiff);
        }
        
        // 添加视觉细节
        addVisualDetails(blocks, world, anchor, materials, paletteId);
        
        return blocks;
    }
    
    /**
     * 确定码头类型
     */
    private static PierType determinePierType(int heightDiff) {
        if (heightDiff <= 1) {
            return PierType.FLAT;
        } else if (heightDiff <= 4) {
            return PierType.STEPPED;
        } else {
            return PierType.ELEVATED;
        }
    }
    
    /**
     * 解析材质
     */
    private static PierMaterials resolveMaterials(ServerWorld world, String paletteId) {
        PierMaterials m = new PierMaterials();
        
        // 使用调色板解析材质（如果可用）
        if (paletteId != null && !paletteId.isBlank()) {
            BlockPos dummy = new BlockPos(0, 64, 0);
            m.pierStep = PaletteResolver.pick(world, paletteId, "PIER_STEP", dummy, 0L, m.pierStep);
            m.pierEdge = PaletteResolver.pick(world, paletteId, "PIER_EDGE", dummy, 0L, m.pierEdge);
            m.pierDeck = PaletteResolver.pick(world, paletteId, "PIER_DECK", dummy, 0L, m.pierDeck);
            m.mooringPost = PaletteResolver.pick(world, paletteId, "MOORING_POST", dummy, 0L, m.mooringPost);
            m.revetment = PaletteResolver.pick(world, paletteId, "REVETMENT", dummy, 0L, m.revetment);
        }
        
        return m;
    }
    
    /**
     * 生成平铺式驳岸
     */
    private static void generateFlatPier(List<PlannedBlock> blocks, ServerWorld world,
                                        WaterDetector.PierAnchor anchor, PierMaterials materials, int width) {
        BlockPos start = anchor.landPos;
        BlockPos end = anchor.waterPos;
        Direction facing = getDirection(start, end);
        
        // 计算路径
        List<BlockPos> path = calculatePath(start, end, facing);
        
        // 生成码头平台（与地面齐平）
        for (BlockPos center : path) {
            generatePierDeck(blocks, center, materials.pierDeck, width, facing);
        }
        
        // 延伸进水里（深入水面以下1层）
        BlockPos waterEnd = end.offset(facing, 2);
        for (int i = 0; i < 2; i++) {
            BlockPos pos = end.offset(facing, i);
            generatePierDeck(blocks, pos.down(), materials.pierDeck, width, facing); // 水下1层
            generatePierDeck(blocks, pos, materials.pierDeck, width, facing); // 水面
        }
    }
    
    /**
     * 生成阶梯式码头
     */
    private static void generateSteppedPier(List<PlannedBlock> blocks, ServerWorld world,
                                           WaterDetector.PierAnchor anchor, PierMaterials materials,
                                           int width, int heightDiff) {
        BlockPos start = anchor.landPos;
        BlockPos end = anchor.waterPos;
        Direction facing = getDirection(start, end);
        
        // 计算路径
        List<BlockPos> path = calculatePath(start, end, facing);
        
        // 计算台阶数量（每个台阶高度差1格）
        int steps = Math.min(heightDiff, path.size());
        int stepHeight = heightDiff / Math.max(1, steps);
        
        // 生成阶梯
        int currentY = start.getY();
        for (int i = 0; i < path.size() && i < steps; i++) {
            BlockPos center = path.get(i);
            BlockPos stepPos = new BlockPos(center.getX(), currentY, center.getZ());
            
            // 生成台阶（使用楼梯方块）
            generatePierStep(blocks, stepPos, materials.pierStep, width, facing, i < steps - 1);
            
            currentY -= stepHeight;
            if (i < steps - 1) {
                currentY = Math.max(end.getY(), currentY); // 确保不低于水面
            }
        }
        
        // 水面平台
        generatePierDeck(blocks, end, materials.pierDeck, width, facing);
    }
    
    /**
     * 生成挑空木栈桥
     */
    private static void generateElevatedPier(List<PlannedBlock> blocks, ServerWorld world,
                                            WaterDetector.PierAnchor anchor, PierMaterials materials,
                                            int width, int heightDiff) {
        BlockPos start = anchor.landPos;
        BlockPos end = anchor.waterPos;
        Direction facing = getDirection(start, end);
        
        // 计算路径
        List<BlockPos> path = calculatePath(start, end, facing);
        
        // 使用柱子支撑
        int deckY = start.getY(); // 保持陆地高度
        
        for (BlockPos center : path) {
            BlockPos deckPos = new BlockPos(center.getX(), deckY, center.getZ());
            
            // 生成柱子（从地面到平台）
            int groundY = world.getTopY(net.minecraft.world.Heightmap.Type.WORLD_SURFACE, center.getX(), center.getZ());
            for (int y = groundY; y < deckY; y++) {
                BlockPos pillarPos = new BlockPos(center.getX(), y, center.getZ());
                blocks.add(new PlannedBlock(pillarPos, materials.pierStep)); // 使用步阶材质作为柱子
            }
            
            // 生成平台
            generatePierDeck(blocks, deckPos, materials.pierDeck, width, facing);
        }
        
        // 延伸到水面
        BlockPos waterEnd = end.offset(facing, 2);
        for (int i = 0; i <= 2; i++) {
            BlockPos pos = end.offset(facing, i);
            BlockPos deckPos = new BlockPos(pos.getX(), deckY, pos.getZ());
            
            // 柱子
            int groundY = Math.min(deckY - 1, pos.getY());
            for (int y = groundY; y < deckY; y++) {
                BlockPos pillarPos = new BlockPos(pos.getX(), y, pos.getZ());
                blocks.add(new PlannedBlock(pillarPos, materials.pierStep));
            }
            
            generatePierDeck(blocks, deckPos, materials.pierDeck, width, facing);
        }
    }
    
    /**
     * 生成码头平台
     */
    private static void generatePierDeck(List<PlannedBlock> blocks, BlockPos center, BlockState material,
                                        int width, Direction facing) {
        if (width <= 0) width = 3;
        
        // 计算横向方向
        Direction side = facing.rotateYClockwise();
        int halfWidth = width / 2;
        
        for (int i = -halfWidth; i <= halfWidth; i++) {
            BlockPos pos = center.offset(side, i);
            blocks.add(new PlannedBlock(pos, material));
            
            // 边缘使用边缘材质
            if (Math.abs(i) == halfWidth) {
                BlockPos edgePos = pos.up();
                BlockState edgeState = material;
                // 尝试设置为顶部半砖
                if (edgeState.contains(Properties.SLAB_TYPE)) {
                    edgeState = edgeState.with(Properties.SLAB_TYPE, SlabType.TOP);
                }
                blocks.add(new PlannedBlock(edgePos, edgeState));
            }
        }
    }
    
    /**
     * 生成台阶
     */
    private static void generatePierStep(List<PlannedBlock> blocks, BlockPos center, BlockState material,
                                        int width, Direction facing, boolean isIntermediate) {
        if (width <= 0) width = 3;
        
        Direction side = facing.rotateYClockwise();
        int halfWidth = width / 2;
        
        for (int i = -halfWidth; i <= halfWidth; i++) {
            BlockPos pos = center.offset(side, i);
            BlockState stepState = material;
            
            // 尝试设置为楼梯（设置朝向）
            if (stepState.contains(Properties.HORIZONTAL_FACING)) {
                stepState = stepState.with(Properties.HORIZONTAL_FACING, facing);
            }
            // 注意：楼梯的 half 属性在大多数情况下不需要手动设置，使用默认值即可
            
            blocks.add(new PlannedBlock(pos, stepState));
        }
    }
    
    /**
     * 添加视觉细节
     */
    private static void addVisualDetails(List<PlannedBlock> blocks, ServerWorld world,
                                        WaterDetector.PierAnchor anchor, PierMaterials materials,
                                        String paletteId) {
        // 1. 拴船桩（在码头转角处）
        addMooringPosts(blocks, anchor, materials.mooringPost);
        
        // 2. 驳岸处理（码头两侧岸线替换为石质方块）
        addRevetment(blocks, world, anchor, materials.revetment, paletteId);
        
        // 3. 照明（码头入口悬挂灯笼）
        addLighting(blocks, anchor, materials);
    }
    
    /**
     * 添加拴船桩
     */
    private static void addMooringPosts(List<PlannedBlock> blocks, WaterDetector.PierAnchor anchor,
                                       BlockState postMaterial) {
        // 在水岸线位置和停泊位位置各放置一个拴船桩
        BlockPos[] positions = {anchor.landPos, anchor.mooringPos};
        
        for (BlockPos pos : positions) {
            if (pos == null) continue;
            
            // 木栅栏（拴船桩）
            blocks.add(new PlannedBlock(pos.up(), postMaterial));
            
            // 顶部放压力板或羊毛毯（模拟缆绳）
            BlockPos top = pos.up(2);
            BlockState topBlock = Blocks.OAK_PRESSURE_PLATE.getDefaultState();
            blocks.add(new PlannedBlock(top, topBlock));
        }
    }
    
    /**
     * 添加驳岸处理
     */
    private static void addRevetment(List<PlannedBlock> blocks, ServerWorld world,
                                    WaterDetector.PierAnchor anchor, BlockState revetmentMaterial,
                                    String paletteId) {
        // 简化实现：在码头两侧各延伸1-2格，替换为石质方块
        Direction facing = getDirection(anchor.landPos, anchor.waterPos);
        Direction side = facing.rotateYClockwise();
        
        for (int offset : new int[]{-2, -1, 1, 2}) {
            BlockPos pos = anchor.landPos.offset(side, offset);
            int y = world.getTopY(net.minecraft.world.Heightmap.Type.WORLD_SURFACE, pos.getX(), pos.getZ());
            BlockPos revetPos = new BlockPos(pos.getX(), y, pos.getZ());
            blocks.add(new PlannedBlock(revetPos, revetmentMaterial));
        }
    }
    
    /**
     * 添加照明
     */
    private static void addLighting(List<PlannedBlock> blocks, WaterDetector.PierAnchor anchor,
                                   PierMaterials materials) {
        // 在建筑出入口附近悬挂灯笼
        BlockPos lightPos = anchor.buildingExit.up(2);
        BlockState lantern = Blocks.LANTERN.getDefaultState();
        blocks.add(new PlannedBlock(lightPos, lantern));
    }
    
    /**
     * 计算路径（从起点到终点的方块路径）
     */
    private static List<BlockPos> calculatePath(BlockPos start, BlockPos end, Direction facing) {
        List<BlockPos> path = new ArrayList<>();
        
        int dx = end.getX() - start.getX();
        int dz = end.getZ() - start.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        
        if (steps == 0) {
            path.add(start);
            return path;
        }
        
        for (int i = 0; i <= steps; i++) {
            int x = start.getX() + (dx * i) / steps;
            int z = start.getZ() + (dz * i) / steps;
            int y = start.getY(); // 保持Y坐标
            path.add(new BlockPos(x, y, z));
        }
        
        return path;
    }
    
    /**
     * 获取方向
     */
    private static Direction getDirection(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        } else {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }
    
    /**
     * 码头类型
     */
    private enum PierType {
        FLAT,      // 平铺式驳岸
        STEPPED,   // 阶梯式码头
        ELEVATED   // 挑空木栈桥
    }
    
    /**
     * 码头材质
     */
    private static class PierMaterials {
        BlockState pierStep = Blocks.STONE_BRICK_STAIRS.getDefaultState();
        BlockState pierEdge = Blocks.ANDESITE_SLAB.getDefaultState();
        BlockState pierDeck = Blocks.STONE_BRICKS.getDefaultState();
        BlockState mooringPost = Blocks.SPRUCE_FENCE.getDefaultState();
        BlockState revetment = Blocks.STONE_BRICKS.getDefaultState();
    }
}

