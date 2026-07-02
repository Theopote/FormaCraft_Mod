package com.formacraft.common.generation.structure;

import com.formacraft.common.generation.structure.util.StructureSpecParsers;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.skeleton.SkeletonParams;
import com.formacraft.common.skeleton.vertical.VerticalTaperPlan;
import com.formacraft.server.build.GeneratedStructure;
import com.formacraft.server.build.PlannedBlock;
import com.formacraft.server.material.PaletteResolver;
import com.formacraft.server.skeleton.vertical.VerticalTaperInterpreter;
import com.formacraft.server.skeleton.vertical.VerticalTaperSkeleton;
import com.formacraft.common.style.profile.DetailPreferences;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * EiffelTowerGenerator：埃菲尔铁塔（强原型）专用生成器（v1）
 *
 * 设计目标：
 * - 保证“轮廓识别性”：四腿收分 + 两层平台 + 顶部尖塔
 * - 参数化：高度、底座宽度、平台数、细节等级（aesthetic/refined）
 * - 允许材质覆写，但不改变拓扑/标志性构件
 */
public class EiffelTowerGenerator implements StructureGenerator {

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        int height = clamp(getIntExtra(spec, "towerHeight", spec != null ? spec.getHeight() : 60), 24, 220);

        int baseWidth = getIntExtra(spec, "baseWidth", (spec != null ? Math.max(spec.getWidth(), spec.getDepth()) : 0));
        if (baseWidth <= 0) baseWidth = 27;
        baseWidth = clamp(baseWidth, 15, 81);
        if (baseWidth % 2 == 0) baseWidth += 1;

        int platformCount = clamp(getIntExtra(spec, "platformCount", 2), 1, 3);
        String detail = getStringExtra(spec, "detailLevel", "aesthetic").toLowerCase();
        boolean refined = detail.contains("refined") || detail.contains("ornate");

        // Style-driven details (best-effort; explicit extra block IDs override via extra keys)
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

        BlockState leg = getStateOrDefault(world, getStringExtra(spec, "legBlock", "minecraft:iron_block"), Blocks.IRON_BLOCK.getDefaultState());
        BlockState brace = getStateOrDefault(world, getStringExtra(spec, "braceBlock", "minecraft:iron_bars"), Blocks.IRON_BARS.getDefaultState());
        BlockState platform = getStateOrDefault(world, getStringExtra(spec, "platformBlock", "minecraft:smooth_stone"), Blocks.SMOOTH_STONE.getDefaultState());
        BlockState rail = getStateOrDefault(world, getStringExtra(spec, "railBlock", "minecraft:iron_bars"), Blocks.IRON_BARS.getDefaultState());
        BlockState spire = getStateOrDefault(world, getStringExtra(spec, "spireBlock", "minecraft:iron_block"), Blocks.IRON_BLOCK.getDefaultState());
        // neon eaves: if railBlock not explicitly set, brighten rails
        if ((spec == null || spec.getExtra() == null || !spec.getExtra().containsKey("railBlock"))
                && eavesProfile != null && eavesProfile.toLowerCase(java.util.Locale.ROOT).contains("neon")) {
            rail = Blocks.SEA_LANTERN.getDefaultState();
        }

        // Palette overrides (optional): keep explicit per-spec block overrides as fallback.
        if (paletteId != null && !paletteId.isBlank()) {
            leg = PaletteResolver.pick(world, paletteId, "STRUCTURAL_BEAM", origin, 0xE1FF01L, leg);
            brace = PaletteResolver.pick(world, paletteId, "STRUCTURAL_BEAM", origin, 0xE1FF02L, brace);
            brace = PaletteResolver.pick(world, paletteId, "FRAME", origin, 0xE1FF06L, brace);
            brace = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xE1FF07L, brace);
            platform = PaletteResolver.pick(world, paletteId, "FLOORING", origin, 0xE1FF03L, platform);
            rail = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xE1FF04L, rail);
            spire = PaletteResolver.pick(world, paletteId, "STRUCTURAL_BEAM", origin, 0xE1FF05L, spire);
            spire = PaletteResolver.pick(world, paletteId, "FRAME", origin, 0xE1FF08L, spire);
            spire = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xE1FF09L, spire);
        }

        // -----------------------------
        // Skeleton-driven generation (v1)
        // -----------------------------
        SkeletonParams params = new SkeletonParams()
                .put("height", height)
                .put("baseWidth", baseWidth)
                .put("topHalf", 2)
                .put("platformCount", platformCount)
                .put("refined", refined);
        VerticalTaperPlan plan = new VerticalTaperSkeleton().generate(params);
        List<PlannedBlock> blocks = new VerticalTaperInterpreter(leg, brace, platform, rail, spire)
                .interpret(plan, origin, world);

        // Ornaments (best-effort, low intrusion)
        if (ornamentProfile != null && !ornamentProfile.isBlank()) {
            String op = ornamentProfile.trim().toLowerCase(java.util.Locale.ROOT);
            if (op.contains("cyber") || op.contains("sign")) {
                // a small "signage" ring near the mid platform
                int y = Math.max(6, height / 2);
                BlockState plate = Blocks.CYAN_STAINED_GLASS.getDefaultState();
                int r = Math.max(3, baseWidth / 4);
                for (int x = -r; x <= r; x += r * 2) {
                    blocks.add(new PlannedBlock(origin.add(x, y, 0), plate));
                    blocks.add(new PlannedBlock(origin.add(0, y, x), plate));
                }
            } else if (op.contains("steam") || op.contains("pipe")) {
                // a couple of copper pipe accents near the base
                BlockState pipe = Blocks.COPPER_BLOCK.getDefaultState();
                for (int yy = 2; yy <= 6; yy++) {
                    blocks.add(new PlannedBlock(origin.add(baseWidth / 2, yy, 0), pipe));
                    blocks.add(new PlannedBlock(origin.add(-baseWidth / 2, yy, 0), pipe));
                }
            } else if (op.contains("organic") || op.contains("vine")) {
                BlockState leaf = Blocks.OAK_LEAVES.getDefaultState();
                int y = Math.max(6, height / 3);
                blocks.add(new PlannedBlock(origin.add(0, y, baseWidth / 2), leaf));
                blocks.add(new PlannedBlock(origin.add(0, y, -baseWidth / 2), leaf));
            }
        }

        // v1 scoring：提供“像不像”的可观测指标
        String desc = getString(height, baseWidth, platformCount);
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    private static @NotNull String getString(int height, int baseWidth, int platformCount) {
        double shapeScore = 0.9;      // 四腿收分 + 平台存在 -> 高
        double ratioScore = clamp01(1.0 - Math.abs((height / (double) baseWidth) - 2.6) * 0.18); // 约 2.6 更像（MC 尺度经验值）
        double signatureScore = (platformCount >= 2 ? 0.92 : 0.78);
        double overall = clamp01(shapeScore * 0.4 + ratioScore * 0.3 + signatureScore * 0.3);

        return String.format(
                "EiffelTower (MODERN, h=%d, base=%d, platforms=%d, score=%.2f[shape=%.2f,ratio=%.2f,sig=%.2f])",
                height, baseWidth, platformCount, overall, shapeScore, ratioScore, signatureScore
        );
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


