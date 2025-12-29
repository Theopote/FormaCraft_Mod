package com.formacraft.server.generator;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.style.profile.DetailPreferences;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
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
import java.util.Map;

/**
 * 桥梁生成器
 * 支持三种桥型：平桥、拱桥、悬索桥
 * 自动生成桥面、护栏、桥墩、主塔和拉索结构
 */
public class BridgeGenerator implements StructureGenerator {

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> blocks = new ArrayList<>();

        // 获取参数
        int width = Math.max(3, spec.getFootprint() != null ? spec.getFootprint().getWidth() : 5);
        int length = Math.max(6, spec.getFootprint() != null ? spec.getFootprint().getDepth() : 20);

        // 获取材质
        BlockState floor = getState(world, spec.getMaterials() != null ? spec.getMaterials().getFloor() : null);
        BlockState support = getState(world, spec.getMaterials() != null ? spec.getMaterials().getWall() : null);
        BlockState railing = Blocks.OAK_FENCE.getDefaultState();

        // style + palette (best-effort)
        Map<String, Object> extra = spec != null ? spec.getExtra() : null;
        BuildingStyle style = (spec != null && spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.DEFAULT;
        StyleProfile profile = (spec != null) ? StyleProfileRegistry.resolve(spec) : StyleProfileRegistry.forStyle(style);
        DetailPreferences details = profile != null ? profile.details() : null;
        String eavesProfile = details != null ? details.eavesProfile : null;
        String ornamentProfile = details != null ? details.ornamentProfile : null;
        String paletteId = (extra != null && extra.get("paletteId") != null) ? String.valueOf(extra.get("paletteId")).trim() : null;

        // 获取风格选项（BuildingSpec 2.0）
        String bridgeType = spec.getStyleOptions() != null ? 
            spec.getStyleOptions().getBridgeType() : "flat";
        
        // 向后兼容：如果 styleOptions 中没有，尝试从 extra 获取
        if (bridgeType == null || bridgeType.isEmpty() || "flat".equals(bridgeType)) {
            if (extra != null && extra.containsKey("bridgeType")) {
                bridgeType = String.valueOf(extra.get("bridgeType"));
            }
        }
        
        // 桥墩间隔（暂时仍从 extra 获取，未来可移到 styleOptions）
        int pillarInterval = extra != null ? 
            ((Number) extra.getOrDefault("pillarInterval", 6)).intValue() : 6;

        // Eaves profiles can steer railing material (neon strip etc.) when not explicitly overridden
        if ((extra == null || !extra.containsKey("railingBlock")) && eavesProfile != null) {
            String ep = eavesProfile.toLowerCase(java.util.Locale.ROOT);
            if (ep.contains("neon")) railing = Blocks.SEA_LANTERN.getDefaultState();
            else if (ep.contains("organic") || ep.contains("vine")) railing = Blocks.OAK_LEAVES.getDefaultState();
        }
        if (extra != null && extra.containsKey("railingBlock")) {
            railing = getState(world, String.valueOf(extra.get("railingBlock")));
        }

        // ---------------------------------------------------------
        // 1. 清空桥上与两侧空间
        // ---------------------------------------------------------
        for (int z = 0; z <= length; z++) {
            for (int x = -width; x <= width; x++) {
                for (int y = -2; y <= 10; y++) {
                    blocks.add(new PlannedBlock(origin.add(x, y, z), Blocks.AIR.getDefaultState()));
                }
            }
        }

        // ---------------------------------------------------------
        // 2. 桥面 Y 偏移曲线（flat / arched / suspension）
        // ---------------------------------------------------------
        int[] yOffsets = null;
        if (bridgeType != null) {
            yOffsets = computeYOffsetArray(bridgeType, length);
        }

        // ---------------------------------------------------------
        // 3. 主桥面（floor）
        // ---------------------------------------------------------
        for (int z = 0; z <= length; z++) {
            int yOffset = 0;
            if (yOffsets != null) {
                yOffset = yOffsets[z];
            }
            for (int x = -width / 2; x <= width / 2; x++) {
                BlockPos pos = origin.add(x, yOffset, z);
                BlockState st = floor;
                if (paletteId != null && !paletteId.isBlank()) {
                    long salt = (x * 31L) ^ (z * 17L) ^ (yOffset * 13L);
                    st = PaletteResolver.pick(world, paletteId, "FLOORING", pos, salt, st);
                }
                blocks.add(new PlannedBlock(pos, st));
            }
        }

        // ---------------------------------------------------------
        // 4. 护栏（两侧 fence）
        // ---------------------------------------------------------
        for (int z = 0; z <= length; z++) {
            int yOffset = 0;
            if (yOffsets != null) {
                yOffset = yOffsets[z];
            }
            BlockPos left = origin.add(-width / 2 - 1, yOffset, z);
            BlockPos right = origin.add(width / 2 + 1, yOffset, z);
            BlockState lr = railing;
            if (paletteId != null && !paletteId.isBlank()) {
                long saltL = (-31L) ^ (z * 17L) ^ (yOffset * 13L);
                long saltR = (31L) ^ (z * 17L) ^ (yOffset * 13L);
                lr = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", left, saltL, lr);
                BlockState rr = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", right, saltR, railing);
                blocks.add(new PlannedBlock(left, lr));
                blocks.add(new PlannedBlock(right, rr));
            } else {
                blocks.add(new PlannedBlock(left, lr));
                blocks.add(new PlannedBlock(right, lr));
            }
        }

        // Ornaments (best-effort): signage/banners along the bridge sides
        if (ornamentProfile != null && !ornamentProfile.isBlank()) {
            String op = ornamentProfile.trim().toLowerCase(java.util.Locale.ROOT);
            int step = Math.max(10, length / 4);
            for (int z = step; z < length; z += step) {
                int yOffset = (yOffsets != null) ? yOffsets[z] : 0;
                BlockPos left = origin.add(-width / 2 - 2, yOffset + 2, z);
                BlockPos right = origin.add(width / 2 + 2, yOffset + 2, z);
                if (op.contains("cyber") || op.contains("sign")) {
                    blocks.add(new PlannedBlock(left, Blocks.DARK_OAK_WALL_SIGN.getDefaultState()));
                    blocks.add(new PlannedBlock(right, Blocks.DARK_OAK_WALL_SIGN.getDefaultState()));
                } else if (op.contains("banner")) {
                    blocks.add(new PlannedBlock(left, Blocks.RED_WALL_BANNER.getDefaultState()));
                    blocks.add(new PlannedBlock(right, Blocks.RED_WALL_BANNER.getDefaultState()));
                }
            }
        }

        // ---------------------------------------------------------
        // 5. 桥墩（根据地形生成支撑）
        // ---------------------------------------------------------
        for (int z = 0; z <= length; z += pillarInterval) {
            int yOffset = 0;
            if (yOffsets != null) {
                yOffset = yOffsets[z];
            }
            BlockPos mid = origin.add(0, yOffset, z);
            
            // 向下延伸支撑直到遇到实心方块
            BlockPos p = mid.down();
            int maxDepth = 20; // 限制最大深度，避免无限循环
            int depth = 0;
            
            while (p.getY() > world.getBottomY() && depth < maxDepth) {
                BlockState state = world.getBlockState(p);
                if (!state.isAir() && !state.isOf(Blocks.WATER)) {
                    break;
                }
                blocks.add(new PlannedBlock(p, support));
                p = p.down();
                depth++;
            }
        }

        // ---------------------------------------------------------
        // 6. 悬桥形式：加入主塔和拉索（可选）
        // ---------------------------------------------------------
        if (bridgeType != null && bridgeType.equalsIgnoreCase("suspension")) {
            generateSuspensionCables(blocks, origin, world, width, length, support);
        }

        // ---------------------------------------------------------
        // 7. 桥头接地处理（在桥两端创建平台）
        // ---------------------------------------------------------
        BlockPos leftEnd = null;
        if (yOffsets != null) {
            leftEnd = origin.add(0, yOffsets[0], 0);
        }
        BlockPos rightEnd = null;
        if (yOffsets != null) {
            rightEnd = origin.add(0, yOffsets[length], length);
        }

        // 为桥头创建接地平台
        com.formacraft.server.terrain.TerrainOperation leftLanding =
                null;
        if (leftEnd != null) {
            leftLanding = com.formacraft.server.terrain.TerrainShaper.createBridgeLanding(
                world, leftEnd, 3, support);
        }
        com.formacraft.server.terrain.TerrainOperation rightLanding =
                null;
        if (rightEnd != null) {
            rightLanding = com.formacraft.server.terrain.TerrainShaper.createBridgeLanding(
                world, rightEnd, 3, support);
        }

        if (leftLanding != null) {
            blocks.addAll(leftLanding.getBlocks());
        }
        if (rightLanding != null) {
            blocks.addAll(rightLanding.getBlocks());
        }

        // ---------------------------------------------------------
        // 返回结构
        // ---------------------------------------------------------
        String description = String.format("Bridge (%s, %dx%d, type=%s)", 
                spec.getType(), width, length, bridgeType);

        return new GeneratedStructure(
                null, // owner 将在 BuildExecutionService 中设置
                origin,
                description,
                blocks
        );
    }

