package com.formacraft.server.generator;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.server.build.GeneratedStructure;
import com.formacraft.server.build.PlannedBlock;
import com.formacraft.server.material.PaletteResolver;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 城墙生成器
 * 生成矩形直线城墙
 * 支持高度、长度、厚度配置
 */
public class WallGenerator implements StructureGenerator {

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> blocks = new ArrayList<>();

        // 获取参数
        int height = Math.max(3, spec.getHeight());
        int length = Math.max(5, spec.getFootprint() != null ? spec.getFootprint().getDepth() : 10);
        int thickness = 2; // 默认厚度为 2 格

        // 获取材质
        BlockState wallBlock = getState(world, spec.getMaterials() != null ? spec.getMaterials().getWall() : null);
        String paletteId = null;
        if (spec.getExtra() != null) {
            Object pid = spec.getExtra().get("paletteId");
            if (pid != null) paletteId = String.valueOf(pid).trim();
        }

        // 生成城墙（沿 Z 轴延伸）
        for (int z = 0; z < length; z++) {
            for (int t = 0; t < thickness; t++) {
                for (int y = 0; y < height; y++) {
                    BlockPos pos = origin.add(t, y, z);
                    BlockState st = wallBlock;
                    if (paletteId != null && !paletteId.isBlank()) {
                        long salt = (t * 31L) ^ (y * 17L) ^ (z * 13L);
                        st = PaletteResolver.pick(world, paletteId, "WALL_BASE", pos, salt, wallBlock);
                    }
                    blocks.add(new PlannedBlock(pos, st));
                }
            }
        }

        String description = String.format("Wall (height=%d, length=%d, thickness=%d)", 
                height, length, thickness);

        return new GeneratedStructure(
                null, // owner 将在 BuildExecutionService 中设置
                origin,
                description,
                blocks
        );
    }

    /**
     * 将字符串 blockId 转为 BlockState
     */
    private BlockState getState(ServerWorld world, String id) {
        if (id == null || id.isEmpty()) {
            return Blocks.STONE_BRICKS.getDefaultState();
        }

        try {
            // 解析 Identifier
            Identifier identifier;
            if (id.contains(":")) {
                identifier = Identifier.of(id);
            } else {
                identifier = Identifier.of("minecraft", id);
            }

            // 从注册表获取 Block
            Block block = Registries.BLOCK.get(identifier);
            return block.getDefaultState();

            // 回退方案
        } catch (Exception e) {
            return resolveBlockFallback(id);
        }
    }

    /**
     * 回退方案：通过字符串匹配解析常用方块
     */
    private BlockState resolveBlockFallback(String material) {
        if (material == null) return Blocks.STONE_BRICKS.getDefaultState();
        
        String lower = material.toLowerCase();
        
        if (lower.contains("stone_brick") || lower.contains("stonebrick")) {
            return Blocks.STONE_BRICKS.getDefaultState();
        }
        if (lower.contains("cobblestone")) {
            return Blocks.COBBLESTONE.getDefaultState();
        }
        if (lower.contains("stone") && !lower.contains("brick")) {
            return Blocks.STONE.getDefaultState();
        }
        if (lower.contains("brick") && !lower.contains("stone")) {
            return Blocks.BRICKS.getDefaultState();
        }
        
        return Blocks.STONE_BRICKS.getDefaultState();
    }
}

