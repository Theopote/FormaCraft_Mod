package com.formacraft.common.building;

public class TerrainScanResult {
    private final TerrainScanner.TerrainType terrainType;
    private final int minY;
    private final int maxY;
    private final double avgHeight;
    private final double roughness;
    private final int waterBlocks;
    
    public TerrainScanResult(TerrainScanner.TerrainType terrainType, int minY, int maxY, 
                            double avgHeight, double roughness, int waterBlocks) {
        this.terrainType = terrainType;
        this.minY = minY;
        this.maxY = maxY;
        this.avgHeight = avgHeight;
        this.roughness = roughness;
        this.waterBlocks = waterBlocks;
    }
    
    public TerrainScanner.TerrainType getTerrainType() {
        return terrainType;
    }
    
    public int getMinY() {
        return minY;
    }
    
    public int getMaxY() {
        return maxY;
    }
    
    public double getAvgHeight() {
        return avgHeight;
    }
    
    public double getRoughness() {
        return roughness;
    }
    
    public int getWaterBlocks() {
        return waterBlocks;
    }
}

