package com.formacraft.server.generator;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.skeleton.SkeletonParams;
import com.formacraft.common.skeleton.span.SpanSuspensionPlan;
import com.formacraft.server.build.GeneratedStructure;
import com.formacraft.server.build.PlannedBlock;
import com.formacraft.server.skeleton.span.SpanSuspensionInterpreter;
import com.formacraft.server.skeleton.span.SpanSuspensionSkeleton;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;
import java.util.Map;

/**
 * GoldenGateBridgeGenerator：金门大桥（强原型）专用生成器（v1）
 *
 * v1 标志性约束（简化但可识别）：
 * - 双主塔（红色）
 * - 桥面（直线）
 * - 两侧主缆（抛物线近似）
 * - 吊索（竖向 hangers）
 */
public class GoldenGateBridgeGenerator implements StructureGenerator {

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        int span = clamp(getIntExtra(spec, "span", spec != null && spec.getFootprint() != null ? spec.getFootprint().getDepth() : 180), 40, 2000);
        int deckWidth = clamp(getIntExtra(spec, "deckWidth", spec != null && spec.getFootprint() != null ? spec.getFootprint().getWidth() : 9), 5, 63);
        if (deckWidth % 2 == 0) deckWidth += 1;
        int towerH = clamp(getIntExtra(spec, "towerHeight", 44), 18, 160);
        boolean followTerrain = getBoolExtra(spec, "followTerrain", true);
        String detail = getStringExtra(spec, "detailLevel", "aesthetic").toLowerCase();
        boolean refined = detail.contains("refined") || detail.contains("ornate");

        Direction facing = parseFacing(getStringExtra(spec, "facing", "EAST"));

        BlockState tower = getStateOrDefault(world, getStringExtra(spec, "towerBlock", "minecraft:red_terracotta"), Blocks.RED_TERRACOTTA.getDefaultState());
        BlockState deck = getStateOrDefault(world, getStringExtra(spec, "deckBlock", "minecraft:polished_andesite"), Blocks.POLISHED_ANDESITE.getDefaultState());
        BlockState cable = getStateOrDefault(world, getStringExtra(spec, "cableBlock", "minecraft:red_wool"), Blocks.RED_WOOL.getDefaultState());
        BlockState hanger = getStateOrDefault(world, getStringExtra(spec, "hangerBlock", "minecraft:iron_bars"), Blocks.IRON_BARS.getDefaultState());
        BlockState rail = getStateOrDefault(world, getStringExtra(spec, "railBlock", "minecraft:iron_bars"), Blocks.IRON_BARS.getDefaultState());
        BlockState foundation = getStateOrDefault(world, getStringExtra(spec, "foundationBlock", "minecraft:stone_bricks"), Blocks.STONE_BRICKS.getDefaultState());

        // -----------------------------
        // Skeleton-driven generation (v1)
        // -----------------------------
        SkeletonParams params = new SkeletonParams()
                .put("span", span)
                .put("deckWidth", deckWidth)
                .put("towerHeight", towerH)
                .put("followTerrain", followTerrain)
                .put("facing", facing.asString())
                .put("refined", refined);
        SpanSuspensionPlan plan = new SpanSuspensionSkeleton(world, origin).generate(params);
        List<PlannedBlock> blocks = new SpanSuspensionInterpreter(facing, tower, deck, cable, hanger, rail, foundation)
                .interpret(plan, origin, world);

        // scoring
        double shapeScore = 0.9; // 双塔+主缆
        double ratioScore = clamp01(1.0 - Math.abs((span / (double) towerH) - 4.0) * 0.08);
        double signatureScore = 0.92;
        double overall = clamp01(shapeScore * 0.4 + ratioScore * 0.3 + signatureScore * 0.3);

        String desc = String.format(
                "GoldenGateBridge (span=%d, deck=%d, towerH=%d, facing=%s, score=%.2f[shape=%.2f,ratio=%.2f,sig=%.2f])",
                span, deckWidth, towerH, facing.asString(), overall, shapeScore, ratioScore, signatureScore
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

    private static boolean getBoolExtra(BuildingSpec spec, String key, boolean def) {
        if (spec == null) return def;
        Map<String, Object> extra = spec.getExtra();
        if (extra == null) return def;
        Object v = extra.get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.isEmpty()) return def;
        return "true".equals(s) || "1".equals(s) || "yes".equals(s) || "y".equals(s);
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

    // Parabola / lerp / buildTower moved into SpanSuspensionSkeleton/Interpreter
}


