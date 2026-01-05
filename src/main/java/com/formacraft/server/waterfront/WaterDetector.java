package com.formacraft.server.waterfront;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * 水体检测工具类
 * 
 * 用于检测建筑附近的水体，并分析空间关系（距离、高差、水岸线位置）。
 * 支持临水码头自动关联逻辑。
 */
public class WaterDetector {
    
    /**
     * 检测建筑附近是否有水体
     * 
     * @param world 世界对象
     * @param buildingOrigin 建筑原点
     * @param buildingWidth 建筑宽度
     * @param buildingDepth 建筑深度
     * @param searchRadius 搜索半径（默认8格）
     * @return 检测结果
     */
    public static WaterDetectionResult detectNearbyWater(
            ServerWorld world, BlockPos buildingOrigin, int buildingWidth, int buildingDepth, int searchRadius) {
        return detectNearbyWater(world, buildingOrigin, buildingWidth, buildingDepth, searchRadius, 8);
    }
    
    /**
     * 检测建筑附近是否有水体
     * 
     * @param world 世界对象
     * @param buildingOrigin 建筑原点
     * @param buildingWidth 建筑宽度
     * @param buildingDepth 建筑深度
     * @param searchRadius 搜索半径
     * @param distanceThreshold 距离阈值（建筑边缘到水岸线的最短距离阈值）
     * @return 检测结果
     */
    public static WaterDetectionResult detectNearbyWater(
            ServerWorld world, BlockPos buildingOrigin, int buildingWidth, int buildingDepth,
            int searchRadius, int distanceThreshold) {
        
        if (world == null || buildingOrigin == null) {
            return WaterDetectionResult.NONE;
        }
        
        // 计算建筑边界
        int minX = buildingOrigin.getX();
        int maxX = buildingOrigin.getX() + buildingWidth - 1;
        int minZ = buildingOrigin.getZ();
        int maxZ = buildingOrigin.getZ() + buildingDepth - 1;
        int baseY = buildingOrigin.getY();
        
        // 扩展搜索范围
        int searchMinX = minX - searchRadius;
        int searchMaxX = maxX + searchRadius;
        int searchMinZ = minZ - searchRadius;
        int searchMaxZ = maxZ + searchRadius;
        
        List<WaterEdge> waterEdges = new ArrayList<>();
        int nearestDistance = Integer.MAX_VALUE;
        BlockPos nearestWaterPos = null;
        BlockPos nearestLandPos = null;
        int landY = baseY;
        int waterY = baseY;
        
        // 扫描搜索区域，寻找水岸线
        for (int x = searchMinX; x <= searchMaxX; x++) {
            for (int z = searchMinZ; z <= searchMaxZ; z++) {
                // 跳过建筑内部
                if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) {
                    continue;
                }
                
                // 获取地表高度
                int surfaceY = world.getTopY(net.minecraft.world.Heightmap.Type.WORLD_SURFACE, x, z);
                BlockPos surfacePos = new BlockPos(x, surfaceY, z);
                BlockState surfaceState = world.getBlockState(surfacePos);
                
                // 检查当前位置是否是水
                boolean isWater = surfaceState.getBlock() == Blocks.WATER 
                    || !surfaceState.getFluidState().isEmpty();
                
                if (isWater) {
                    // 检查相邻位置是否有陆地（找到水岸线）
                    for (Direction dir : Direction.values()) {
                        if (dir.getAxis() == net.minecraft.util.math.Direction.Axis.Y) continue;
                        
                        BlockPos neighborPos = surfacePos.offset(dir);
                        BlockState neighborState = world.getBlockState(neighborPos);
                        
                        // 如果相邻位置不是水，则找到了水岸线
                        if (neighborState.getBlock() != Blocks.WATER 
                            && neighborState.getFluidState().isEmpty()
                            && !neighborState.isAir()) {
                            
                            // 计算到建筑边缘的距离
                            int distToBuilding = distanceToBuildingEdge(x, z, minX, maxX, minZ, maxZ);
                            
                            if (distToBuilding <= distanceThreshold) {
                                waterEdges.add(new WaterEdge(neighborPos, surfacePos, distToBuilding));
                                
                                // 更新最近的水体位置
                                if (distToBuilding < nearestDistance) {
                                    nearestDistance = distToBuilding;
                                    nearestWaterPos = surfacePos;
                                    nearestLandPos = neighborPos;
                                    landY = neighborPos.getY();
                                    waterY = surfacePos.getY();
                                }
                            }
                        }
                    }
                }
            }
        }
        
        if (waterEdges.isEmpty()) {
            return WaterDetectionResult.NONE;
        }
        
