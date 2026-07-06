package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.style.profile.DetailPreferences;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
import com.formacraft.common.typology.TypologyParamResolver;
import com.formacraft.common.typology.TypologyParams;
import com.formacraft.common.typology.detail.ChineseTypologyDetailUtil;
import com.formacraft.server.generation.structure.util.StructureSpecParsers;
import com.formacraft.server.material.PaletteResolver;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parametric dense-eaves brick pagoda builder (typology: {@code dense_eaves_pagoda}).
 */
public final class DenseEavesPagodaBuilder {

    public static final String TYPOLOGY_ID = "dense_eaves_pagoda";

    private DenseEavesPagodaBuilder() {}

    public static GeneratedStructure fromBuildingSpec(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        return generate(TypologyParamResolver.fromBuildingSpec(spec, TYPOLOGY_ID), origin, world, spec);
    }

    public static GeneratedStructure generate(
            Map<String, Object> params,
            BlockPos origin,
            ServerWorld world,
            BuildingSpec specHint
    ) {
        TypologyParams p = new TypologyParams(params);
        int levels = p.intVal("levels", 13, 7, 15);
        int height = p.intVal("height", "towerHeight", 47, 28, 120);
        int baseW = p.intVal("baseWidth", 10, 7, 21);
        if (baseW % 2 == 0) baseW += 1;

        String detail = p.strVal("detailLevel", "refined").toLowerCase();
        boolean refined = detail.contains("refined") || detail.contains("ornate");

        BuildingStyle style = (specHint != null && specHint.getStyle() != null)
                ? specHint.getStyle() : BuildingStyle.ASIAN;
        StyleProfile profile = (specHint != null)
                ? StyleProfileRegistry.resolve(specHint)
                : StyleProfileRegistry.forStyle(style);
        DetailPreferences details = profile != null ? profile.details() : null;

        String paletteId = p.strVal("paletteId", null);
        if ((paletteId == null || paletteId.isBlank()) && details != null
                && details.paletteId != null && !details.paletteId.isBlank()) {
            paletteId = details.paletteId.trim();
        }

        Direction facing = specHint != null
                ? StructureSpecParsers.resolveEntranceFacing(specHint, Direction.SOUTH)
                : facingFromParam(p.strVal("facing", "SOUTH"));

        BlockState body = blockOrDefault(world, p.strVal("bodyBlock", "minecraft:bricks"), Blocks.BRICKS.getDefaultState());
        BlockState trim = blockOrDefault(world, p.strVal("trimBlock", "minecraft:stone_brick_slab"), Blocks.STONE_BRICK_SLAB.getDefaultState());
        BlockState eave = blockOrDefault(world, p.strVal("eaveBlock", "minecraft:blackstone_slab"), Blocks.BLACKSTONE_SLAB.getDefaultState());
        BlockState accent = blockOrDefault(world, p.strVal("accentBlock", "minecraft:gold_block"), Blocks.GOLD_BLOCK.getDefaultState());
        BlockState archStair = blockOrDefault(world, p.strVal("archBlock", "minecraft:brick_stairs"), Blocks.BRICK_STAIRS.getDefaultState());

        if (paletteId != null && !paletteId.isBlank()) {
            if (p.strVal("bodyBlock", "").isBlank()) {
                body = PaletteResolver.pick(world, paletteId, "WALL_BASE", origin, 0xFA001L, body);
            }
            if (p.strVal("trimBlock", "").isBlank()) {
                trim = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xFA002L, trim);
            }
            if (p.strVal("eaveBlock", "").isBlank()) {
                eave = PaletteResolver.pick(world, paletteId, "ROOF_TILE", origin, 0xFA003L, eave);
                eave = PaletteResolver.pick(world, paletteId, "FLOOR_SLAB", origin, 0xFA004L, eave);
            }
            if (p.strVal("accentBlock", "").isBlank()) {
                accent = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xFA005L, accent);
            }
        }

        int minPer = refined ? 2 : 3;
        int maxPer = refined ? 4 : 3;
        int per = Math.max(minPer, Math.min(maxPer, height / Math.max(1, levels)));
        int extraTop = Math.max(0, height - per * levels);

        List<PlannedBlock> blocks = new ArrayList<>(levels * 900);
        int y = 0;
        int half = baseW / 2;

        for (int lv = 0; lv < levels; lv++) {
            int levelH = per;
            if (lv == levels - 1) levelH += extraTop;
            int eaveY = y + levelH;

            int inner = Math.max(0, half - (refined ? 2 : 3));
            boolean hollow = half >= 4;

            for (int dy = 0; dy < levelH; dy++) {
                int yy = y + dy;
                for (int x = -half; x <= half; x++) {
                    for (int z = -half; z <= half; z++) {
                        if (!ChineseTypologyDetailUtil.inRegularOctagon(x, z, half)) continue;
                        boolean edge = ChineseTypologyDetailUtil.onRegularOctagonEdge(x, z, half);
                        if (hollow && !edge && ChineseTypologyDetailUtil.inRegularOctagon(x, z, inner)) continue;
                        BlockState s = body;
                        if (edge && (dy % 4 == 0)) s = trim;
                        blocks.add(new PlannedBlock(origin.add(x, yy, z), s));
                    }
                }
            }

            if (lv == 0) {
                carveDoor(blocks, origin, facing, y + 1, half);
            } else {
                ChineseTypologyDetailUtil.addPagodaTierNiche(
                        blocks, origin, half, y, levelH, lv, levels, refined, trim, archStair);
            }

            ChineseTypologyDetailUtil.ringRegularOctagon(blocks, origin, half + 1, eaveY, eave);
            ChineseTypologyDetailUtil.ringRegularOctagon(blocks, origin, half, eaveY, trim);
            if (refined) {
                ChineseTypologyDetailUtil.ringRegularOctagon(blocks, origin, half + 2, eaveY + 1, eave);
            }

            y += levelH + 1;
            half = Math.max(3, half - 1);
        }

        ChineseTypologyDetailUtil.addStupaFinial(
                blocks, origin, y + 1, accent, trim, Blocks.LIGHTNING_ROD.getDefaultState());

        double slenderness = height / (double) baseW;
        double ratioScore = clamp01(1.0 - Math.abs(slenderness - 4.7) * 0.15);
        double overall = clamp01(0.9 * 0.4 + ratioScore * 0.35 + clamp01(0.75 + levels / 20.0) * 0.25);
        String ref = p.strVal("reference_landmark", "");
        String desc = String.format(
                "dense_eaves_pagoda (levels=%d, h=%d, base=%d, facing=%s, ref=%s, score=%.2f)",
                levels, height, baseW, facing.asString(), ref.isBlank() ? "-" : ref, overall
        );
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    private static void carveDoor(List<PlannedBlock> blocks, BlockPos origin, Direction facing, int y0, int half) {
        BlockPos cell = ChineseTypologyDetailUtil.octagonFaceCell(half, facing);
        int x = cell.getX();
        int z = cell.getZ();
        for (int dy = 0; dy < 3; dy++) {
            blocks.add(new PlannedBlock(origin.add(x, y0 + dy, z), Blocks.AIR.getDefaultState()));
        }
        blocks.add(new PlannedBlock(origin.add(x, y0, z), Blocks.OAK_DOOR.getDefaultState()));
    }

    private static Direction facingFromParam(String facing) {
        try {
            return Direction.valueOf(facing.trim().toUpperCase());
        } catch (Exception e) {
            return Direction.SOUTH;
        }
    }

    private static BlockState blockOrDefault(ServerWorld world, String id, BlockState def) {
        if (id == null || id.isBlank()) return def;
        try {
            var ident = net.minecraft.util.Identifier.tryParse(id);
            if (ident == null) return def;
            return net.minecraft.registry.Registries.BLOCK.get(ident).getDefaultState();
        } catch (Exception e) {
            return def;
        }
    }

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}
