package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.skeleton.SkeletonParams;
import com.formacraft.common.skeleton.vertical.VerticalTaperPlan;
import com.formacraft.common.typology.TypologyParamResolver;
import com.formacraft.server.generation.structure.util.StructureSpecParsers;
import com.formacraft.server.skeleton.vertical.VerticalTaperInterpreter;
import com.formacraft.server.skeleton.vertical.VerticalTaperSkeleton;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lattice / X-brace observation tower (Tokyo-Tower style).
 * Reuses {@link VerticalTaperSkeleton} topology with explicit primary/secondary materials.
 */
public final class TrussTowerBuilder {

    public static final String TYPOLOGY_ID = "truss_tower";

    private TrussTowerBuilder() {}

    public static GeneratedStructure fromBuildingSpec(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        return generate(TypologyParamResolver.fromBuildingSpec(spec, TYPOLOGY_ID), origin, world, spec);
    }

    public static GeneratedStructure generate(
            Map<String, Object> params,
            BlockPos origin,
            ServerWorld world,
            BuildingSpec specHint
    ) {
        Map<String, Object> merged = new HashMap<>();
        if (params != null) {
            merged.putAll(params);
        }
        int height = clamp(intParam(merged, 45, "height", "towerHeight", "spire_height"), 16, 220);
        if (specHint != null && specHint.getHeight() > 0) {
            height = clamp(Math.max(height, specHint.getHeight()), 16, 220);
        }
        int baseWidth = intParam(merged, 0, "baseWidth", "width", "base_width");
        if (baseWidth <= 0 && specHint != null && specHint.getFootprint() != null) {
            baseWidth = Math.max(specHint.getFootprint().getWidth(), specHint.getFootprint().getDepth());
        }
        if (baseWidth <= 0) {
            baseWidth = 18;
        }
        baseWidth = clamp(baseWidth, 9, 81);
        if (baseWidth % 2 == 0) {
            baseWidth += 1;
        }
        int platformCount = clamp(intParam(merged, 2, "platformCount", "platform_count"), 1, 4);
        Object levels = merged.get("platform_levels");
        if (levels instanceof List<?> list && !list.isEmpty()) {
            platformCount = clamp(list.size(), 1, 4);
        }

        BlockState primary = resolveBlock(merged, Blocks.RED_CONCRETE.getDefaultState(),
                "material_primary", "legBlock", "material", "wall_block");
        BlockState secondary = resolveBlock(merged, Blocks.WHITE_CONCRETE.getDefaultState(),
                "material_secondary", "braceBlock", "accent_material");
        BlockState platform = resolveBlock(merged, Blocks.SMOOTH_STONE.getDefaultState(),
                "platformBlock", "floor_block", "platform");
        BlockState rail = resolveBlock(merged, Blocks.IRON_BARS.getDefaultState(),
                "railBlock", "railing_block");
        BlockState spire = resolveBlock(merged, secondary, "spireBlock", "spire_material");

        int topHalf = Math.max(1, (int) Math.round(baseWidth / 2.0 * clamp01(
                StructureSpecParsers.doubleValue(merged.get("taper_ratio"), 0.35))));
        topHalf = clamp(topHalf, 1, Math.max(1, baseWidth / 2));

        SkeletonParams sk = new SkeletonParams()
                .put("height", height)
                .put("baseWidth", baseWidth)
                .put("topHalf", topHalf)
                .put("platformCount", platformCount)
                .put("refined", true);
        VerticalTaperPlan plan = new VerticalTaperSkeleton().generate(sk);
        List<PlannedBlock> blocks = new VerticalTaperInterpreter(primary, secondary, platform, rail, spire)
                .interpret(plan, origin, world);
        String desc = String.format(Locale.ROOT,
                "TrussTower (truss_tower, h=%d, base=%d, platforms=%d)",
                height, baseWidth, platformCount);
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    private static BlockState resolveBlock(Map<String, Object> params, BlockState def, String... keys) {
        for (String key : keys) {
            Object raw = params.get(key);
            if (raw == null) continue;
            String id = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) continue;
            if (!id.contains(":")) id = "minecraft:" + id;
            try {
                var block = Registries.BLOCK.get(Identifier.of(id));
                if (block != null && block != Blocks.AIR) {
                    return block.getDefaultState();
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        return def;
    }

    private static int intParam(Map<String, Object> params, int def, String... keys) {
        for (String key : keys) {
            Object raw = params.get(key);
            if (raw == null) continue;
            Integer parsed = StructureSpecParsers.intValueOrNull(raw);
            if (parsed != null) return parsed;
        }
        return def;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clamp01(double v) {
        if (v < 0.05) return 0.05;
        if (v > 0.9) return 0.9;
        return v;
    }
}
