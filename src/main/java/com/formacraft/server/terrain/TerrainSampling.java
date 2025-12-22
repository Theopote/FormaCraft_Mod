package com.formacraft.server.terrain;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 地形高度采样工具
 */
public class TerrainSampling {

    /**
     * 在指定区域内采样地形高度
     * @param world 服务器世界
     * @param min 区域最小点
     * @param max 区域最大点
     * @return 高度列表
     */
    public static List<Integer> sampleHeights(ServerWorld world, BlockPos min, BlockPos max) {
        List<Integer> heights = new ArrayList<>();
        
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                // 使用 Heightmap 获取地表高度
                int y = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
                
                // 如果 Heightmap 返回无效值，手动查找
                int bottomY = world.getBottomY();
                // 使用一个合理的上限（Minecraft 1.21 的世界高度通常是 384）
                int maxY = 384;
                if (y <= bottomY || y > maxY) {
                    y = findTopBlock(world, x, z);
                }
                
                heights.add(y);
            }
        }
        
        return heights;
    }

    /**
     * 手动查找指定坐标的最高非空气方块
     */
    private static int findTopBlock(ServerWorld world, int x, int z) {
        // 从合理的高度开始向下搜索（Minecraft 1.21 的世界高度通常是 384）
        int y = 383;
        int bottomY = world.getBottomY();
        while (y > bottomY) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!world.getBlockState(pos).isAir()) {
                return y;
            }
            y--;
        }
        return 64; // 默认高度
    }

    /**
     * 计算高度列表的中位数
     */
    public static int medianHeight(List<Integer> heights) {
        if (heights.isEmpty()) {
            return 64; // 默认高度
        }
        
        List<Integer> sorted = new ArrayList<>(heights);
        Collections.sort(sorted);
        
        int size = sorted.size();
        if (size % 2 == 0) {
            // 偶数个元素，取中间两个的平均值
            return (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2;
        } else {
            // 奇数个元素，取中间值
            return sorted.get(size / 2);
        }
    }

    /**
     * 计算高度列表的平均值
     */
    public static int averageHeight(List<Integer> heights) {
        if (heights.isEmpty()) {
            return 64;
        }
        
        int sum = 0;
        for (int h : heights) {
            sum += h;
        }
        return sum / heights.size();
    }

    /**
     * 计算高度列表的众数（出现最频繁的高度）
     */
    public static int modeHeight(List<Integer> heights) {
        if (heights.isEmpty()) {
            return 64;
        }
        
        // 统计每个高度的出现次数
        java.util.Map<Integer, Integer> frequency = new java.util.HashMap<>();
        for (int h : heights) {
            frequency.put(h, frequency.getOrDefault(h, 0) + 1);
        }
        
        // 找到出现次数最多的高度
        int maxCount = 0;
        int mode = heights.getFirst();
        for (java.util.Map.Entry<Integer, Integer> entry : frequency.entrySet()) {
            if (entry.getValue() > maxCount) {
                maxCount = entry.getValue();
                mode = entry.getKey();
            }
        }
        
        return mode;
    }
}

