package com.formacraft.server.terrain;

import com.formacraft.common.build.PlannedBlock;
import com.formacraft.server.build.BuildConstraintContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
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
        int targetY = com.formacraft.server.world.WorldBuildBounds.clampY(world, TerrainSampling.medianHeight(heights));

        List<PlannedBlock> operations = new ArrayList<>();

        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                if (!TerrainSampling.isColumnReady(world, x, z)) {
                    TerrainSampling.noteUnloadedColumn();
                    continue;
                }
                // 向下填充（填充到 targetY - 3 到 targetY），钳制在维度高度内
                int[] fillRange = com.formacraft.server.world.WorldBuildBounds.clampYRange(world, targetY - 3, targetY);
                for (int y = fillRange[0]; y <= fillRange[1]; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState current = world.getBlockState(p);
                    
                    // 只填充空气或可替换的方块
                    if (current.isAir() || current.getBlock() == Blocks.WATER || current.getBlock() == Blocks.LAVA) {
                        emit(operations, p, fillMaterial);
                    }
                }

                // 向上清空（清除 targetY + 1 到 maxY + 5 的方块）
                int[] clearRange = com.formacraft.server.world.WorldBuildBounds.clampYRange(
                        world, targetY + 1, max.getY() + 5);
                for (int y = clearRange[0]; y <= clearRange[1]; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState current = world.getBlockState(p);
                    
                    // 清除非空气方块
                    if (!current.isAir()) {
                        emit(operations, p, Blocks.AIR.getDefaultState());
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
                if (!TerrainSampling.isColumnReady(world, x, z)) {
                    TerrainSampling.noteUnloadedColumn();
                    continue;
                }
                // 从 minY 到 maxY + 10 清理障碍物（钳制在维度高度内）
                int[] clearRange = com.formacraft.server.world.WorldBuildBounds.clampYRange(
                        world, min.getY(), max.getY() + 10);
                for (int y = clearRange[0]; y <= clearRange[1]; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(p);

                    if (isObstacle(state)) {
                        emit(operations, p, Blocks.AIR.getDefaultState());
                    }
                }
            }
        }

        return new TerrainOperation(operations);
    }

    /**
     * HARD_CONSTRAINT: same generation-time clip as TerrainFit / TerrainAdaptationEngine.
     * Visible for tests.
     */
    static void emit(List<PlannedBlock> operations, BlockPos p, BlockState state) {
        if (p == null || state == null) return;
        if (!BuildConstraintContext.allow(p)) return;
        operations.add(new PlannedBlock(p, state));
    }

    /**
     * Natural clutter to clear above a build site — logs/leaves (incl. cherry/pale oak via tags)
     * and replaceable / plant vegetation. Solid ground like {@link Blocks#GRASS_BLOCK} is never clutter.
     */
    static boolean isObstacle(BlockState state) {
        if (state == null || state.isAir() || !state.getFluidState().isEmpty()) return false;
        try {
            if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.LEAVES)) return true;
        } catch (IllegalStateException unbound) {
            if (TerrainSampling.isCanopyMaterial(state)) return true;
        }
        // Replaceable clutter (snow layers, short grass when marked replaceable, …).
        if (state.isReplaceable()) return true;
        // Flowers / bamboo / snow / vines are not always replaceable depending on state.
        var block = state.getBlock();
        return block instanceof net.minecraft.block.PlantBlock
                || block instanceof net.minecraft.block.LeavesBlock
                || block instanceof net.minecraft.block.SugarCaneBlock
                || block instanceof net.minecraft.block.CactusBlock
                || block instanceof net.minecraft.block.BambooBlock
                || block instanceof net.minecraft.block.SnowBlock
                || state.isOf(Blocks.VINE)
                || state.isOf(Blocks.MOSS_CARPET)
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
        int groundY = TerrainSampling.placementY(world, bridgeEnd.getX(), bridgeEnd.getZ());
        if (groundY <= world.getBottomY()) {
            groundY = bridgeEnd.getY() - 3; // 回退方案
        }
        
        int targetY = com.formacraft.server.world.WorldBuildBounds.clampY(
                world, Math.max(groundY, bridgeEnd.getY() - 2));
        
        // 在桥端点周围创建平台
        for (int x = bridgeEnd.getX() - platformSize; x <= bridgeEnd.getX() + platformSize; x++) {
            for (int z = bridgeEnd.getZ() - platformSize; z <= bridgeEnd.getZ() + platformSize; z++) {
                if (!TerrainSampling.isColumnReady(world, x, z)) {
                    TerrainSampling.noteUnloadedColumn();
                    continue;
                }
                int[] fillRange = com.formacraft.server.world.WorldBuildBounds.clampYRange(world, targetY - 2, targetY);
                for (int y = fillRange[0]; y <= fillRange[1]; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState current = world.getBlockState(p);
                    
                    if (current.isAir() || current.getBlock() == Blocks.WATER || current.getBlock() == Blocks.LAVA) {
                        emit(operations, p, fillMaterial);
                    }
                }
                
                int[] clearRange = com.formacraft.server.world.WorldBuildBounds.clampYRange(
                        world, targetY + 1, bridgeEnd.getY() + 2);
                for (int y = clearRange[0]; y <= clearRange[1]; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState current = world.getBlockState(p);
                    
                    if (!current.isAir() && isObstacle(current)) {
                        emit(operations, p, Blocks.AIR.getDefaultState());
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

