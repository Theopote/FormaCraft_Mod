package com.formacraft.common.generation.structure;

import com.formacraft.common.generation.structure.util.StructureSpecParsers;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.skeleton.SkeletonParams;
import com.formacraft.common.skeleton.span.SpanSuspensionPlan;
import com.formacraft.server.build.GeneratedStructure;
import com.formacraft.server.build.PlannedBlock;
import com.formacraft.server.skeleton.span.SpanSuspensionInterpreter;
import com.formacraft.server.skeleton.span.SpanSuspensionSkeleton;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.style.profile.DetailPreferences;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
import com.formacraft.server.material.PaletteResolver;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;
import java.util.Map;

/**
 * GoldenGateBridgeGenerator：金门大桥（强原型）专用生成器（v1）
 * <p>
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
        boolean followTerrain = getBoolExtra(spec);
        String detail = getStringExtra(spec, "detailLevel", "aesthetic").toLowerCase();
        boolean refined = detail.contains("refined") || detail.contains("ornate");

        Direction facing = parseFacing(getStringExtra(spec, "facing", "EAST"));

        // Style-driven defaults (best-effort)
        BuildingStyle style = (spec != null && spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.MODERN;
        StyleProfile profile = (spec != null) ? StyleProfileRegistry.resolve(spec) : StyleProfileRegistry.forStyle(style);
        DetailPreferences details = profile != null ? profile.details() : null;
        String eavesProfile = details != null ? details.eavesProfile : null;
        String ornamentProfile = details != null ? details.ornamentProfile : null;
        String paletteId = null;
        if (spec != null && spec.getExtra() != null && spec.getExtra().get("paletteId") != null) {
            paletteId = String.valueOf(spec.getExtra().get("paletteId")).trim();
        }
        if ((paletteId == null || paletteId.isBlank()) && details != null && details.paletteId != null && !details.paletteId.isBlank()) {
            paletteId = details.paletteId.trim();
        }

        BlockState tower = getStateOrDefault(world, getStringExtra(spec, "towerBlock", "minecraft:red_terracotta"), Blocks.RED_TERRACOTTA.getDefaultState());
        BlockState deck = getStateOrDefault(world, getStringExtra(spec, "deckBlock", "minecraft:polished_andesite"), Blocks.POLISHED_ANDESITE.getDefaultState());
        BlockState cable = getStateOrDefault(world, getStringExtra(spec, "cableBlock", "minecraft:red_wool"), Blocks.RED_WOOL.getDefaultState());
        BlockState hanger = getStateOrDefault(world, getStringExtra(spec, "hangerBlock", "minecraft:iron_bars"), Blocks.IRON_BARS.getDefaultState());
        BlockState rail = getStateOrDefault(world, getStringExtra(spec, "railBlock", "minecraft:iron_bars"), Blocks.IRON_BARS.getDefaultState());
        BlockState foundation = getStateOrDefault(world, getStringExtra(spec, "foundationBlock", "minecraft:stone_bricks"), Blocks.STONE_BRICKS.getDefaultState());
        if ((spec == null || spec.getExtra() == null || !spec.getExtra().containsKey("railBlock"))
                && eavesProfile != null && eavesProfile.toLowerCase(java.util.Locale.ROOT).contains("neon")) {
            // If palette exists, prefer semantic bridge rail/lighting; otherwise keep legacy neon rail = sea lantern.
            if (paletteId != null && !paletteId.isBlank()) {
                rail = PaletteResolver.pick(world, paletteId, "BRIDGE_RAIL", origin, 0x6018L, rail);
                rail = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0x6019L, rail);
                rail = PaletteResolver.pick(world, paletteId, "LIGHTING", origin, 0x601AL, rail);
            } else {
                rail = Blocks.SEA_LANTERN.getDefaultState();
            }
        }

        // Palette overrides (optional): keep explicit per-spec block overrides as fallback.
        if (paletteId != null && !paletteId.isBlank()) {
            // Prefer structural language for suspension towers
            tower = PaletteResolver.pick(world, paletteId, "STRUCTURAL_BEAM", origin, 0x6010L, tower);
            tower = PaletteResolver.pick(world, paletteId, "FRAME", origin, 0x601AL, tower);
            tower = PaletteResolver.pick(world, paletteId, "WALL_BASE", origin, 0x601BL, tower);
            foundation = PaletteResolver.pick(world, paletteId, "WALL_FOUNDATION", origin, 0x6011L, foundation);
            deck = PaletteResolver.pick(world, paletteId, "BRIDGE_DECK", origin, 0x6012L, deck);
            deck = PaletteResolver.pick(world, paletteId, "FLOORING", origin, 0x6013L, deck);
            rail = PaletteResolver.pick(world, paletteId, "BRIDGE_RAIL", origin, 0x6014L, rail);
            rail = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0x6015L, rail);

            // Prefer chain/steel-ish for cables when not explicitly overridden by spec.extra keys.
            if (spec == null || spec.getExtra() == null || !spec.getExtra().containsKey("cableBlock")) {
                BlockState chain = getStateOrDefault(world, "minecraft:chain", Blocks.IRON_BARS.getDefaultState());
                cable = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0x6016L, chain);
            } else {
                cable = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0x6016L, cable);
            }
            if (spec == null || spec.getExtra() == null || !spec.getExtra().containsKey("hangerBlock")) {
                hanger = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0x6017L, Blocks.IRON_BARS.getDefaultState());
            } else {
                hanger = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0x6017L, hanger);
            }
        }

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

        // Ornaments (best-effort, low intrusion)
        if (ornamentProfile != null && !ornamentProfile.isBlank()) {
            String op = ornamentProfile.trim().toLowerCase(java.util.Locale.ROOT);
            if (op.contains("cyber") || op.contains("sign")) {
                BlockState sign = Blocks.DARK_OAK_WALL_SIGN.getDefaultState();
                if (paletteId != null && !paletteId.isBlank()) {
                    sign = PaletteResolver.pick(world, paletteId, "ROAD_SIGNAGE", origin, 0x6020L, sign);
                    sign = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0x6021L, sign);
                }
                int y = 3;
                for (int i = 12; i < span; i += 48) {
                    BlockPos p = origin.offset(facing, i);
                    blocks.add(new PlannedBlock(p.up(y), sign));
                }
            } else if (op.contains("organic") || op.contains("vine")) {
                BlockState leaf = Blocks.OAK_LEAVES.getDefaultState();
                if (paletteId != null && !paletteId.isBlank()) {
                    leaf = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0x6022L, leaf);
                }
                for (int i = 16; i < span; i += 40) {
                    BlockPos p = origin.offset(facing, i);
                    blocks.add(new PlannedBlock(p.up(2).east(), leaf));
                    blocks.add(new PlannedBlock(p.up(2).west(), leaf));
                }
            }
        }

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
            case "W", "WEST", "西", "朝西" -> Direction.WEST;
            default -> Direction.EAST;
        };
    }

    private static int getIntExtra(BuildingSpec spec, String key, int def) {
        return StructureSpecParsers.extraInt(spec, key, def);
    }

    private static boolean getBoolExtra(BuildingSpec spec) {
        if (spec == null) return true;
        Map<String, Object> extra = spec.getExtra();
        if (extra == null) return true;
        Object v = extra.get("followTerrain");
        if (v == null) return true;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.isEmpty()) return true;
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
        return Math.min(v, 1.0);
    }

    // Parabola / lerp / buildTower moved into SpanSuspensionSkeleton/Interpreter
}


