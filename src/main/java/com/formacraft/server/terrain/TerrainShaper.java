package com.formacraft.server.terrain;

import com.formacraft.common.build.PlannedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 地形整形器
 * 提供各种地形修改操作
 */
public class TerrainShaper {

    /**
     * 对一个长方体区域做地形平整
     * - 找采样高度中位数作为 targetY
     * - targetY 以上削平
     * - targetY 以下填充
     * 
     * @param world 服务器世界
     * @param min 区域最小点
     * @param max 区域最大点
     * @param fillMaterial 填充材料
     * @return 地形整形操作
     */
    public static TerrainOperation flattenArea(ServerWorld world, BlockPos min, BlockPos max, BlockState fillMaterial) {
        List<Integer> heights = TerrainSampling.sampleHeights(world, min, max);
        int targetY = TerrainSampling.medianHeight(heights);

        List<PlannedBlock> operations = new ArrayList<>();

        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                // 向下填充（填充到 targetY - 3 到 targetY）
                for (int y = targetY - 3; y <= targetY; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState current = world.getBlockState(p);
                    
                    // 只填充空气或可替换的方块
                    if (current.isAir() || current.getBlock() == Blocks.WATER || current.getBlock() == Blocks.LAVA) {
                        operations.add(new PlannedBlock(p, fillMaterial));
                    }
                }

                // 向上清空（清除 targetY + 1 到 maxY + 5 的方块）
                int clearTop = max.getY() + 5;
                for (int y = targetY + 1; y <= clearTop; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState current = world.getBlockState(p);
                    
                    // 清除非空气方块
                    if (!current.isAir()) {
                        operations.add(new PlannedBlock(p, Blocks.AIR.getDefaultState()));
                    }
                }
            }
        }

        return new TerrainOperation(operations);
    }

    /**
     * 清理建筑区域上方的树叶/树木/草等障碍物
     * 
     * @param world 服务器世界
     * @param min 区域最小点
     * @param max 区域最大点
     * @return 地形整形操作
     */
    public static TerrainOperation clearObstacles(ServerWorld world, BlockPos min, BlockPos max) {
        List<PlannedBlock> operations = new ArrayList<>();

        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                // 从 minY 到 maxY + 10 清理障碍物
                for (int y = min.getY(); y <= max.getY() + 10; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(p);

                    if (isObstacle(state)) {
                        operations.add(new PlannedBlock(p, Blocks.AIR.getDefaultState()));
                    }
                }
            }
        }

        return new TerrainOperation(operations);
    }

    /**
     * 判断方块是否为障碍物
     */
    private static boolean isObstacle(BlockState state) {
        return state.isOf(Blocks.OAK_LOG)
                || state.isOf(Blocks.OAK_LEAVES)
                || state.isOf(Blocks.SPRUCE_LOG)
                || state.isOf(Blocks.SPRUCE_LEAVES)
                || state.isOf(Blocks.BIRCH_LOG)
                || state.isOf(Blocks.BIRCH_LEAVES)
                || state.isOf(Blocks.JUNGLE_LOG)
                || state.isOf(Blocks.JUNGLE_LEAVES)
                || state.isOf(Blocks.ACACIA_LOG)
                || state.isOf(Blocks.ACACIA_LEAVES)
                || state.isOf(Blocks.DARK_OAK_LOG)
                || state.isOf(Blocks.DARK_OAK_LEAVES)
                || state.isOf(Blocks.MANGROVE_LOG)
                || state.isOf(Blocks.MANGROVE_LEAVES)
                || state.isOf(Blocks.GRASS_BLOCK)
                || state.isOf(Blocks.SHORT_GRASS)
                || state.isOf(Blocks.TALL_GRASS)
                || state.isOf(Blocks.FERN)
                || state.isOf(Blocks.LARGE_FERN)
                || state.isOf(Blocks.DANDELION)
                || state.isOf(Blocks.POPPY)
                || state.isOf(Blocks.BLUE_ORCHID)
                || state.isOf(Blocks.ALLIUM)
                || state.isOf(Blocks.AZURE_BLUET)
                || state.isOf(Blocks.RED_TULIP)
                || state.isOf(Blocks.ORANGE_TULIP)
                || state.isOf(Blocks.WHITE_TULIP)
                || state.isOf(Blocks.PINK_TULIP)
                || state.isOf(Blocks.OXEYE_DAISY)
                || state.isOf(Blocks.CORNFLOWER)
                || state.isOf(Blocks.LILY_OF_THE_VALLEY)
                || state.isOf(Blocks.SUNFLOWER)
                || state.isOf(Blocks.LILAC)
                || state.isOf(Blocks.ROSE_BUSH)
                || state.isOf(Blocks.PEONY)
                || state.isOf(Blocks.VINE)
                || state.isOf(Blocks.BROWN_MUSHROOM)
                || state.isOf(Blocks.RED_MUSHROOM);
    }

    /**
     * 为桥梁端点创建接地平台
     * 
     * @param world 服务器世界
     * @param bridgeEnd 桥梁端点
     * @param platformSize 平台大小（半径）
     * @param fillMaterial 填充材料
     * @return 地形整形操作
     */
    public static TerrainOperation createBridgeLanding(ServerWorld world, BlockPos bridgeEnd, int platformSize, BlockState fillMaterial) {
        List<PlannedBlock> operations = new ArrayList<>();
        
        // 找到桥端点下方的地面高度
        int groundY = world.getTopY(net.minecraft.world.Heightmap.Type.WORLD_SURFACE, bridgeEnd.getX(), bridgeEnd.getZ());
        if (groundY <= world.getBottomY()) {
            groundY = bridgeEnd.getY() - 3; // 回退方案
        }
        
        int targetY = Math.max(groundY, bridgeEnd.getY() - 2);
        
        // 在桥端点周围创建平台
        for (int x = bridgeEnd.getX() - platformSize; x <= bridgeEnd.getX() + platformSize; x++) {
            for (int z = bridgeEnd.getZ() - platformSize; z <= bridgeEnd.getZ() + platformSize; z++) {
                // 填充到 targetY
                for (int y = targetY - 2; y <= targetY; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState current = world.getBlockState(p);
                    
                    if (current.isAir() || current.getBlock() == Blocks.WATER || current.getBlock() == Blocks.LAVA) {
                        operations.add(new PlannedBlock(p, fillMaterial));
                    }
                }
                
                // 清除 targetY 上方的障碍物
                for (int y = targetY + 1; y <= bridgeEnd.getY() + 2; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState current = world.getBlockState(p);
                    
                    if (!current.isAir() && isObstacle(current)) {
                        operations.add(new PlannedBlock(p, Blocks.AIR.getDefaultState()));
                    }
                }
            }
        }
        
        return new TerrainOperation(operations);
    }

    /**
     * 预处理建筑结构，添加地形整形
     * 
     * @param world 服务器世界
     * @param structure 原始建筑结构
     * @param min 建筑区域最小点
     * @param max 建筑区域最大点
     * @param fillMaterial 填充材料
     * @return 合并了地形整形的结构
     */
    public static com.formacraft.common.build.GeneratedStructure preprocessStructure(
            ServerWorld world,
            com.formacraft.common.build.GeneratedStructure structure,
            BlockPos min,
            BlockPos max,
            BlockState fillMaterial) {
        
        // 生成地形整形操作
        TerrainOperation flatten = flattenArea(world, min, max, fillMaterial);
        TerrainOperation clear = clearObstacles(world, min, max);
        
        // 合并所有 PlannedBlock
        List<PlannedBlock> merged = new ArrayList<>();
        merged.addAll(flatten.getBlocks());
        merged.addAll(clear.getBlocks());
        merged.addAll(structure.getBlocks());
        
        return new com.formacraft.common.build.GeneratedStructure(
                structure.getOwner(),
                structure.getOrigin(),
                structure.getDescription() + " + Terrain",
                merged
        );
    }
}

