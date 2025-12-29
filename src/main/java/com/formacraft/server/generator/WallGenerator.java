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
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        Map<String, Object> extra = spec != null ? spec.getExtra() : null;
        String paletteId = null;
        if (extra != null) {
            Object pid = extra.get("paletteId");
            if (pid != null) paletteId = String.valueOf(pid).trim();
        }

        // StyleProfile-driven defaults (extra explicitly overrides)
        BuildingStyle style = (spec != null && spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.DEFAULT;
        StyleProfile profile = (spec != null) ? StyleProfileRegistry.resolve(spec) : StyleProfileRegistry.forStyle(style);
        DetailPreferences details = (profile != null) ? profile.details() : null;
        String eavesProfile = (details != null) ? details.eavesProfile : null;
        String ornamentProfile = (details != null) ? details.ornamentProfile : null;
        String bannerColor = (details != null) ? details.bannerColor : null;

        boolean battlements = getBool(extra, "battlements", false);
        boolean battlementsExplicit = (extra != null && extra.containsKey("battlements"));
        if (!battlementsExplicit && eavesProfile != null && eavesProfile.toLowerCase(java.util.Locale.ROOT).contains("battlement")) {
            battlements = true;
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

        // Eaves profiles on linear walls (best-effort, low intrusion)
        if (eavesProfile != null && !eavesProfile.isBlank()) {
            String ep = eavesProfile.trim().toLowerCase(java.util.Locale.ROOT);
            if (ep.contains("neon")) {
                // neon strip along top edge
                BlockState light = Blocks.SEA_LANTERN.getDefaultState();
                int y = height - 1;
                for (int z = 0; z < length; z += 5) {
                    blocks.add(new PlannedBlock(origin.add(0, y, z), light));
                    blocks.add(new PlannedBlock(origin.add(thickness - 1, y, z), light));
                }
            } else if (ep.contains("organic") || ep.contains("vine")) {
                BlockState leaf = Blocks.OAK_LEAVES.getDefaultState();
                int y = height - 1;
                for (int z = 1; z < length; z += 4) {
                    blocks.add(new PlannedBlock(origin.add(-1, y, z), leaf));
                    blocks.add(new PlannedBlock(origin.add(thickness, y, z), leaf));
                }
            }
        }

        // Battlements on top (optional)
        if (battlements) {
            BlockState crenel = Blocks.STONE_BRICK_WALL.getDefaultState();
            int y = height;
            for (int z = 0; z < length; z++) {
                if ((z & 1) == 0) {
                    blocks.add(new PlannedBlock(origin.add(0, y, z), crenel));
                    blocks.add(new PlannedBlock(origin.add(thickness - 1, y, z), crenel));
                }
            }
        }

        // Ornaments (best-effort): banners / signage
        if (ornamentProfile != null && !ornamentProfile.isBlank()) {
            String op = ornamentProfile.trim().toLowerCase(java.util.Locale.ROOT);
            if (op.contains("banner")) {
                BlockState bWest = resolveWallBannerState(bannerColor, Direction.WEST);
                BlockState bEast = resolveWallBannerState(bannerColor, Direction.EAST);
                int y = Math.max(2, height - 2);
                for (int z = 2; z < length - 2; z += 9) {
                    blocks.add(new PlannedBlock(origin.add(-1, y, z), bWest));
                    blocks.add(new PlannedBlock(origin.add(thickness, y, z), bEast));
                }
            } else if (op.contains("cyber") || op.contains("sign")) {
                BlockState signW = facing(Blocks.DARK_OAK_WALL_SIGN.getDefaultState(), Direction.WEST);
                BlockState signE = facing(Blocks.DARK_OAK_WALL_SIGN.getDefaultState(), Direction.EAST);
                int y = Math.max(2, height - 2);
                for (int z = 3; z < length - 3; z += 11) {
                    blocks.add(new PlannedBlock(origin.add(-1, y, z), signW));
                    blocks.add(new PlannedBlock(origin.add(thickness, y, z), signE));
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

    private static boolean getBool(Map<String, Object> extra, String key, boolean def) {
        if (extra == null) return def;
        Object v = extra.get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty()) return def;
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
    }

    private static BlockState resolveWallBannerState(String color, Direction facing) {
        String c = (color == null) ? "" : color.trim().toLowerCase(java.util.Locale.ROOT);
        BlockState st = switch (c) {
            case "black" -> Blocks.BLACK_WALL_BANNER.getDefaultState();
            case "white" -> Blocks.WHITE_WALL_BANNER.getDefaultState();
            case "blue" -> Blocks.BLUE_WALL_BANNER.getDefaultState();
            case "green" -> Blocks.GREEN_WALL_BANNER.getDefaultState();
            case "yellow" -> Blocks.YELLOW_WALL_BANNER.getDefaultState();
            case "purple" -> Blocks.PURPLE_WALL_BANNER.getDefaultState();
            case "cyan" -> Blocks.CYAN_WALL_BANNER.getDefaultState();
            default -> Blocks.RED_WALL_BANNER.getDefaultState();
        };
        return facing(st, facing);
    }

    private static BlockState facing(BlockState st, Direction facing) {
        if (st != null && st.contains(Properties.HORIZONTAL_FACING)) {
            return st.with(Properties.HORIZONTAL_FACING, facing);
        }
        return st;
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

