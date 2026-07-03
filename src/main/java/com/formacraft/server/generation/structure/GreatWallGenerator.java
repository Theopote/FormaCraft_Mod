package com.formacraft.server.generation.structure;

import com.formacraft.server.generation.structure.util.StructureSpecParsers;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.skeleton.SkeletonParams;
import com.formacraft.common.skeleton.linear.LinearPathPlan;
import com.formacraft.server.skeleton.linear.LinearPathSkeleton;
import com.formacraft.server.skeleton.linear.LinearWallInterpreter;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.style.profile.DetailPreferences;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
import com.formacraft.server.terrain.TerrainAdaptationMode;
import com.formacraft.server.terrain.TerrainAdaptationResolver;
import com.formacraft.server.terrain.TerrainAdaptationSpec;
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
        boolean followTerrain = getBoolExtra(spec, "followTerrain");
        boolean mixBlocks = getBoolExtra(spec, "mixWallBlocks");
        String paletteId = getStringExtra(spec, "paletteId", null);

        Direction facing = StructureSpecParsers.horizontalFacing(getStringExtra(spec, "facing", "EAST"), Direction.EAST);

        // Terrain adaptation (GreatWall): if explicitly requested, treat as DRAPE wall with smoothing + foundation.
        Map<String, Object> extra = spec != null ? spec.getExtra() : null;
        TerrainAdaptationSpec ta = TerrainAdaptationResolver.resolve(extra);
        boolean drapeExplicit = TerrainAdaptationResolver.hasExplicit(extra) && ta.mode() == TerrainAdaptationMode.DRAPE;
        int maxStep = drapeExplicit ? Math.max(1, Math.min(8, ta.drapeMaxStep())) : 0;
        int foundationDepth = drapeExplicit ? Math.max(0, Math.min(16, ta.foundationDepth())) : 0;
        boolean allowWater = ta.allowWaterEdit();
        boolean allowLava = ta.allowLavaEdit();
        if (drapeExplicit) followTerrain = true;

        BuildingStyle style = (spec != null && spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.MEDIEVAL;
        StyleProfile profile = (spec != null) ? StyleProfileRegistry.resolve(spec) : StyleProfileRegistry.forStyle(style);
        DetailPreferences details = profile != null ? profile.details() : null;
        if ((paletteId == null || paletteId.isBlank()) && details != null && details.paletteId != null && !details.paletteId.isBlank()) {
            paletteId = details.paletteId.trim();
        }
        String pWall = profile != null && profile.palette() != null ? profile.palette().wall : null;
        String pTrim = profile != null && profile.palette() != null ? profile.palette().trim : null;
        String eavesProfile = details != null ? details.eavesProfile : null;
        String ornamentProfile = details != null ? details.ornamentProfile : null;

        // extra 显式优先；否则使用 StyleProfile；最后才是硬编码默认
        BlockState wall = getStateOrDefault(world, getStringExtra(spec, "wallBlock", pWall != null ? pWall : "minecraft:stone_bricks"), Blocks.STONE_BRICKS.getDefaultState());
        BlockState accent = getStateOrDefault(world, getStringExtra(spec, "accentBlock", pTrim != null ? pTrim : "minecraft:mossy_stone_bricks"), Blocks.MOSSY_STONE_BRICKS.getDefaultState());
        BlockState walkway = getStateOrDefault(world, getStringExtra(spec, "walkwayBlock", pWall != null ? pWall : "minecraft:stone_bricks"), Blocks.STONE_BRICKS.getDefaultState());
        BlockState walkwayStairs = getStateOrDefault(world, getStringExtra(spec, "walkwayStairsBlock", null), null);
        if (walkwayStairs == null) walkwayStairs = Blocks.STONE_BRICK_STAIRS.getDefaultState();
        BlockState crenel = getStateOrDefault(world, getStringExtra(spec, "crenelBlock", "minecraft:stone_brick_wall"), Blocks.STONE_BRICK_WALL.getDefaultState());
        BlockState towerBlock = getStateOrDefault(world, getStringExtra(spec, "towerBlock", pWall != null ? pWall : "minecraft:stone_bricks"), Blocks.STONE_BRICKS.getDefaultState());
        if ((spec == null || spec.getExtra() == null || !spec.getExtra().containsKey("crenelBlock"))
                && eavesProfile != null && eavesProfile.toLowerCase(java.util.Locale.ROOT).contains("neon")) {
            crenel = Blocks.SEA_LANTERN.getDefaultState();
            if (paletteId != null && !paletteId.isBlank()) {
                // Prefer palette semantic lighting/decor for neon crenels; keep sea lantern as fallback.
                crenel = com.formacraft.server.material.PaletteResolver.pick(world, paletteId, "ROAD_LIGHT", origin, 0xC0E11L, crenel);
                crenel = com.formacraft.server.material.PaletteResolver.pick(world, paletteId, "LIGHTING", origin, 0xC0E12L, crenel);
                crenel = com.formacraft.server.material.PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xC0E13L, crenel);
            }
        }

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
                .put("maxStep", maxStep)
                .put("facing", facing.asString())
                .put("crenels", true);

        LinearPathPlan plan = new LinearPathSkeleton(world, origin).generate(params);
        BlockState foundationBlock = wall;
        if (paletteId != null && !paletteId.isBlank()) {
            // let palette choose a foundation-ish material if available
            foundationBlock = com.formacraft.server.material.PaletteResolver.pick(world, paletteId, "WALL_FOUNDATION", origin, 0xF0A11L, foundationBlock);
            foundationBlock = com.formacraft.server.material.PaletteResolver.pick(world, paletteId, "WALL_BASE", origin, 0xF0A12L, foundationBlock);
        }
        List<PlannedBlock> blocks = new LinearWallInterpreter(wall, accent, mixBlocks, walkway, walkwayStairs, crenel, towerBlock, paletteId,
                foundationDepth, foundationBlock, allowWater, allowLava)
                .interpret(plan, origin, world);

        if (ornamentProfile != null && !ornamentProfile.isBlank()) {
            String op = ornamentProfile.trim().toLowerCase(java.util.Locale.ROOT);
            if (op.contains("banner")) {
                BlockState b = Blocks.RED_WALL_BANNER.getDefaultState();
                if (paletteId != null && !paletteId.isBlank()) {
                    b = com.formacraft.server.material.PaletteResolver.pick(world, paletteId, "BANNER", origin, 0xBAA3L, b);
                }
                for (int i = 8; i < length; i += Math.max(24, towerSpacing)) {
                    BlockPos p = origin.offset(facing, i);
                    blocks.add(new PlannedBlock(p.up(Math.max(2, height - 2)), b));
                }
            }
        }

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

    private static boolean getBoolExtra(BuildingSpec spec, String key) {
        if (spec == null) return true;
        Map<String, Object> extra = spec.getExtra();
        if (extra == null) return true;
        Object v = extra.get(key);
        if (v == null) return true;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.isEmpty()) return true;
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s) || "y".equals(s)) return true;
        return !"false".equals(s) && !"0".equals(s) && !"no".equals(s) && !"n".equals(s);
    }

    private static int getIntExtra(BuildingSpec spec, String key, int def) {
        return StructureSpecParsers.extraInt(spec, key, def);
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
        return Math.min(v, 1.0);
    }
}


