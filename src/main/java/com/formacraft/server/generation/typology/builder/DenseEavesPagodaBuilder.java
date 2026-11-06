package com.formacraft.server.generation.typology.builder;

import com.formacraft.FormacraftMod;
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
 * Square and octagon footprints share {@link PagodaVerticalLayout}; only contour rasterization differs.
 */
public final class DenseEavesPagodaBuilder {

    public static final String TYPOLOGY_ID = "dense_eaves_pagoda";

    private enum Footprint {
        OCTAGON,
        SQUARE
    }

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
            return generateFootprint(p, origin, world, specHint, Footprint.SQUARE);
        }
        return generateFootprint(p, origin, world, specHint, Footprint.OCTAGON);
    }

    private static GeneratedStructure generateFootprint(
            TypologyParams p,
            BlockPos origin,
            ServerWorld world,
            BuildingSpec specHint,
            Footprint footprint
    ) {
        // Octagon defaults taller; square keeps a shorter implicit prior — layout math is shared.
        PagodaVerticalSemantics vertical = footprint == Footprint.SQUARE
                ? PagodaVerticalSemantics.resolve(p, 7, 3, 15)
                : PagodaVerticalSemantics.resolve(p, 13, 7, 15);
        int floors = vertical.floorCount();
        int eavesPerLevel = vertical.eavesPerLevel();

        boolean heightExplicit = hasParam(p, "height", "towerHeight");
        int req = PagodaVerticalLayout.requiredMinHeight(vertical);
        int softDefault = footprint == Footprint.SQUARE
                ? Math.max(18, floors * 5)
                : (vertical.floorsExplicit() ? req : Math.max(47, req));
        int authoredHeight = p.intVal("height", "towerHeight", softDefault, 1, 120);

        int baseWDefault = footprint == Footprint.SQUARE ? 17 : 10;
        int baseWMin = footprint == Footprint.SQUARE ? 9 : 7;
        int baseWMax = footprint == Footprint.SQUARE ? 51 : 21;
        int baseW = p.intVal("baseWidth", baseWDefault, baseWMin, baseWMax);
        if (baseW % 2 == 0) baseW += 1;

        PagodaVerticalLayout.Result layout = PagodaVerticalLayout.plan(
                TYPOLOGY_ID,
                vertical,
                softDefault,
                heightExplicit,
                authoredHeight,
                baseW / 2
        );
        int height = layout.plannedHeight();

        String detailDefault = footprint == Footprint.SQUARE ? "aesthetic" : "refined";
        String detail = p.strVal("detailLevel", detailDefault).toLowerCase();
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
        BlockState trimDefault = footprint == Footprint.SQUARE
                ? Blocks.STONE_BRICK_SLAB.getDefaultState()
                : Blocks.STONE_BRICK_SLAB.getDefaultState();
        String trimId = footprint == Footprint.SQUARE ? "minecraft:stone_brick_slab" : "minecraft:stone_brick_slab";
        BlockState trim = blockOrDefault(world, p.strVal("trimBlock", trimId), trimDefault);
        BlockState eaveDefault = footprint == Footprint.SQUARE
                ? Blocks.BRICK_SLAB.getDefaultState()
                : Blocks.BLACKSTONE_SLAB.getDefaultState();
        String eaveId = footprint == Footprint.SQUARE ? "minecraft:brick_slab" : "minecraft:blackstone_slab";
        BlockState eave = blockOrDefault(world, p.strVal("eaveBlock", eaveId), eaveDefault);
        BlockState accentDefault = footprint == Footprint.SQUARE
                ? Blocks.CHISELED_STONE_BRICKS.getDefaultState()
                : Blocks.GOLD_BLOCK.getDefaultState();
        String accentId = footprint == Footprint.SQUARE
                ? "minecraft:chiseled_stone_bricks"
                : "minecraft:gold_block";
        BlockState accent = blockOrDefault(world, p.strVal("accentBlock", accentId), accentDefault);
        BlockState archStair = blockOrDefault(world, p.strVal("archBlock", "minecraft:brick_stairs"), Blocks.BRICK_STAIRS.getDefaultState());

        if (paletteId != null && !paletteId.isBlank()) {
            long seed = footprint == Footprint.SQUARE ? 0xD4C1A000L : 0xFA000L;
            if (p.strVal("bodyBlock", "").isBlank()) {
                body = PaletteResolver.pick(world, paletteId, "WALL_BASE", origin, seed + 1, body);
            }
            if (p.strVal("trimBlock", "").isBlank()) {
                trim = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, seed + 2, trim);
                if (footprint == Footprint.SQUARE) {
                    trim = PaletteResolver.pick(world, paletteId, "FLOOR_SLAB", origin, seed + 3, trim);
                }
            }
            if (p.strVal("eaveBlock", "").isBlank()) {
                if (footprint == Footprint.OCTAGON) {
                    eave = PaletteResolver.pick(world, paletteId, "ROOF_TILE", origin, seed + 3, eave);
                } else {
                    eave = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, seed + 4, eave);
                }
                eave = PaletteResolver.pick(world, paletteId, "FLOOR_SLAB", origin, seed + 5, eave);
            }
            if (p.strVal("accentBlock", "").isBlank()) {
                accent = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, seed + 6, accent);
            }
        }

        boolean solidCore = p.boolVal("solid", false) || p.boolVal("solid_core", false);
        List<PlannedBlock> blocks = new ArrayList<>(floors * eavesPerLevel * 900);

        for (PagodaVerticalLayout.Storey storey : layout.storeys()) {
            int half = storey.half();
            int levelH = storey.height();
            int y = storey.y0();
            int lv = storey.index();
            int wallThick = Math.max(1, Math.min(2, refined ? 2 : 1));
            int inner = Math.max(0, half - wallThick);
            boolean hollow = !solidCore && inner >= 1;
            var shaftSrc = PagodaMorphologyAudit.shaftRole(lv);

            for (int dy = 0; dy < levelH; dy++) {
                int yy = y + dy;
                boolean floorPlate = lv == 0 && dy == 0;
                for (int x = -half; x <= half; x++) {
                    for (int z = -half; z <= half; z++) {
                        if (footprint == Footprint.OCTAGON
                                && !ChineseTypologyDetailUtil.inRegularOctagon(x, z, half)) {
                            continue;
                        }
                        boolean edge = footprint == Footprint.OCTAGON
                                ? ChineseTypologyDetailUtil.onRegularOctagonEdge(x, z, half)
                                : (Math.abs(x) == half || Math.abs(z) == half);
                        if (hollow && !floorPlate && !edge) {
                            if (footprint == Footprint.OCTAGON) {
                                if (ChineseTypologyDetailUtil.inRegularOctagon(x, z, inner)) continue;
                            } else if (Math.abs(x) <= inner && Math.abs(z) <= inner) {
                                continue;
                            }
                        }
                        BlockState s = body;
                        if (footprint == Footprint.OCTAGON) {
                            if (edge && (dy % 4 == 0)) s = trim;
                        } else {
                            boolean corner = Math.abs(x) == half && Math.abs(z) == half;
                            if (corner && (dy % 3 == 0)) s = accent;
                        }
                        blocks.add(new PlannedBlock(origin.add(x, yy, z), s, shaftSrc));
                    }
                }
            }

            if (lv == 0) {
                if (footprint == Footprint.OCTAGON) {
                    carveOctagonDoor(blocks, origin, facing, y + 1, half, wallThick);
                } else {
                    carveSquareDoor(blocks, origin, facing, y + 1, half, wallThick);
                }
            } else if (footprint == Footprint.OCTAGON) {
                ChineseTypologyDetailUtil.addPagodaTierNiche(
                        blocks, origin, half, y, levelH, lv, floors, refined, trim, archStair);
            }
        }

        var eaveTrimSrc = PagodaMorphologyAudit.eaveTrimRole();
        for (PagodaVerticalLayout.EaveBand band : layout.eaves()) {
            int half = layout.storeys().get(band.levelIndex()).half();
            int ey = band.y();
            int e = band.tier();
            var eaveSrc = PagodaMorphologyAudit.eaveRole(band.levelIndex(), e);
            if (footprint == Footprint.OCTAGON) {
                ChineseTypologyDetailUtil.ringRegularOctagon(blocks, origin, half + 1 + e, ey, eave, eaveSrc);
                ChineseTypologyDetailUtil.ringRegularOctagon(blocks, origin, half + e, ey, trim, eaveTrimSrc);
                if (refined) {
                    ChineseTypologyDetailUtil.ringRegularOctagon(
                            blocks, origin, half + 1 + e, ey + 1, eave, eaveTrimSrc);
                    ChineseTypologyDetailUtil.ringRegularOctagon(
                            blocks, origin, half + 2 + e, ey, eave, eaveTrimSrc);
                }
            } else {
                ChineseTypologyDetailUtil.ringSquare(blocks, origin, half + 1 + e, ey, eave, eaveSrc);
                ChineseTypologyDetailUtil.ringSquare(blocks, origin, half + e, ey, trim, eaveTrimSrc);
                if (refined) {
                    ChineseTypologyDetailUtil.ringSquare(
                            blocks, origin, half + 1 + e, ey + 1, eave, eaveTrimSrc);
                }
            }
        }

        ChineseTypologyDetailUtil.addStupaFinial(
                blocks,
                origin,
                layout.finialBaseY(),
                accent,
                trim,
                Blocks.LIGHTNING_ROD.getDefaultState(),
                PagodaMorphologyAudit.finialRole(),
                layout.finialHeight()
        );

        int maxY = blocks.stream().mapToInt(b -> b.getPos().getY()).max().orElse(origin.getY());
        int span = maxY - origin.getY() + 1;
        if (span > height + 2) {
            FormacraftMod.LOGGER.warn(
                    "DenseEavesPagodaBuilder: vertical span {} exceeds planned height {} (floors={}, eaves_per_level={}, footprint={})",
                    span, height, floors, eavesPerLevel, footprint.name().toLowerCase(Locale.ROOT));
        }

        PagodaMorphologyAudit.checkFromBlocks(
                TYPOLOGY_ID, vertical, blocks, origin, vertical.floorsExplicit());

        double slenderness = height / (double) baseW;
        double targetSlenderness = footprint == Footprint.SQUARE ? 3.0 : 4.7;
        double ratioScore = clamp01(1.0 - Math.abs(slenderness - targetSlenderness) * 0.15);
        double overall = clamp01(0.9 * 0.4 + ratioScore * 0.35 + clamp01(0.75 + floors / 20.0) * 0.25);
        String ref = p.strVal("reference_landmark", "");
        String fpLabel = footprint == Footprint.SQUARE ? "dense_eaves_pagoda/square" : "dense_eaves_pagoda";
        String desc = String.format(
                "%s (floors=%d, eaves_per_level=%d, eave_tiers=%d, h=%d, span=%d, base=%d, facing=%s, ref=%s, score=%.2f)",
                fpLabel, floors, eavesPerLevel, layout.eaves().size(), height, span, baseW,
                facing.asString(), ref.isBlank() ? "-" : ref, overall
        );
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    private static boolean hasParam(TypologyParams p, String... keys) {
        if (p == null || p.raw() == null) return false;
        for (String key : keys) {
            if (p.raw().containsKey(key) && p.raw().get(key) != null) return true;
        }
        return false;
    }

    /**
     * Carve a walkable entrance: clear the face cell and tunnel inward through the wall
     * thickness so a second wall layer cannot block the hollow cavity behind the door.
     */
    private static void carveOctagonDoor(
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

    private static void carveSquareDoor(
            List<PlannedBlock> blocks, BlockPos origin, Direction facing, int y0, int half, int wallThick
    ) {
        int x = 0;
        int z = 0;
        if (facing == Direction.SOUTH) z = half;
        if (facing == Direction.NORTH) z = -half;
        if (facing == Direction.EAST) x = half;
        if (facing == Direction.WEST) x = -half;

        Direction inward = facing.getOpposite();
        int depth = Math.max(1, wallThick);
        for (int d = 0; d <= depth; d++) {
            int cx = x + inward.getOffsetX() * d;
            int cz = z + inward.getOffsetZ() * d;
            for (int dy = 0; dy < 3; dy++) {
                blocks.add(new PlannedBlock(origin.add(cx, y0 + dy, cz), Blocks.AIR.getDefaultState()));
                if (half >= 5 && d == 0) {
                    if (facing == Direction.SOUTH || facing == Direction.NORTH) {
                        blocks.add(new PlannedBlock(origin.add(cx + 1, y0 + dy, cz), Blocks.AIR.getDefaultState()));
                        blocks.add(new PlannedBlock(origin.add(cx - 1, y0 + dy, cz), Blocks.AIR.getDefaultState()));
                    } else {
                        blocks.add(new PlannedBlock(origin.add(cx, y0 + dy, cz + 1), Blocks.AIR.getDefaultState()));
                        blocks.add(new PlannedBlock(origin.add(cx, y0 + dy, cz - 1), Blocks.AIR.getDefaultState()));
                    }
                }
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