    // =============================================================================
    // 计算桥面 Y 偏移：不同桥型不同
    // =============================================================================
    private int[] computeYOffsetArray(String type, int length) {
        int[] arr = new int[length + 1];
        
        switch (type.toLowerCase()) {
            case "arched":
                // 简化抛物线 y = -a * (z - mid)^2 + height
                double mid = length / 2.0;
                double height = Math.max(2, length / 10.0);
                
                for (int i = 0; i <= length; i++) {
                    double dy = -((i - mid) * (i - mid)) / (mid * mid) * height + height;
                    arr[i] = (int) Math.round(dy);  // 上拱
                }
                break;
                
            case "suspension":
                // 简化悬链线曲线 y = a * cosh((z-mid)/b) - a
                mid = length / 2.0;
                double a = 3.0;
                double b = Math.max(1.0, length / 5.0);
                
                for (int i = 0; i <= length; i++) {
                    double dy = a * (Math.cosh((i - mid) / b) - 1);
                    arr[i] = -(int) Math.round(dy); // 下垂
                }
                break;
                
            case "flat":
            default:
                // 平桥不偏移
                for (int i = 0; i <= length; i++) {
                    arr[i] = 0;
                }
        }
        
        return arr;
    }

    // =============================================================================
    // 悬桥：简单主塔 + 拉索
    // =============================================================================
    private void generateSuspensionCables(
            List<PlannedBlock> blocks,
            BlockPos origin,
            ServerWorld world,
            int width,
            int length,
            BlockState material
    ) {
        int towerHeight = 8;
        
        // 左右各一个塔
        int[] towerZ = { length / 3, length * 2 / 3 };
        
        for (int tz : towerZ) {
            // 左塔
            for (int y = 0; y < towerHeight; y++) {
                blocks.add(new PlannedBlock(origin.add(-width / 2 - 2, y, tz), material));
            }
            
            // 右塔
            for (int y = 0; y < towerHeight; y++) {
                blocks.add(new PlannedBlock(origin.add(width / 2 + 2, y, tz), material));
            }
        }
        
        // -----------------------------------------------------
        // 生成主缆线（两侧各一条）
        // -----------------------------------------------------
        generateMainCable(blocks, origin, length, towerZ, width, towerHeight, material);
        
        // -----------------------------------------------------
        // 生成垂直吊索（连到桥面）
        // -----------------------------------------------------
        generateVerticalHangers(blocks, origin, length, width, towerHeight);
    }

