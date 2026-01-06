package com.formacraft.server.memory;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 空间索引（Spatial Index）
 * 基于区块的快速坐标检索
 * 解决"它在哪？"的问题
 */
public class SpatialIndex {
    // 区块坐标 -> 该区块内的项目 UUID 列表
    private final Map<ChunkPos, Set<String>> chunkIndex = new ConcurrentHashMap<>();
    
    // UUID -> ProjectMemory 的快速查找
    private final Map<String, ProjectMemory> memoryCache = new ConcurrentHashMap<>();
    
    /**
     * 添加记忆到索引
     */
    public void addMemory(ProjectMemory memory) {
        if (memory == null || memory.getUuid() == null || memory.getBounds() == null) {
            return;
        }
        
        String uuid = memory.getUuid();
        memoryCache.put(uuid, memory);
        
        ProjectMemory.SpatialBounds bounds = memory.getBounds();
        BlockPos min = bounds.getMinPos();
        BlockPos max = bounds.getMaxPos();
        
        if (min == null || max == null) {
            return;
        }
        
        // 计算覆盖的所有区块
        int minChunkX = min.getX() >> 4;
        int minChunkZ = min.getZ() >> 4;
        int maxChunkX = max.getX() >> 4;
        int maxChunkZ = max.getZ() >> 4;
        
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                ChunkPos chunkPos = new ChunkPos(cx, cz);
                chunkIndex.computeIfAbsent(chunkPos, k -> ConcurrentHashMap.newKeySet()).add(uuid);
            }
        }
    }
    
    /**
     * 从索引中移除记忆
     */
    public void removeMemory(String uuid) {
        if (uuid == null) {
            return;
        }
        
        ProjectMemory memory = memoryCache.remove(uuid);
        if (memory == null || memory.getBounds() == null) {
            return;
        }
        
        ProjectMemory.SpatialBounds bounds = memory.getBounds();
        BlockPos min = bounds.getMinPos();
        BlockPos max = bounds.getMaxPos();
        
        if (min == null || max == null) {
            return;
        }
        
        // 从所有相关区块中移除
        int minChunkX = min.getX() >> 4;
        int minChunkZ = min.getZ() >> 4;
        int maxChunkX = max.getX() >> 4;
        int maxChunkZ = max.getZ() >> 4;
        
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                ChunkPos chunkPos = new ChunkPos(cx, cz);
                Set<String> uuids = chunkIndex.get(chunkPos);
                if (uuids != null) {
                    uuids.remove(uuid);
                    if (uuids.isEmpty()) {
                        chunkIndex.remove(chunkPos);
                    }
                }
            }
        }
    }
    
    /**
     * 根据坐标查找包含该位置的所有项目
     */
    public List<ProjectMemory> findAt(BlockPos pos) {
        ChunkPos chunkPos = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
        Set<String> uuids = chunkIndex.get(chunkPos);
        
        if (uuids == null || uuids.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<ProjectMemory> results = new ArrayList<>();
        for (String uuid : uuids) {
            ProjectMemory memory = memoryCache.get(uuid);
            if (memory != null && memory.contains(pos)) {
                results.add(memory);
            }
        }
        
        return results;
    }
    
    /**
     * 根据坐标查找最近的项目（在指定范围内）
     */
    public ProjectMemory findNearest(BlockPos pos, double maxDistance) {
        ChunkPos centerChunk = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
        int searchRadius = (int) Math.ceil(maxDistance / 16.0) + 1;
        
        ProjectMemory nearest = null;
        double nearestDistance = maxDistance;
        
        for (int dx = -searchRadius; dx <= searchRadius; dx++) {
            for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                ChunkPos chunkPos = new ChunkPos(centerChunk.x + dx, centerChunk.z + dz);
                Set<String> uuids = chunkIndex.get(chunkPos);
                
                if (uuids != null) {
                    for (String uuid : uuids) {
                        ProjectMemory memory = memoryCache.get(uuid);
                        if (memory != null && memory.getBounds() != null) {
                            ProjectMemory.SpatialBounds bounds = memory.getBounds();
                            BlockPos min = bounds.getMinPos();
                            BlockPos max = bounds.getMaxPos();
                            
                            if (min != null && max != null) {
                                // 计算到边界框的最近距离
                                double dist = distanceToBounds(pos, min, max);
                                if (dist < nearestDistance) {
                                    nearestDistance = dist;
                                    nearest = memory;
                                }
                            }
                        }
                    }
                }
            }
        }
        
        return nearest;
    }
    
    /**
     * 计算点到边界框的距离
     */
    private double distanceToBounds(BlockPos pos, BlockPos min, BlockPos max) {
        int dx = Math.max(Math.max(min.getX() - pos.getX(), pos.getX() - max.getX()), 0);
        int dy = Math.max(Math.max(min.getY() - pos.getY(), pos.getY() - max.getY()), 0);
        int dz = Math.max(Math.max(min.getZ() - pos.getZ(), pos.getZ() - max.getZ()), 0);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
    
    /**
     * 根据 UUID 获取记忆
     */
    public ProjectMemory getMemory(String uuid) {
        return memoryCache.get(uuid);
    }
    
    /**
     * 清空索引
     */
    public void clear() {
        chunkIndex.clear();
        memoryCache.clear();
    }
    
    /**
     * 获取索引中的记忆数量
     */
    public int size() {
        return memoryCache.size();
    }
}

