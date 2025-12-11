package com.formacraft.server.generator;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.server.build.GeneratedStructure;
import com.formacraft.server.build.PlannedBlock;
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
 * 塔楼生成器
 * 支持 AI 生成的 BuildingSpec
 * 支持圆形塔楼（medieval / modern 都可）
 * 支持外墙、层高、窗户规则、楼板、内部楼梯、塔顶
 */
public class TowerGenerator implements StructureGenerator {
    
    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> result = new ArrayList<>();

        // 获取参数
        int radius = Math.max(3, spec.getFootprint() != null ? spec.getFootprint().getRadius() : 6);
        int height = Math.max(8, spec.getHeight());
        int floors = Math.max(1, spec.getFloors());

        // 获取材质
        BlockState wall = getState(world, spec.getMaterials() != null ? spec.getMaterials().getWall() : null);
        BlockState floor = getState(world, spec.getMaterials() != null ? spec.getMaterials().getFloor() : null);
        BlockState window = getState(world, spec.getMaterials() != null ? spec.getMaterials().getWindow() : null);
        BlockState roof = getState(world, spec.getMaterials() != null ? spec.getMaterials().getRoof() : null);

        // 获取特性
        boolean hasWindows = spec.getFeatures() != null && spec.getFeatures().hasWindows();
        boolean hasStairs = spec.getFeatures() != null && spec.getFeatures().hasStairs();
        
        // 获取风格选项（BuildingSpec 2.0）
        String roofType = spec.getStyleOptions() != null ? 
            spec.getStyleOptions().getRoofType() : "flat";
        double windowRatio = spec.getStyleOptions() != null ? 
            spec.getStyleOptions().getWindowRatio() : 0.3;

        // 内部楼梯旋转方向偏移（螺旋楼梯）
        BlockPos[] spiralOffsets = {
                new BlockPos(1, 0, 0),
                new BlockPos(0, 0, 1),
                new BlockPos(-1, 0, 0),
                new BlockPos(0, 0, -1)
        };

        int stairIndex = 0;
        int floorHeight = height / floors;

