package com.formacraft.common.building;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import java.util.Map;

public class LayeredBuilder {
    private final ServerWorld world;
    private final TerrainScanner terrainScanner;
    
    public LayeredBuilder(ServerWorld world) {
        this.world = world;
        this.terrainScanner = new TerrainScanner(world);
    }
    
    public void buildStructure(BuildingPlan plan, BlockPos origin) {
        // First scan the terrain to adapt the building
        TerrainScanResult scan = terrainScanner.scan(origin, plan.getWidth(), plan.getLength());
        
        // Adjust the building plan based on terrain
        plan.adaptToTerrain(scan);
        
        // Build each layer
        for (Layer layer : plan.getLayers()) {
            int currentY = origin.getY() + layer.getYLevel();
            
            // Skip if the layer is below the minimum build height
            if (currentY < world.getBottomY()) continue;
            
            // Skip if the layer is above the maximum build height
            if (currentY > world.getTopY(Heightmap.Type.WORLD_SURFACE, origin.getX(), origin.getZ())) break;
            
            // Build this layer
            buildLayer(layer, origin, currentY);
        }
    }
    
    private void buildLayer(Layer layer, BlockPos origin, int currentY) {
        BlockPos.Mutable pos = new BlockPos.Mutable();
        
        for (Map.Entry<BlockPos, BlockState> entry : layer.getBlocks().entrySet()) {
            BlockPos relativePos = entry.getKey();
            BlockState state = entry.getValue();
            
            // Calculate the absolute position in the world
            pos.set(
                origin.getX() + relativePos.getX(),
                currentY + relativePos.getY(),
                origin.getZ() + relativePos.getZ()
            );
            
            // Place the block if the position is within world bounds
            int topY = world.getTopY(Heightmap.Type.WORLD_SURFACE, pos.getX(), pos.getZ());
            if (pos.getY() >= world.getBottomY() && pos.getY() <= topY) {
                world.setBlockState(pos, state);
            }
        }
    }
}
