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
 * 房屋生成器
 * 支持矩形建筑、外墙/内墙、门、窗户、多层楼结构、地板、屋顶
 * 可扩展 features（如阳台、柱子、装饰）
 */
public class HouseGenerator implements StructureGenerator {

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> blocks = new ArrayList<>();

        // 获取参数
        int width = Math.max(6, spec.getFootprint() != null ? spec.getFootprint().getWidth() : 8);
        int depth = Math.max(6, spec.getFootprint() != null ? spec.getFootprint().getDepth() : 6);
        int height = Math.max(4, spec.getHeight());
        int floors = Math.max(1, spec.getFloors());

        // 获取材质
        BlockState wall = getState(world, spec.getMaterials() != null ? spec.getMaterials().getWall() : null);
        BlockState floor = getState(world, spec.getMaterials() != null ? spec.getMaterials().getFloor() : null);
        BlockState window = getState(world, spec.getMaterials() != null ? spec.getMaterials().getWindow() : null);
        BlockState roof = getState(world, spec.getMaterials() != null ? spec.getMaterials().getRoof() : null);

        // 获取特性
        boolean hasWindows = spec.getFeatures() != null && spec.getFeatures().hasWindows();
        boolean hasDoor = spec.getFeatures() != null && spec.getFeatures().hasDoor();
        boolean hasRoof = spec.getFeatures() != null && spec.getFeatures().hasRoof();
        
        // 获取风格选项（BuildingSpec 2.0）
        String doorStyle = spec.getStyleOptions() != null ? 
            spec.getStyleOptions().getDoorStyle() : "single";
        String roofType = spec.getStyleOptions() != null ? 
            spec.getStyleOptions().getRoofType() : "flat";
        double windowRatio = spec.getStyleOptions() != null ? 
            spec.getStyleOptions().getWindowRatio() : 0.3;
        // TODO: 未来实现 wallPattern（uniform/striped/gradient/random）
        // String wallPattern = spec.getStyleOptions() != null ? 
        //     spec.getStyleOptions().getWallPattern() : "uniform";

        // -------------------------------------
        // 1. 清空内部空间（避免房屋和山体重叠）
        // -------------------------------------
        for (int x = -1; x <= width + 1; x++) {
            for (int z = -1; z <= depth + 1; z++) {
                for (int y = 0; y <= height + 6; y++) {
                    blocks.add(new PlannedBlock(origin.add(x, y, z), Blocks.AIR.getDefaultState()));
                }
            }
        }

        // -------------------------------------
        // 2. 生成墙体
        // -------------------------------------
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    boolean isEdgeX = (x == 0 || x == width - 1);
                    boolean isEdgeZ = (z == 0 || z == depth - 1);

