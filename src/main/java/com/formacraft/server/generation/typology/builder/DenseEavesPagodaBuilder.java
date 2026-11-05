package com.formacraft.server.generation.typology.builder;

import com.formacraft.FormacraftMod;
import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.style.profile.DetailPreferences;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
import com.formacraft.common.generation.component.util.InteriorSpaceDefaults;
import com.formacraft.common.typology.TypologyParamResolver;
import com.formacraft.common.typology.TypologyParams;
import com.formacraft.common.typology.detail.ChineseTypologyDetailUtil;
import com.formacraft.common.skeleton.SkeletonParams;
import com.formacraft.common.skeleton.stack.VerticalStackPlan;
import com.formacraft.server.skeleton.stack.VerticalStackInterpreter;
import com.formacraft.server.skeleton.stack.VerticalStackSkeleton;
import com.formacraft.server.material.PaletteResolver;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import com.formacraft.server.generation.structure.util.StructureSpecParsers;

import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parametric dense-eaves brick pagoda (typology: {@code dense_eaves_pagoda}).
 * Shaft storeys ({@code floor_count}) are distinct from per-storey eaves ({@code eaves_per_level}).
 * 三层重檐 → 3 body segments × 2 eaves + centered finial — never inflate floors to 7+.
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
        String footprint = p.strVal("footprint", "octagon").toLowerCase(Locale.ROOT);
        if ("square".equals(footprint)) {
            return generateSquare(p, origin, world, specHint);
        }
        return generateOctagon(p, origin, world, specHint);
    }

    private static GeneratedStructure generateSquare(
            TypologyParams p,
            BlockPos origin,
            ServerWorld world,
            BuildingSpec specHint
    ) {
        PagodaVerticalSemantics vertical = PagodaVerticalSemantics.resolve(p, 7, 3, 15);
        int floors = vertical.floorCount();
        int height = p.intVal("height", "towerHeight", Math.max(18, floors * 5), 18, 120);
        int baseW = p.intVal("baseWidth", 17, 9, 51);
        if (baseW % 2 == 0) baseW += 1;

        String detail = p.strVal("detailLevel", "aesthetic").toLowerCase();
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
        BlockState eave = blockOrDefault(world, p.strVal("eaveBlock", "minecraft:brick_slab"), Blocks.BRICK_SLAB.getDefaultState());
        BlockState accent = blockOrDefault(world, p.strVal("accentBlock", "minecraft:chiseled_stone_bricks"), Blocks.CHISELED_STONE_BRICKS.getDefaultState());

        if (paletteId != null && !paletteId.isBlank()) {
            if (p.strVal("bodyBlock", "").isBlank()) {
                body = PaletteResolver.pick(world, paletteId, "WALL_BASE", origin, 0xD4C1A001L, body);
            }
            if (p.strVal("trimBlock", "").isBlank()) {
                trim = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xD4C1A002L, trim);
                trim = PaletteResolver.pick(world, paletteId, "FLOOR_SLAB", origin, 0xD4C1A003L, trim);
            }
            if (p.strVal("eaveBlock", "").isBlank()) {
                eave = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xD4C1A004L, eave);
                eave = PaletteResolver.pick(world, paletteId, "FLOOR_SLAB", origin, 0xD4C1A005L, eave);
            }
            if (p.strVal("accentBlock", "").isBlank()) {
                accent = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xD4C1A006L, accent);
            }
        }

        SkeletonParams skParams = new SkeletonParams()
                .put("levels", floors)
                .put("height", height)
                .put("baseWidth", baseW)
                .put("refined", refined)
                .put("facing", facing.asString());

        VerticalStackPlan plan = new VerticalStackSkeleton().generate(skParams);
        List<PlannedBlock> blocks = new VerticalStackInterpreter(body, trim, eave, accent, true)
                .interpret(plan, origin, world);

        // Measure final blocks — do not trust generator loop counters as morphology proof.
        PagodaMorphologyAudit.checkFromBlocks(
                TYPOLOGY_ID, vertical, blocks, origin, vertical.floorsExplicit());

        double ratioScore = clamp01(1.0 - Math.abs((height / (double) baseW) - 3.0) * 0.12);
        double signatureScore = clamp01(0.75 + Math.min(0.25, floors / 10.0));
        double overall = clamp01(0.9 * 0.4 + ratioScore * 0.3 + signatureScore * 0.3);
        String ref = p.strVal("reference_landmark", "");
        String desc = String.format(
                "dense_eaves_pagoda/square (floors=%d, eaves_per_level=%d, eave_tiers=%d, h=%d, base=%d, facing=%s, ref=%s, score=%.2f)",
                floors, vertical.eavesPerLevel(), vertical.eaveTiers(), height, baseW,
                facing.asString(), ref.isBlank() ? "-" : ref, overall
        );
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    private static GeneratedStructure generateOctagon(
            TypologyParams p,
            BlockPos origin,
            ServerWorld world,
            BuildingSpec specHint
    ) {
        PagodaVerticalSemantics vertical = PagodaVerticalSemantics.resolve(p, 13, 7, 15);
        int floors = vertical.floorCount();
        int eavesPerLevel = vertical.eavesPerLevel();
        // Budget shaft clear-height (≥3, ground taller) + eave rings + finial.
        int minClear = InteriorSpaceDefaults.MIN_CLEAR_HEIGHT;
        int groundClear = InteriorSpaceDefaults.DEFAULT_GROUND_CLEAR_HEIGHT;
        int upperClear = InteriorSpaceDefaults.DEFAULT_UPPER_CLEAR_HEIGHT;
        int bodyNeed = floors <= 1
                ? minClear
                : groundClear + Math.max(0, floors - 1) * upperClear;
        int eaveNeed = floors * Math.max(1, eavesPerLevel);
        final int finialHeight = 10;
        int minTotalHeight = bodyNeed + eaveNeed + finialHeight;
        int height = p.intVal("height", "towerHeight",
                Math.max(vertical.floorsExplicit() ? minTotalHeight : Math.max(47, minTotalHeight), minTotalHeight),
                Math.min(18, minTotalHeight), 120);
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

        // ``height`` is TOTAL vertical span (shaft + eave rings + finial), not shaft alone.
        // Storey body height ≈ indoor clear height for hollow shafts.
        int minPer = minClear;
        int maxPer = refined ? 5 : 4;
        boolean solidCore = p.boolVal("solid", false) || p.boolVal("solid_core", false);
        int eaveRows = floors * Math.max(1, eavesPerLevel);
        // Explicit floor/eave intent must emit every tier — expand height rather than drop eaves.
        if (vertical.floorsExplicit()) {
            height = Math.max(height, bodyNeed + eaveRows + finialHeight);
        }
        int reserved = eaveRows + finialHeight;
        int bodyBudget = Math.max(bodyNeed, height - reserved);
        if (!vertical.floorsExplicit() && bodyBudget < bodyNeed) {
            // Implicit tall defaults only: prefer clear-height storeys; soft-trim eaves if extreme.
            eaveRows = Math.max(floors, height - finialHeight - bodyNeed);
            eavesPerLevel = Math.max(1, eaveRows / Math.max(1, floors));
            reserved = floors * eavesPerLevel + finialHeight;
            bodyBudget = Math.max(bodyNeed, height - reserved);
        }
        bodyBudget = Math.max(bodyNeed, Math.min(bodyBudget, Math.max(bodyNeed, height - reserved)));
        // Uniform upper-floor clear height; ground floor may claim +1 from leftover budget.
        int per = Math.max(minPer, Math.min(maxPer, bodyBudget / Math.max(1, floors)));
        int leftover = Math.max(0, bodyBudget - per * floors);
        int groundBonus = 0;
        if (floors > 1 && per < groundClear) {
            groundBonus = Math.min(leftover, groundClear - per);
            leftover -= groundBonus;
        } else if (floors > 1 && leftover > 0 && per + 1 <= maxPer) {
            groundBonus = 1;
            leftover -= 1;
        }
        int extraTop = leftover;

        List<PlannedBlock> blocks = new ArrayList<>(floors * eavesPerLevel * 900);
        int y = 0;
        int half = baseW / 2;
        int emittedEaves = 0;
        int shaftAndEaveCap = Math.max(bodyNeed + eaveRows, height - finialHeight);

        for (int lv = 0; lv < floors; lv++) {
            int levelH = per;
            if (lv == 0) levelH += groundBonus;
            if (lv == floors - 1) levelH += extraTop;
            int remainingLevels = floors - lv;
            int remainingEaves = remainingLevels * eavesPerLevel;
            int yCapForShaft = shaftAndEaveCap - remainingEaves;
            levelH = Math.max(minPer, Math.min(levelH, Math.max(minPer, yCapForShaft - y)));
            // Wall thickness 1–2; hollow whenever a ≥1-cell cavity remains (unless solid_core).
            int wallThick = Math.max(1, Math.min(2, refined ? 2 : 1));
            int inner = Math.max(0, half - wallThick);
            boolean hollow = !solidCore && inner >= 1;

            var shaftSrc = PagodaMorphologyAudit.shaftRole(lv);
            for (int dy = 0; dy < levelH; dy++) {
                int yy = y + dy;
                for (int x = -half; x <= half; x++) {
                    for (int z = -half; z <= half; z++) {
                        if (!ChineseTypologyDetailUtil.inRegularOctagon(x, z, half)) continue;
                        boolean edge = ChineseTypologyDetailUtil.onRegularOctagonEdge(x, z, half);
                        if (hollow && !edge && ChineseTypologyDetailUtil.inRegularOctagon(x, z, inner)) continue;
                        BlockState s = body;
                        if (edge && (dy % 4 == 0)) s = trim;
                        blocks.add(new PlannedBlock(origin.add(x, yy, z), s, shaftSrc));
                    }
                }
            }

            if (lv == 0) {
                carveDoor(blocks, origin, facing, y + 1, half, wallThick);
            } else {
                ChineseTypologyDetailUtil.addPagodaTierNiche(
                        blocks, origin, half, y, levelH, lv, floors, refined, trim, archStair);
            }

            int eaveY = y + levelH;
            int placedThisLevel = 0;
            var eaveTrimSrc = PagodaMorphologyAudit.eaveTrimRole();
            for (int e = 0; e < eavesPerLevel; e++) {
                int ey = eaveY + e;
                // Never drop authored eave tiers once floors are explicit.
                if (!vertical.floorsExplicit() && ey >= shaftAndEaveCap) break;
                var eaveSrc = PagodaMorphologyAudit.eaveRole(lv, e);
                // Intentional eave tier — material may be slab OR full block; role carries identity.
                ChineseTypologyDetailUtil.ringRegularOctagon(blocks, origin, half + 1 + e, ey, eave, eaveSrc);
                ChineseTypologyDetailUtil.ringRegularOctagon(blocks, origin, half + e, ey, trim, eaveTrimSrc);
                if (refined && (vertical.floorsExplicit() || ey + 1 < shaftAndEaveCap)) {
                    ChineseTypologyDetailUtil.ringRegularOctagon(
                            blocks, origin, half + 1 + e, ey + 1, eave, eaveTrimSrc);
                    ChineseTypologyDetailUtil.ringRegularOctagon(
                            blocks, origin, half + 2 + e, ey, eave, eaveTrimSrc);
                }
                emittedEaves++;
                placedThisLevel++;
            }

            y += levelH + placedThisLevel;
            // Allow taper to half=2 so small bases (e.g. 7×7 → half=3) still show setback.
            half = Math.max(2, half - 1);
        }

        int finialY = Math.min(Math.max(y, 1), Math.max(1, height - finialHeight));
        ChineseTypologyDetailUtil.addStupaFinial(
                blocks, origin, finialY, accent, trim, Blocks.LIGHTNING_ROD.getDefaultState(),
                PagodaMorphologyAudit.finialRole());

        int maxY = blocks.stream().mapToInt(b -> b.getPos().getY()).max().orElse(origin.getY());
        int span = maxY - origin.getY() + 1;
        if (span > height + 1) {
            FormacraftMod.LOGGER.warn(
                    "DenseEavesPagodaBuilder: vertical span {} exceeds planned height {} (floors={}, eaves_per_level={})",
                    span, height, floors, eavesPerLevel);
        }

        // Geometry audit from emitted blocks (setback/finial/eave Y-bands), not loop counters.
        PagodaMorphologyAudit.checkFromBlocks(
                TYPOLOGY_ID, vertical, blocks, origin, vertical.floorsExplicit());

        double slenderness = height / (double) baseW;
        double ratioScore = clamp01(1.0 - Math.abs(slenderness - 4.7) * 0.15);
        double overall = clamp01(0.9 * 0.4 + ratioScore * 0.35 + clamp01(0.75 + floors / 20.0) * 0.25);
        String ref = p.strVal("reference_landmark", "");
        String desc = String.format(
                "dense_eaves_pagoda (floors=%d, eaves_per_level=%d, eave_tiers=%d, h=%d, span=%d, base=%d, facing=%s, ref=%s, score=%.2f)",
                floors, eavesPerLevel, emittedEaves, height, span, baseW,
                facing.asString(), ref.isBlank() ? "-" : ref, overall
        );
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    /**
     * Carve a walkable entrance: clear the face cell and tunnel inward through the wall
     * thickness so a second wall layer cannot block the hollow cavity behind the door.
     */
    private static void carveDoor(
            List<PlannedBlock> blocks, BlockPos origin, Direction facing, int y0, int half, int wallThick
    ) {
        BlockPos cell = ChineseTypologyDetailUtil.octagonFaceCell(half, facing);
        int x = cell.getX();
        int z = cell.getZ();
        Direction inward = facing.getOpposite();
        int depth = Math.max(1, wallThick);
        for (int d = 0; d <= depth; d++) {
            int cx = x + inward.getOffsetX() * d;
            int cz = z + inward.getOffsetZ() * d;
            for (int dy = 0; dy < 3; dy++) {
                blocks.add(new PlannedBlock(origin.add(cx, y0 + dy, cz), Blocks.AIR.getDefaultState()));
            }
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
        return Math.min(v, 1.0);
    }
}
