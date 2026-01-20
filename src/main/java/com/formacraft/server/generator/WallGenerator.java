package com.formacraft.server.generator;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.style.profile.DetailPreferences;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
import com.formacraft.server.build.GeneratedStructure;
import com.formacraft.server.build.PlannedBlock;
import com.formacraft.server.material.PaletteResolver;
import com.formacraft.server.terrain.TerrainAdaptationMode;
import com.formacraft.server.terrain.TerrainAdaptationResolver;
import com.formacraft.server.terrain.TerrainAdaptationSpec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;

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
        Map<String, Object> extra = spec.getExtra();
        String paletteId = null;
        if (extra != null) {
            Object pid = extra.get("paletteId");
            if (pid != null) paletteId = String.valueOf(pid).trim();
        }

        // StyleProfile-driven defaults (extra explicitly overrides)
        StyleProfile profile = StyleProfileRegistry.resolve(spec);
        DetailPreferences details = (profile != null) ? profile.details() : null;
        if ((paletteId == null || paletteId.isBlank()) && details != null && details.paletteId != null && !details.paletteId.isBlank()) {
            paletteId = details.paletteId.trim();
        }
        String eavesProfile = (details != null) ? details.eavesProfile : null;
        String ornamentProfile = (details != null) ? details.ornamentProfile : null;
        String bannerColor = (details != null) ? details.bannerColor : null;

        // Terrain adaptation (WALL): only DRAPE is applied here (short wall / fence-like).
        TerrainAdaptationSpec ta = TerrainAdaptationResolver.resolve(extra);
        boolean drape = TerrainAdaptationResolver.hasExplicit(extra) && ta.mode() == TerrainAdaptationMode.DRAPE;
        int maxStep = drape ? Math.max(1, Math.min(8, ta.drapeMaxStep())) : 0;
        int foundationDepth = drape ? Math.max(0, Math.min(16, ta.foundationDepth())) : 0;
        boolean allowWater = ta.allowWaterEdit();
        boolean allowLava = ta.allowLavaEdit();

        // Allow orientation: forward direction (defaults to SOUTH = +Z)
        Direction forward = parseFacing(extra != null ? extra.get("facing") : null);
        Direction right = forward.rotateYClockwise();

        boolean battlements = getBool(extra);
        boolean battlementsExplicit = (extra != null && extra.containsKey("battlements"));
        if (!battlementsExplicit && eavesProfile != null && eavesProfile.toLowerCase(java.util.Locale.ROOT).contains("battlement")) {
            battlements = true;
        }

        // DRAPE height field along the wall (length axis)
        int[] ground = new int[length];
        int[] baseY = new int[length];
        if (drape) {
            for (int i = 0; i < length; i++) {
                BlockPos p = origin.offset(forward, i);
                int top = world.getTopY(Heightmap.Type.WORLD_SURFACE, p.getX(), p.getZ());
                int gy = top - 1;
                ground[i] = gy;
                baseY[i] = Math.max(origin.getY(), gy);
            }
            // smooth while never below ground
            for (int it = 0; it < 3; it++) {
                for (int i = 1; i < length; i++) {
                    int prev = baseY[i - 1];
                    int cur = baseY[i];
                    if (cur > prev + maxStep) cur = prev + maxStep;
                    if (cur < prev - maxStep) cur = prev - maxStep;
                    if (cur < ground[i]) cur = ground[i];
                    baseY[i] = cur;
                }
                for (int i = length - 2; i >= 0; i--) {
                    int next = baseY[i + 1];
                    int cur = baseY[i];
                    if (cur > next + maxStep) cur = next + maxStep;
                    if (cur < next - maxStep) cur = next - maxStep;
                    if (cur < ground[i]) cur = ground[i];
                    baseY[i] = cur;
                }
            }
        }

        // 生成城墙（默认沿 forward 方向延伸；厚度沿 right）
        for (int i = 0; i < length; i++) {
            int by = drape ? baseY[i] : origin.getY();
            for (int t = 0; t < thickness; t++) {
                BlockPos col = origin.offset(forward, i).offset(right, t);

                // foundation columns (anti-gap)
                if (drape && foundationDepth > 0) {
                    for (int k = 1; k <= foundationDepth; k++) {
                        BlockPos fp = new BlockPos(col.getX(), by - k, col.getZ());
                        BlockState cur = world.getBlockState(fp);
                        boolean isAir = cur.isAir();
                        boolean isWater = cur.getBlock() == Blocks.WATER;
                        boolean isLava = cur.getBlock() == Blocks.LAVA;
                        if (!(isAir || (allowWater && isWater) || (allowLava && isLava))) break;
                        BlockState fb = wallBlock;
                        if (paletteId != null && !paletteId.isBlank()) {
                            long salt = (i * 1315423911L) ^ (t * 97531L) ^ (k * 2654435761L) ^ 0xF01DL;
                            fb = PaletteResolver.pick(world, paletteId, "WALL_FOUNDATION", fp, salt, fb);
                            fb = PaletteResolver.pick(world, paletteId, "WALL_BASE", fp, salt ^ 0xBACE01L, fb);
                        }
                        blocks.add(new PlannedBlock(fp, fb));
                    }
                }

                for (int y = 0; y < height; y++) {
                    BlockPos pos = new BlockPos(col.getX(), by + y, col.getZ());
                    BlockState st = wallBlock;
                    if (paletteId != null && !paletteId.isBlank()) {
                        long salt = (t * 31L) ^ (y * 17L) ^ (i * 13L);
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
                if (paletteId != null && !paletteId.isBlank()) {
                    light = PaletteResolver.pick(world, paletteId, "ROAD_LIGHT", origin, 0x7A1101L, light);
                    light = PaletteResolver.pick(world, paletteId, "LIGHTING", origin, 0x7A1102L, light);
                }
                for (int i = 0; i < length; i += 5) {
                    int by = drape ? baseY[i] : origin.getY();
                    int y = by + (height - 1);
                    BlockPos a = origin.offset(forward, i).offset(right, 0).withY(y);
                    BlockPos b = origin.offset(forward, i).offset(right, thickness - 1).withY(y);
                    blocks.add(new PlannedBlock(a, light));
                    blocks.add(new PlannedBlock(b, light));
                }
            } else if (ep.contains("organic") || ep.contains("vine")) {
                BlockState leaf = Blocks.OAK_LEAVES.getDefaultState();
                for (int i = 1; i < length; i += 4) {
                    int by = drape ? baseY[i] : origin.getY();
                    int y = by + (height - 1);
                    BlockPos left = origin.offset(forward, i).offset(right, -1).withY(y);
                    BlockPos rightPos = origin.offset(forward, i).offset(right, thickness).withY(y);
                    blocks.add(new PlannedBlock(left, leaf));
                    blocks.add(new PlannedBlock(rightPos, leaf));
                }
            }
        }

        // Battlements on top (optional)
        if (battlements) {
            BlockState crenel = Blocks.STONE_BRICK_WALL.getDefaultState();
            for (int i = 0; i < length; i++) {
                if ((i & 1) == 0) {
                    int by = drape ? baseY[i] : origin.getY();
                    int y = by + height;
                    BlockPos a = origin.offset(forward, i).offset(right, 0).withY(y);
                    BlockPos b = origin.offset(forward, i).offset(right, thickness - 1).withY(y);
                    blocks.add(new PlannedBlock(a, crenel));
                    blocks.add(new PlannedBlock(b, crenel));
                }
            }
        }

        // Ornaments (best-effort): banners / signage
        if (ornamentProfile != null && !ornamentProfile.isBlank()) {
            String op = ornamentProfile.trim().toLowerCase(java.util.Locale.ROOT);
            if (op.contains("banner")) {
                BlockState bWest = resolveWallBannerState(bannerColor, Direction.WEST);
                BlockState bEast = resolveWallBannerState(bannerColor, Direction.EAST);
                for (int i = 2; i < length - 2; i += 9) {
                    int by = drape ? baseY[i] : origin.getY();
                    int y = Math.max(by + 2, by + height - 2);
                    blocks.add(new PlannedBlock(origin.offset(forward, i).offset(right, -1).withY(y), bWest));
                    blocks.add(new PlannedBlock(origin.offset(forward, i).offset(right, thickness).withY(y), bEast));
                }
            } else if (op.contains("cyber") || op.contains("sign")) {
                BlockState signW = facing(Blocks.DARK_OAK_WALL_SIGN.getDefaultState(), Direction.WEST);
                BlockState signE = facing(Blocks.DARK_OAK_WALL_SIGN.getDefaultState(), Direction.EAST);
                if (paletteId != null && !paletteId.isBlank()) {
                    // Prefer palette ROAD_SIGNAGE/DECOR_DETAIL when available; keep wall sign as fallback.
                    signW = PaletteResolver.pick(world, paletteId, "ROAD_SIGNAGE", origin, 0x7A1103L, signW);
                    signW = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0x7A1104L, signW);
                    signW = facing(signW, Direction.WEST);
                    signE = PaletteResolver.pick(world, paletteId, "ROAD_SIGNAGE", origin, 0x7A1105L, signE);
                    signE = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0x7A1106L, signE);
                    signE = facing(signE, Direction.EAST);
                }
                for (int i = 3; i < length - 3; i += 11) {
                    int by = drape ? baseY[i] : origin.getY();
                    int y = Math.max(by + 2, by + height - 2);
                    blocks.add(new PlannedBlock(origin.offset(forward, i).offset(right, -1).withY(y), signW));
                    blocks.add(new PlannedBlock(origin.offset(forward, i).offset(right, thickness).withY(y), signE));
                }
            }
        }

        String description = String.format("Wall (height=%d, length=%d, thickness=%d%s)",
                height, length, thickness, drape ? ", DRAPE" : "");

        return new GeneratedStructure(
                null, // owner 将在 BuildExecutionService 中设置
                origin,
                description,
                blocks
        );
    }

    private static boolean getBool(Map<String, Object> extra) {
        if (extra == null) return false;
        Object v = extra.get("battlements");
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty()) return false;
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
    }

    private static Direction parseFacing(Object v) {
        String s = v == null ? "" : String.valueOf(v).trim().toUpperCase(java.util.Locale.ROOT);
        if (s.isEmpty()) return Direction.SOUTH;
        return switch (s) {
            case "N", "NORTH", "北", "朝北" -> Direction.NORTH;
            case "E", "EAST", "东", "朝东" -> Direction.EAST;
            case "W", "WEST", "西", "朝西" -> Direction.WEST;
            default -> Direction.SOUTH;
        };
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

