package com.formacraft.server.generation.structure;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 矩形房屋生成器
 */
public class RectangularHouseGenerator implements StructureGenerator {
    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> blocks = new ArrayList<>();
        
        int width = spec.getWidth() > 0 ? spec.getWidth() : 8;
        int depth = spec.getDepth() > 0 ? spec.getDepth() : 6;
        int height = spec.getHeight() > 0 ? spec.getHeight() : 4;
        
        Block wallBlock = resolveBlock(spec.getMaterials() != null ? spec.getMaterials().getWall() : null);
        Block floorBlock = resolveBlock(spec.getMaterials() != null ? spec.getMaterials().getFloor() : null);
        Block roofBlock = resolveBlock(spec.getMaterials() != null ? spec.getMaterials().getRoof() : null);
        
        // 生成墙体
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    boolean isWall = x == 0 || x == width - 1 || z == 0 || z == depth - 1;
                    
                    if (isWall) {
                        BlockPos pos = origin.add(x, y, z);
                        blocks.add(new PlannedBlock(pos, wallBlock.getDefaultState()));
                    }
                }
            }
        }
        
        // 生成地板
        if (floorBlock != null) {
            for (int x = 1; x < width - 1; x++) {
                for (int z = 1; z < depth - 1; z++) {
                    BlockPos pos = origin.add(x, 0, z);
                    blocks.add(new PlannedBlock(pos, floorBlock.getDefaultState()));
                }
            }
        }
        
        // 生成屋顶
        if (roofBlock != null && spec.getFeatures() != null && spec.getFeatures().hasRoof()) {
            for (int roofY = 0; roofY < 3; roofY++) {
                for (int x = -roofY; x < width + roofY; x++) {
                    for (int z = 0; z < depth; z++) {
                        if (x >= 0 && x < width) {
                            BlockPos pos = origin.add(x, height + roofY, z);
                            blocks.add(new PlannedBlock(pos, roofBlock.getDefaultState()));
                        }
                    }
                }
            }
        }
        
        // 添加窗户
        if (spec.getFeatures() != null && spec.getFeatures().hasWindows()) {
            Block windowBlock = resolveBlock(spec.getMaterials() != null ? spec.getMaterials().getWindow() : null);
            int windowCount = spec.getFeatures().getWindowCount() > 0 ? spec.getFeatures().getWindowCount() : 2;
            // 简单实现：在墙上开几个窗户
            for (int i = 0; i < windowCount && i < width - 2; i++) {
                int x = 1 + i;
                int y = height / 2;
                BlockPos pos = origin.add(x, y, 0);
                blocks.add(new PlannedBlock(pos, windowBlock.getDefaultState()));
            }
        }
        
        String description = String.format("House (%s, %dx%dx%d)", 
                spec.getType(), width, height, depth);
        
        return new GeneratedStructure(null, origin, description, blocks);
    }

    private Block resolveBlock(String material) {
        if (material == null) return Blocks.OAK_PLANKS;
        String lower = material.toLowerCase();
        if (lower.contains("stone")) return Blocks.STONE;
        if (lower.contains("brick")) return Blocks.BRICKS;
        if (lower.contains("wood") || lower.contains("oak")) return Blocks.OAK_PLANKS;
        if (lower.contains("dark_oak")) return Blocks.DARK_OAK_PLANKS;
        if (lower.contains("plank")) return Blocks.OAK_PLANKS;
        return Blocks.OAK_PLANKS;
    }
}