        return new WaterDetectionResult(waterEdges, nearestDistance, nearestLandPos, nearestWaterPos, landY, waterY);
    }
    
    /**
     * 计算点到建筑边缘的最短距离
     */
    private static int distanceToBuildingEdge(int x, int z, int minX, int maxX, int minZ, int maxZ) {
        int dx = 0;
        int dz = 0;
        
        if (x < minX) {
            dx = minX - x;
        } else if (x > maxX) {
            dx = x - maxX;
        }
        
        if (z < minZ) {
            dz = minZ - z;
        } else if (z > maxZ) {
            dz = z - maxZ;
        }
        
        // 如果点在建筑内部，返回0
        if (dx == 0 && dz == 0) {
            return 0;
        }
        
        // 使用曼哈顿距离（更符合方块世界的实际距离）
        return dx + dz;
    }
    
    /**
     * 寻找建筑出入口到水岸线的最佳接驳点
     * 
     * @param world 世界对象
     * @param buildingExit 建筑出入口位置
     * @param exitFacing 出入口朝向
     * @param searchRadius 搜索半径
     * @return 最佳接驳点，如果没有找到则返回null
     */
    public static PierAnchor findBestPierAnchor(
            ServerWorld world, BlockPos buildingExit, Direction exitFacing, int searchRadius) {
        
        if (world == null || buildingExit == null || exitFacing == null) {
            return null;
        }
        
        // 沿出入口朝向进行射线探测
        BlockPos bestWaterPos = null;
        BlockPos bestLandPos = null;
        int minDistance = Integer.MAX_VALUE;
        
        // 向前搜索（最多searchRadius格）
        for (int i = 1; i <= searchRadius; i++) {
            BlockPos checkPos = buildingExit.offset(exitFacing, i);
            BlockState state = world.getBlockState(checkPos);
            
            // 检查是否是水
            boolean isWater = state.getBlock() == Blocks.WATER 
                || !state.getFluidState().isEmpty();
            
            if (isWater) {
                // 找到水，检查前一个位置是否是陆地
                BlockPos landPos = checkPos.offset(exitFacing.getOpposite());
                BlockState landState = world.getBlockState(landPos);
                
                if (landState.getBlock() != Blocks.WATER 
                    && landState.getFluidState().isEmpty()
                    && !landState.isAir()) {
                    
                    // 找到水岸线
                    if (i < minDistance) {
                        minDistance = i;
                        bestWaterPos = checkPos;
                        bestLandPos = landPos;
                    }
                    break; // 找到第一个水岸线就停止
                }
            }
        }
        
        if (bestWaterPos == null || bestLandPos == null) {
            return null;
        }
        
        int landY = bestLandPos.getY();
        int waterY = bestWaterPos.getY();
        int heightDiff = landY - waterY;
        
        // 计算停泊位（向水域延伸2-3格）
        BlockPos mooringPos = bestWaterPos.offset(exitFacing, 2);
        
        return new PierAnchor(buildingExit, bestLandPos, bestWaterPos, mooringPos, heightDiff, minDistance);
    }
    
    /**
     * 水体检测结果
     */
    public static class WaterDetectionResult {
        public static final WaterDetectionResult NONE = new WaterDetectionResult(List.of(), Integer.MAX_VALUE, null, null, 0, 0);
        
        public final List<WaterEdge> waterEdges;
        public final int nearestDistance;
        public final BlockPos nearestLandPos;
        public final BlockPos nearestWaterPos;
        public final int landY;
        public final int waterY;
        
        public WaterDetectionResult(List<WaterEdge> waterEdges, int nearestDistance,
                                   BlockPos nearestLandPos, BlockPos nearestWaterPos,
                                   int landY, int waterY) {
            this.waterEdges = waterEdges;
            this.nearestDistance = nearestDistance;
            this.nearestLandPos = nearestLandPos;
            this.nearestWaterPos = nearestWaterPos;
            this.landY = landY;
            this.waterY = waterY;
        }
        
        public boolean hasWater() {
            return !waterEdges.isEmpty();
        }
        
        public int getHeightDifference() {
            return landY - waterY;
        }
    }
    
    /**
     * 水岸线信息
     */
    public static class WaterEdge {
        public final BlockPos landPos;  // 陆地位置
        public final BlockPos waterPos; // 水体位置
        public final int distanceToBuilding; // 到建筑边缘的距离
        
        public WaterEdge(BlockPos landPos, BlockPos waterPos, int distanceToBuilding) {
            this.landPos = landPos;
            this.waterPos = waterPos;
            this.distanceToBuilding = distanceToBuilding;
        }
    }
    
    /**
     * 码头锚点信息
     */
    public static class PierAnchor {
        public final BlockPos buildingExit;  // 建筑出入口
        public final BlockPos landPos;       // 水岸线陆地位置
        public final BlockPos waterPos;      // 水岸线水体位置
        public final BlockPos mooringPos;    // 停泊位（向水域延伸）
        public final int heightDiff;         // 高差（陆地Y - 水体Y）
        public final int distance;           // 距离
        
        public PierAnchor(BlockPos buildingExit, BlockPos landPos, BlockPos waterPos,
                         BlockPos mooringPos, int heightDiff, int distance) {
            this.buildingExit = buildingExit;
            this.landPos = landPos;
            this.waterPos = waterPos;
            this.mooringPos = mooringPos;
            this.heightDiff = heightDiff;
            this.distance = distance;
        }
    }
}