                    // 外墙条件
                    if (isEdgeX || isEdgeZ) {
                        BlockPos pos = origin.add(x, y, z);

                        // 门位置逻辑（根据 doorStyle）
                        boolean isDoor = hasDoor && (z == 0) &&
                                ((doorStyle.equalsIgnoreCase("double") &&
                                        (x == width / 2 || x == width / 2 - 1)) ||
                                        (doorStyle.equalsIgnoreCase("single") &&
                                                x == width / 2) ||
                                        (doorStyle.equalsIgnoreCase("arched") &&
                                                (x == width / 2 || x == width / 2 - 1))) &&
                                (y == 0 || y == 1);

                        if (isDoor) {
                            blocks.add(new PlannedBlock(pos, Blocks.AIR.getDefaultState()));
                            continue;
                        }

                        // 窗户逻辑（根据 windowRatio）
                        if (hasWindows && y >= 1 && y <= 2) {
                            // 根据 windowRatio 决定是否开窗
                            boolean shouldPlaceWindow = isShouldPlaceWindow(windowRatio, x, z);

                            // 避免在门的位置开窗
                            if (shouldPlaceWindow && 
                                !(z == 0 && (x == width / 2 || x == width / 2 - 1))) {
                                blocks.add(new PlannedBlock(pos, window));
                                continue;
                            }
                        }

                        // 普通墙
                        blocks.add(new PlannedBlock(pos, wall));
                    }
                }
            }
        }

        // -------------------------------------
        // 3. 地板（每层一层）
        // -------------------------------------
        int floorHeight = height / floors;
        for (int f = 0; f < floors; f++) {
            int y = f * floorHeight;

            for (int x = 1; x < width - 1; x++) {
                for (int z = 1; z < depth - 1; z++) {
                    blocks.add(new PlannedBlock(origin.add(x, y, z), floor));
                }
            }
        }

        // -------------------------------------
        // 4. 屋顶（根据 roofType）
        // -------------------------------------
        if (hasRoof) {
            // 从 styleOptions 获取屋顶类型（向后兼容 extra）
            String actualRoofType = roofType;
            if (actualRoofType == null || actualRoofType.isEmpty()) {
                if (spec.getExtra() != null && spec.getExtra().containsKey("roofType")) {
                    actualRoofType = String.valueOf(spec.getExtra().get("roofType"));
                } else {
                    actualRoofType = "gable"; // 默认双坡
                }
            }
            
            if ("gable".equalsIgnoreCase(actualRoofType)) {
                // 双坡屋顶（gable roof）
                int roofHeight = Math.min(width / 2 + 2, 8); // 限制屋顶高度

                // 正面与背面方向（沿 X 形成双坡）
                for (int i = 0; i < roofHeight; i++) {
                    int rightX = width - 1 - i;

                    if (i > rightX) break;

                    for (int z = 0; z < depth; z++) {
                        blocks.add(new PlannedBlock(origin.add(i, height + i, z), roof));
                        blocks.add(new PlannedBlock(origin.add(rightX, height + i, z), roof));
                    }
                }

                // 封顶（最上层 ridge）
                int ridgeY = height + roofHeight;
                int midX = width / 2;

                for (int z = 0; z < depth; z++) {
                    blocks.add(new PlannedBlock(origin.add(midX, ridgeY, z), roof));
                }
            } else {
                // 平顶
                for (int x = 0; x < width; x++) {
                    for (int z = 0; z < depth; z++) {
                        blocks.add(new PlannedBlock(origin.add(x, height, z), roof));
                    }
                }
            }
        }

        // -------------------------------------
        // 5. 返回结构对象
        // -------------------------------------
        String description = String.format("House (%s, %dx%dx%d, floors=%d)", 
                spec.getType(), width, height, depth, floors);

        return new GeneratedStructure(
                null, // owner 将在 BuildExecutionService 中设置
                origin,
                description,
                blocks
        );
    }

    private static boolean isShouldPlaceWindow(double windowRatio, int x, int z) {
        boolean shouldPlaceWindow;
        if (windowRatio >= 0.5) {
            // 高比例：每隔 2 格开窗
            shouldPlaceWindow = (x % 2 == 0 || z % 2 == 0);
        } else if (windowRatio >= 0.3) {
            // 中等比例：每隔 3 格开窗
            shouldPlaceWindow = (x % 3 == 0 || z % 3 == 0);
        } else {
            // 低比例：每隔 4 格开窗
            shouldPlaceWindow = (x % 4 == 0 || z % 4 == 0);
        }
        return shouldPlaceWindow;
    }

    /**
     * 将字符串 blockId 转为 BlockState（比如 "minecraft:stone_bricks"）
     * 与 TowerGenerator 使用相同的解析逻辑
     */
    private BlockState getState(ServerWorld world, String id) {
        if (id == null || id.isEmpty()) {
            return Blocks.OAK_PLANKS.getDefaultState();
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
            return block.getDefaultState();

            // 如果找不到，尝试使用简单的字符串匹配作为回退
        } catch (Exception e) {
            // 如果解析失败，使用回退方案
            return resolveBlockFallback(id);
        }
    }

    /**
     * 回退方案：通过字符串匹配解析常用方块
     */
    private BlockState resolveBlockFallback(String material) {
        if (material == null) return Blocks.OAK_PLANKS.getDefaultState();
        
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
        
        // 默认返回橡木木板
        return Blocks.OAK_PLANKS.getDefaultState();
    }
}

