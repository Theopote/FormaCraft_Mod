package com.formacraft.common.building;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.List;

public class TerrainScanner {
    private final ServerWorld world;
    
    public TerrainScanner(ServerWorld world) {
        this.world = world;
    }
    
    public TerrainScanResult scan(BlockPos center, int width, int length) {
        int minX = center.getX() - width / 2;
        int maxX = center.getX() + width / 2;
        int minZ = center.getZ() - length / 2;
        int maxZ = center.getZ() + length / 2;
        
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int totalHeight = 0;
        int waterBlocks = 0;
        int totalPoints = 0;
        
        // Sample points in a grid pattern
        List<BlockPos> samplePoints = new ArrayList<>();
        for (int x = minX; x <= maxX; x += 2) {
            for (int z = minZ; z <= maxZ; z += 2) {
                // Get the topmost solid block at this x,z coordinate
                int y = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
                BlockPos pos = new BlockPos(x, y, z);
                samplePoints.add(pos);
                
                // Update min/max height
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                totalHeight += y;
                
                // Check for water or other special blocks
                Block block = world.getBlockState(pos.down()).getBlock();
                if (block == Blocks.WATER || block == Blocks.LAVA) {
                    waterBlocks++;
                }
                
                totalPoints++;
            }
        }
        
        // Calculate average height and terrain roughness
        double avgHeight = (double) totalHeight / totalPoints;
        double roughness = calculateRoughness(samplePoints, avgHeight);
        
        // Determine terrain type
        TerrainType terrainType = determineTerrainType(roughness, maxY - minY, 
                (double) waterBlocks / totalPoints);
        
        return new TerrainScanResult(terrainType, minY, maxY, avgHeight, roughness, waterBlocks);
    }
    
    private double calculateRoughness(List<BlockPos> points, double avgHeight) {
        double sum = 0;
        for (BlockPos pos : points) {
            double diff = pos.getY() - avgHeight;
            sum += diff * diff;
        }
        return Math.sqrt(sum / points.size());
    }
    
    private TerrainType determineTerrainType(double roughness, int heightRange, double waterRatio) {
        if (waterRatio > 0.3) {
            return TerrainType.WATER;
        } else if (roughness > 2.0 || heightRange > 5) {
            return TerrainType.MOUNTAINOUS;
        } else if (roughness > 0.8 || heightRange > 2) {
            return TerrainType.HILLY;
        } else {
            return TerrainType.FLAT;
        }
    }
    
    public enum TerrainType {
        FLAT,       // Mostly flat terrain, ideal for most buildings
        HILLY,      // Gentle slopes, may need some leveling
        MOUNTAINOUS,// Steep slopes, needs significant adaptation
        WATER       // Water body, may need pilings or other special handling
    }
}