    // =============================================================================
    // 主缆线：使用简化 cosh 曲线（悬链线）进行插值
    // =============================================================================
    private void generateMainCable(
            List<PlannedBlock> blocks,
            BlockPos origin,
            int length,
            int[] towerZ,
            int width,
            int towerHeight,
            BlockState cableMaterial
    ) {
        int z1 = towerZ[0];
        int z2 = towerZ[1];
        
        int mid = (z1 + z2) / 2;
        double a = 3.0;                // 曲率
        double b = Math.max(1.0, (z2 - z1) / 5.0);
        
        for (int z = z1; z <= z2; z++) {
            double dy = a * (Math.cosh((z - mid) / b) - 1);
            int y = towerHeight - (int) Math.round(dy);
            
            // 左右主缆
            blocks.add(new PlannedBlock(origin.add(-width / 2 - 2, y, z), cableMaterial));
            blocks.add(new PlannedBlock(origin.add(width / 2 + 2, y, z), cableMaterial));
        }
    }

    // =============================================================================
    // 垂直吊索：从主缆垂直连到桥面
    // =============================================================================
    private void generateVerticalHangers(
            List<PlannedBlock> blocks,
            BlockPos origin,
            int length,
            int width,
            int towerHeight
    ) {
        BlockState hangerMaterial = Blocks.IRON_BARS.getDefaultState();
        
        for (int z = 0; z <= length; z++) {
            // 找主缆的 y 值（左缆和右缆理论上相同）
            // 左缆位置：
            BlockPos cablePos = origin.add(-width / 2 - 2, 0, z);
            
            // 实际 y 值靠扫描 blocks 列表
            int cableY = findHighestNonAir(blocks, cablePos);
            
            if (cableY < 0) continue;
            
            // 桥面中央高度：寻找实际 floor 位置
            int floorY = findHighestNonAir(blocks, origin.add(0, 0, z));
            
            if (floorY < 0) continue;
            if (cableY <= floorY) continue;
            
            // 垂直放吊索
            for (int y = floorY + 1; y < cableY; y++) {
                blocks.add(new PlannedBlock(origin.add(-width / 2 - 2, y, z), hangerMaterial));
                blocks.add(new PlannedBlock(origin.add(width / 2 + 2, y, z), hangerMaterial));
            }
        }
    }

    // =============================================================================
    // 辅助方法：找出 blocks 中某坐标的最高 Y（用于定位主缆与桥面）
    // =============================================================================
    private int findHighestNonAir(List<PlannedBlock> blocks, BlockPos pos) {
        // 扫描列表（可优化为 Map，但现在性能足够）
        int maxY = -1;
        for (PlannedBlock pb : blocks) {
            if (pb.getPos().getX() == pos.getX() &&
                pb.getPos().getZ() == pos.getZ() &&
                !pb.getTargetState().isAir()) {
                maxY = Math.max(maxY, pb.getPos().getY());
            }
        }
        return maxY;
    }

    /**
     * 将字符串 blockId 转为 BlockState（比如 "minecraft:stone_bricks"）
     * 与 TowerGenerator 和 HouseGenerator 使用相同的解析逻辑
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
        if (material == null) return Blocks.STONE_BRICKS.getDefaultState();
        
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
        
        // 默认返回石砖
        return Blocks.STONE_BRICKS.getDefaultState();
    }
}
