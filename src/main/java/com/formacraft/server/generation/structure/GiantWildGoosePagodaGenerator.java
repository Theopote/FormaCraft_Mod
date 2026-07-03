package com.formacraft.server.generation.structure;

import com.formacraft.server.generation.structure.util.StructureSpecParsers;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.skeleton.SkeletonParams;
import com.formacraft.common.skeleton.stack.VerticalStackPlan;
import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.server.material.PaletteResolver;
import com.formacraft.server.skeleton.stack.VerticalStackInterpreter;
import com.formacraft.server.skeleton.stack.VerticalStackSkeleton;
import com.formacraft.common.style.profile.DetailPreferences;
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
 * GiantWildGoosePagodaGenerator：大慈恩寺·大雁塔（强原型）专用生成器（v1）
 *
 * v1 识别性目标：
 * - 方形塔身，逐层收分（密檐塔意象）
 * - 每层檐口（slab/trim）
 * - 顶刹（spike）
 */
public class GiantWildGoosePagodaGenerator implements StructureGenerator {

    private static final FcaLog LOG = FcaLog.of("GiantWildGoosePagodaGenerator");

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        int levels = clamp(getIntExtra(spec, "levels", spec != null ? Math.max(1, spec.getFloors()) : 7), 3, 15);
        int height = clamp(getIntExtra(spec, "towerHeight", spec != null ? spec.getHeight() : (levels * 5 + 6)), 18, 200);

        int baseW = getIntExtra(spec, "baseWidth", (spec != null ? Math.max(spec.getWidth(), spec.getDepth()) : 0));
        if (baseW <= 0) baseW = 17;
        baseW = clamp(baseW, 9, 51);
        if (baseW % 2 == 0) baseW += 1;

        String detail = getStringExtra(spec, "detailLevel", "aesthetic").toLowerCase();
        boolean refined = detail.contains("refined") || detail.contains("ornate");

        // style + palette (best-effort)
        BuildingStyle style = (spec != null && spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.ASIAN;
        StyleProfile profile = (spec != null) ? StyleProfileRegistry.resolve(spec) : StyleProfileRegistry.forStyle(style);
        DetailPreferences details = profile != null ? profile.details() : null;
        String paletteId = getStringExtra(spec, "paletteId", null);
        if ((paletteId == null || paletteId.isBlank()) && details != null && details.paletteId != null && !details.paletteId.isBlank()) {
            paletteId = details.paletteId.trim();
        }

        // Layout IR: extra.layout.entranceFacing overrides legacy extra.facing
        Direction facing = resolveFacing(spec);

        BlockState body = getStateOrDefault(world, getStringExtra(spec, "bodyBlock", "minecraft:bricks"), Blocks.BRICKS.getDefaultState());
        BlockState trim = getStateOrDefault(world, getStringExtra(spec, "trimBlock", "minecraft:stone_brick_slab"), Blocks.STONE_BRICK_SLAB.getDefaultState());
        BlockState eave = getStateOrDefault(world, getStringExtra(spec, "eaveBlock", "minecraft:brick_slab"), Blocks.BRICK_SLAB.getDefaultState());
        BlockState accent = getStateOrDefault(world, getStringExtra(spec, "accentBlock", "minecraft:chiseled_stone_bricks"), Blocks.CHISELED_STONE_BRICKS.getDefaultState());

        // Palette overrides (optional): only when caller did NOT explicitly set that material id.
        if (paletteId != null && !paletteId.isBlank()) {
            // Body: WALL_BASE (stone/brick massing)
            if (getStringExtra(spec, "bodyBlock", "").isBlank()) {
                body = PaletteResolver.pick(world, paletteId, "WALL_BASE", origin, 0xD4C1A001L, body);
            }
            // Trims/eaves: DECOR_DETAIL + FLOOR_SLAB (slabs show up a lot here)
            if (getStringExtra(spec, "trimBlock", "").isBlank()) {
                trim = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xD4C1A002L, trim);
                trim = PaletteResolver.pick(world, paletteId, "FLOOR_SLAB", origin, 0xD4C1A003L, trim);
            }
            if (getStringExtra(spec, "eaveBlock", "").isBlank()) {
                eave = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xD4C1A004L, eave);
                eave = PaletteResolver.pick(world, paletteId, "FLOOR_SLAB", origin, 0xD4C1A005L, eave);
            }
            if (getStringExtra(spec, "accentBlock", "").isBlank()) {
                accent = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xD4C1A006L, accent);
            }
        }

        // -----------------------------
        // Skeleton-driven generation (v1)
        // -----------------------------
        SkeletonParams params = new SkeletonParams()
                .put("levels", levels)
                .put("height", height)
                .put("baseWidth", baseW)
                .put("refined", refined)
                .put("facing", facing.asString());

        VerticalStackPlan plan = new VerticalStackSkeleton().generate(params);
        boolean hollow = true;
        List<PlannedBlock> blocks = new VerticalStackInterpreter(body, trim, eave, accent, hollow)
                .interpret(plan, origin, world);

        // scoring: tiers + eaves
        double shapeScore = 0.9;
        double ratioScore = clamp01(1.0 - Math.abs((height / (double) baseW) - 3.0) * 0.12);
        double signatureScore = clamp01(0.75 + Math.min(0.25, levels / 10.0));
        double overall = clamp01(shapeScore * 0.4 + ratioScore * 0.3 + signatureScore * 0.3);

        String desc = String.format(
                "GiantWildGoosePagoda (ASIAN, levels=%d, h=%d, base=%d, facing=%s, score=%.2f[shape=%.2f,ratio=%.2f,sig=%.2f])",
                levels, height, baseW, facing.asString(), overall, shapeScore, ratioScore, signatureScore
        );
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    // ringSquare / carveDoor moved to VerticalStackInterpreter

    private static Direction resolveFacing(BuildingSpec spec) {
        return StructureSpecParsers.resolveEntranceFacing(spec, Direction.SOUTH);
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
        if (v > 1.0) return 1.0;
        return v;
    }
}


