package com.formacraft.server.generator;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.server.build.GeneratedStructure;
import com.formacraft.server.build.PlannedBlock;
import com.formacraft.common.skeleton.SkeletonParams;
import com.formacraft.common.skeleton.linear.LinearPathPlan;
import com.formacraft.server.skeleton.linear.LinearPathSkeleton;
import com.formacraft.server.skeleton.linear.LinearWallInterpreter;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;
import java.util.Map;

/**
 * GreatWallGenerator：长城（强原型）专用生成器（v1）
 *
 * v1 目标：
 * - 线性结构：从 anchor 沿 facing 延伸 length
 * - 城墙可行走：顶面 walkway
 * - 垛口（crenels）提供轮廓识别
 * - 定距烽火台/小城楼（towerSpacing）
 * - （可选）简单贴地形：按世界地表高度微调 y
 */
public class GreatWallGenerator implements StructureGenerator {

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        int length = clamp(getIntExtra(spec, "wallLength", spec != null && spec.getFootprint() != null ? spec.getFootprint().getDepth() : 120), 20, 2000);
        int height = clamp(getIntExtra(spec, "wallHeight", spec != null ? spec.getHeight() : 10), 5, 80);
        int thickness = clamp(getIntExtra(spec, "wallThickness", spec != null && spec.getFootprint() != null ? Math.max(3, spec.getFootprint().getWidth()) : 5), 3, 21);

        int towerSpacing = clamp(getIntExtra(spec, "towerSpacing", 48), 16, 256);
        boolean followTerrain = getBoolExtra(spec, "followTerrain", true);
        boolean mixBlocks = getBoolExtra(spec, "mixWallBlocks", true);
        String paletteId = getStringExtra(spec, "paletteId", null);

        Direction facing = parseFacing(getStringExtra(spec, "facing", "EAST"));

        BuildingStyle style = (spec != null && spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.MEDIEVAL;
        StyleProfile profile = (spec != null) ? StyleProfileRegistry.resolve(spec) : StyleProfileRegistry.forStyle(style);
        String pWall = profile != null && profile.palette() != null ? profile.palette().wall : null;
        String pTrim = profile != null && profile.palette() != null ? profile.palette().trim : null;

        // extra 显式优先；否则使用 StyleProfile；最后才是硬编码默认
        BlockState wall = getStateOrDefault(world, getStringExtra(spec, "wallBlock", pWall != null ? pWall : "minecraft:stone_bricks"), Blocks.STONE_BRICKS.getDefaultState());
        BlockState accent = getStateOrDefault(world, getStringExtra(spec, "accentBlock", pTrim != null ? pTrim : "minecraft:mossy_stone_bricks"), Blocks.MOSSY_STONE_BRICKS.getDefaultState());
        BlockState walkway = getStateOrDefault(world, getStringExtra(spec, "walkwayBlock", pWall != null ? pWall : "minecraft:stone_bricks"), Blocks.STONE_BRICKS.getDefaultState());
        BlockState crenel = getStateOrDefault(world, getStringExtra(spec, "crenelBlock", "minecraft:stone_brick_wall"), Blocks.STONE_BRICK_WALL.getDefaultState());
        BlockState towerBlock = getStateOrDefault(world, getStringExtra(spec, "towerBlock", pWall != null ? pWall : "minecraft:stone_bricks"), Blocks.STONE_BRICKS.getDefaultState());

        // -----------------------------
        // Skeleton-driven generation (v1)
        // -----------------------------
        // Topology = LINEAR_PATH, Interpreter = LinearWallInterpreter
        SkeletonParams params = new SkeletonParams()
                .put("length", length)
                .put("height", height)
                .put("thickness", thickness)
                .put("towerSpacing", towerSpacing)
                .put("followTerrain", followTerrain)
                .put("facing", facing.asString())
                .put("crenels", true);

        LinearPathPlan plan = new LinearPathSkeleton(world, origin).generate(params);
        List<PlannedBlock> blocks = new LinearWallInterpreter(wall, accent, mixBlocks, walkway, crenel, towerBlock, paletteId)
                .interpret(plan, origin, world);

        // scoring: linear+crenels+towers
        double shapeScore = 0.9;
        double ratioScore = clamp01(1.0 - Math.abs((height / (double) thickness) - 2.0) * 0.15);
        double signatureScore = clamp01(0.7 + Math.min(0.3, (length / (double) towerSpacing) * 0.12));
        double overall = clamp01(shapeScore * 0.4 + ratioScore * 0.3 + signatureScore * 0.3);

        String desc = String.format(
                "GreatWall (len=%d, h=%d, thick=%d, facing=%s, towersEvery=%d, score=%.2f[shape=%.2f,ratio=%.2f,sig=%.2f])",
                length, height, thickness, facing.asString(), towerSpacing, overall, shapeScore, ratioScore, signatureScore
        );
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    private static Direction parseFacing(String s) {
        String v = (s == null ? "" : s).trim().toUpperCase();
        return switch (v) {
            case "N", "NORTH", "北", "朝北" -> Direction.NORTH;
            case "S", "SOUTH", "南", "朝南" -> Direction.SOUTH;
            case "E", "EAST", "东", "朝东" -> Direction.EAST;
            case "W", "WEST", "西", "朝西" -> Direction.WEST;
            default -> Direction.EAST;
        };
    }

    private static boolean getBoolExtra(BuildingSpec spec, String key, boolean def) {
        if (spec == null) return def;
        Map<String, Object> extra = spec.getExtra();
        if (extra == null) return def;
        Object v = extra.get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.isEmpty()) return def;
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s) || "y".equals(s)) return true;
        if ("false".equals(s) || "0".equals(s) || "no".equals(s) || "n".equals(s)) return false;
        return def;
    }

    private static int getIntExtra(BuildingSpec spec, String key, int def) {
        if (spec == null) return def;
        Map<String, Object> extra = spec.getExtra();
        if (extra == null) return def;
        Object v = extra.get(key);
        if (v == null) return def;
        try {
            if (v instanceof Number n) return n.intValue();
            String s = String.valueOf(v).trim();
            return s.isEmpty() ? def : Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static String getStringExtra(BuildingSpec spec, String key, String def) {
        if (spec == null) return def;
        Map<String, Object> extra = spec.getExtra();
        if (extra == null) return def;
        Object v = extra.get(key);
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }

    private BlockState getStateOrDefault(ServerWorld world, String id, BlockState defaultState) {
        if (id == null || id.isBlank()) return defaultState;
        try {
            var ident = net.minecraft.util.Identifier.tryParse(id);
            if (ident == null) return defaultState;
            return net.minecraft.registry.Registries.BLOCK.get(ident).getDefaultState();
        } catch (Exception e) {
            return defaultState;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}


