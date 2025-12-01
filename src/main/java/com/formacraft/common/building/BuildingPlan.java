package com.formacraft.common.building;

import java.util.ArrayList;
import java.util.List;

public class BuildingPlan {
    private int width;
    private int length;
    private final List<Layer> layers;
    
    public BuildingPlan(int width, int length) {
        this.width = width;
        this.length = length;
        this.layers = new ArrayList<>();
    }
    
    public int getWidth() {
        return width;
    }
    
    public int getLength() {
        return length;
    }
    
    public void setWidth(int width) {
        this.width = width;
    }
    
    public void setLength(int length) {
        this.length = length;
    }
    
    public List<Layer> getLayers() {
        return new ArrayList<>(layers);
    }
    
    public void addLayer(Layer layer) {
        this.layers.add(layer);
    }
    
    public void adaptToTerrain(TerrainScanResult scan) {
        // Adjust building plan based on terrain scan results
        // This is a stub implementation
        if (scan.getTerrainType() == TerrainScanner.TerrainType.MOUNTAINOUS) {
            // For mountainous terrain, might need to adjust dimensions
            // This is a placeholder for actual terrain adaptation logic
        }
    }
}