        // 逐层生成
        for (int y = 0; y < height; y++) {
            // 生成外墙（圆形）
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    double dist = Math.sqrt(x * x + z * z);
                    BlockPos pos = origin.add(x, y, z);

                    // 外圈：墙（厚度为 1 方块）
                    if (dist >= radius - 0.5 && dist <= radius + 0.5) {
                        // 根据 windowRatio 决定是否开窗
                        boolean shouldPlaceWindow = false;
                        if (hasWindows && y % 4 == 2) {
                            if (windowRatio >= 0.5) {
                                // 高比例：每隔 2 格开窗
                                shouldPlaceWindow = (Math.abs(x) % 2 == 0 || Math.abs(z) % 2 == 0);
                            } else if (windowRatio >= 0.3) {
                                // 中等比例：每隔 4 格开窗
                                shouldPlaceWindow = (Math.abs(x) % 4 == 0 || Math.abs(z) % 4 == 0);
                            } else {
                                // 低比例：每隔 6 格开窗
                                shouldPlaceWindow = (Math.abs(x) % 6 == 0 || Math.abs(z) % 6 == 0);
                            }
                        }
                        
                        if (shouldPlaceWindow) {
                            result.add(new PlannedBlock(pos, window));
                        } else {
                            result.add(new PlannedBlock(pos, wall));
                        }
                    }

                    // 内部填充为空气（避免打到山体）
                    if (dist < radius - 1) {
                        result.add(new PlannedBlock(pos, Blocks.AIR.getDefaultState()));
                    }
                }
            }

            // 每层楼板（除了最底层）
            if (y > 0 && y % floorHeight == 0) {
                for (int fx = -radius + 1; fx < radius; fx++) {
                    for (int fz = -radius + 1; fz < radius; fz++) {
                        double dist = Math.sqrt(fx * fx + fz * fz);
                        if (dist < radius - 1) {
                            result.add(new PlannedBlock(origin.add(fx, y, fz), floor));
                        }
                    }
                }
            }

            // 螺旋楼梯（每层 4 级，形成螺旋）
            if (hasStairs && y < height - 1) {
                BlockPos stairPos = origin.add(spiralOffsets[stairIndex % 4]);
                // 使用楼梯方块，需要设置正确的朝向
                BlockState stairState = Blocks.OAK_STAIRS.getDefaultState();
                result.add(new PlannedBlock(stairPos.add(0, y, 0), stairState));
                stairIndex++;
            }
        }

        // 顶部：根据 roofType 生成屋顶
        boolean hasRoof = spec.getFeatures() != null && spec.getFeatures().hasRoof();
        
        if (hasRoof) {
            // 从 styleOptions 获取屋顶类型（向后兼容 extra）
            String actualRoofType = roofType;
            if (actualRoofType == null || actualRoofType.isEmpty()) {
                if (spec.getExtra() != null && spec.getExtra().containsKey("roofType")) {
                    actualRoofType = String.valueOf(spec.getExtra().get("roofType"));
                } else {
                    actualRoofType = "flat"; // 默认平顶
                }
            }
            
            if ("cone".equalsIgnoreCase(actualRoofType)) {
                // 锥形屋顶：从顶部向下逐渐缩小
                int roofHeight = Math.min(radius, 5); // 屋顶高度不超过半径或 5 格
                for (int roofY = 0; roofY < roofHeight; roofY++) {
                    int currentRadius = radius - roofY;
                    for (int x = -currentRadius; x <= currentRadius; x++) {
                        for (int z = -currentRadius; z <= currentRadius; z++) {
                            double dist = Math.sqrt(x * x + z * z);
                            if (dist <= currentRadius + 0.3) {
                                result.add(new PlannedBlock(origin.add(x, height + roofY, z), roof));
                            }
                        }
                    }
                }
            } else {
                // 平顶
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        double dist = Math.sqrt(x * x + z * z);
                        if (dist <= radius + 0.3) {
                            result.add(new PlannedBlock(origin.add(x, height, z), roof));
                        }
                    }
                }
            }
        }

        String description = String.format("Tower (%s, height=%d, radius=%d, floors=%d)", 
                spec.getType(), height, radius, floors);

        return new GeneratedStructure(
                null, // owner 将在 BuildExecutionService 中设置
                origin,
                description,
                result
        );
    }

    /**
     * 将字符串 blockId 转为 BlockState（比如 "minecraft:stone_bricks"）
     */
    private BlockState getState(ServerWorld world, String id) {
        if (id == null || id.isEmpty()) {
            return Blocks.STONE.getDefaultState();
        }

        try {
            // 解析 Identifier
            Identifier identifier;
            if (id.contains(":")) {
                identifier = Identifier.of(id);
            } else {
                identifier = Identifier.of("minecraft", id);
            }

            // 从注册表获取 Block（使用静态 Registries）
            Block block = Registries.BLOCK.get(identifier);
            if (block != null) {
                return block.getDefaultState();
            }
            
            // 如果找不到，尝试使用简单的字符串匹配作为回退
            return resolveBlockFallback(id);
        } catch (Exception e) {
            // 如果解析失败，使用回退方案
            return resolveBlockFallback(id);
        }
    }

    /**
     * 回退方案：通过字符串匹配解析常用方块
     */
    private BlockState resolveBlockFallback(String material) {
        if (material == null) return Blocks.STONE.getDefaultState();
        
        String lower = material.toLowerCase();
        
        // 石头类
        if (lower.contains("stone_brick") || lower.contains("stonebrick")) {
            return Blocks.STONE_BRICKS.getDefaultState();
        }
        if (lower.contains("cobblestone")) {
            return Blocks.COBBLESTONE.getDefaultState();
        }
        if (lower.contains("stone") && !lower.contains("brick")) {
            return Blocks.STONE.getDefaultState();
        }
        
        // 砖类
        if (lower.contains("brick") && !lower.contains("stone")) {
            return Blocks.BRICKS.getDefaultState();
        }
        
        // 木头类
        if (lower.contains("dark_oak")) {
            return Blocks.DARK_OAK_PLANKS.getDefaultState();
        }
        if (lower.contains("oak_plank") || lower.contains("oak_wood")) {
            return Blocks.OAK_PLANKS.getDefaultState();
        }
        if (lower.contains("spruce")) {
            return Blocks.SPRUCE_PLANKS.getDefaultState();
        }
        if (lower.contains("birch")) {
            return Blocks.BIRCH_PLANKS.getDefaultState();
        }
        if (lower.contains("jungle")) {
            return Blocks.JUNGLE_PLANKS.getDefaultState();
        }
        if (lower.contains("acacia")) {
            return Blocks.ACACIA_PLANKS.getDefaultState();
        }
        if (lower.contains("wood") || lower.contains("plank") || lower.contains("oak")) {
            return Blocks.OAK_PLANKS.getDefaultState();
        }
        
        // 玻璃类
        if (lower.contains("glass_pane")) {
            return Blocks.GLASS_PANE.getDefaultState();
        }
        if (lower.contains("glass")) {
            return Blocks.GLASS.getDefaultState();
        }
        
        // 默认返回石头
        return Blocks.STONE.getDefaultState();
    }
}
