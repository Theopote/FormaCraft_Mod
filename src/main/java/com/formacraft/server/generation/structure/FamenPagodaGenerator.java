package com.formacraft.server.generation.structure;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.style.profile.DetailPreferences;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
import com.formacraft.server.generation.structure.util.ChineseLandmarkDetailUtil;
import com.formacraft.server.generation.structure.util.StructureSpecParsers;
import com.formacraft.server.material.PaletteResolver;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * FamenPagodaGenerator：法门寺舍利塔（强原型）专用生成器（v2 / P2）
 *
 * P2: 正八角轮廓、券形假窗、双层檐口、相轮塔刹。
 */
public class FamenPagodaGenerator implements StructureGenerator {

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        int levels = clamp(StructureSpecParsers.extraInt(spec, "levels", spec != null ? Math.max(1, spec.getFloors()) : 13), 7, 15);
        int height = clamp(StructureSpecParsers.extraInt(spec, "towerHeight",
                spec != null && spec.getHeight() > 0 ? spec.getHeight() : 47), 28, 120);

        int baseW = StructureSpecParsers.extraInt(spec, "baseWidth",
                spec != null ? Math.max(spec.getWidth(), spec.getDepth()) : 10);
        if (baseW <= 0) baseW = 10;
        baseW = clamp(baseW, 7, 21);
        if (baseW % 2 == 0) baseW += 1;

        String detail = StructureSpecParsers.extraString(spec, "detailLevel", "refined").toLowerCase();
        boolean refined = detail.contains("refined") || detail.contains("ornate");

        BuildingStyle style = (spec != null && spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.ASIAN;
        StyleProfile profile = (spec != null) ? StyleProfileRegistry.resolve(spec) : StyleProfileRegistry.forStyle(style);
        DetailPreferences details = profile != null ? profile.details() : null;

        String paletteId = StructureSpecParsers.extraString(spec, "paletteId", null);
        if ((paletteId == null || paletteId.isBlank()) && details != null && details.paletteId != null && !details.paletteId.isBlank()) {
            paletteId = details.paletteId.trim();
        }

        Direction facing = StructureSpecParsers.resolveEntranceFacing(spec, Direction.SOUTH);

        BlockState body = blockOrDefault(world, StructureSpecParsers.extraString(spec, "bodyBlock", "minecraft:bricks"), Blocks.BRICKS.getDefaultState());
        BlockState trim = blockOrDefault(world, StructureSpecParsers.extraString(spec, "trimBlock", "minecraft:stone_brick_slab"), Blocks.STONE_BRICK_SLAB.getDefaultState());
        BlockState eave = blockOrDefault(world, StructureSpecParsers.extraString(spec, "eaveBlock", "minecraft:blackstone_slab"), Blocks.BLACKSTONE_SLAB.getDefaultState());
        BlockState accent = blockOrDefault(world, StructureSpecParsers.extraString(spec, "accentBlock", "minecraft:gold_block"), Blocks.GOLD_BLOCK.getDefaultState());
        BlockState archStair = blockOrDefault(world, StructureSpecParsers.extraString(spec, "archBlock", "minecraft:brick_stairs"), Blocks.BRICK_STAIRS.getDefaultState());

        if (paletteId != null && !paletteId.isBlank()) {
            if (StructureSpecParsers.extraString(spec, "bodyBlock", "").isBlank()) {
                body = PaletteResolver.pick(world, paletteId, "WALL_BASE", origin, 0xFA001L, body);
            }
            if (StructureSpecParsers.extraString(spec, "trimBlock", "").isBlank()) {
                trim = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xFA002L, trim);
            }
            if (StructureSpecParsers.extraString(spec, "eaveBlock", "").isBlank()) {
                eave = PaletteResolver.pick(world, paletteId, "ROOF_TILE", origin, 0xFA003L, eave);
                eave = PaletteResolver.pick(world, paletteId, "FLOOR_SLAB", origin, 0xFA004L, eave);
            }
            if (StructureSpecParsers.extraString(spec, "accentBlock", "").isBlank()) {
                accent = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xFA005L, accent);
            }
        }

        int minPer = refined ? 2 : 3;
        int maxPer = refined ? 4 : 3;
        int per = clamp(height / Math.max(1, levels), minPer, maxPer);
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
                        if (!ChineseLandmarkDetailUtil.inRegularOctagon(x, z, half)) continue;
                        boolean edge = ChineseLandmarkDetailUtil.onRegularOctagonEdge(x, z, half);
                        if (hollow && !edge && ChineseLandmarkDetailUtil.inRegularOctagon(x, z, inner)) continue;
                        BlockState s = body;
                        if (edge && (dy % 4 == 0)) s = trim;
                        blocks.add(new PlannedBlock(origin.add(x, yy, z), s));
                    }
                }
            }

            if (lv == 0) {
                carveDoor(blocks, origin, facing, y + 1, half);
            } else if (lv % 2 == 1 || refined) {
                ChineseLandmarkDetailUtil.addPagodaArchedNiches(blocks, origin, half, y, levelH, refined, trim, archStair);
            }

            ChineseLandmarkDetailUtil.ringRegularOctagon(blocks, origin, half + 1, eaveY, eave);
            ChineseLandmarkDetailUtil.ringRegularOctagon(blocks, origin, half, eaveY, trim);
            if (refined) {
                ChineseLandmarkDetailUtil.ringRegularOctagon(blocks, origin, half + 2, eaveY + 1, eave);
            }

            y += levelH + 1;
            half = Math.max(3, half - 1);
        }

        ChineseLandmarkDetailUtil.addStupaFinial(blocks, origin, y + 1, accent, trim, Blocks.LIGHTNING_ROD.getDefaultState());

        double slenderness = height / (double) baseW;
        double ratioScore = clamp01(1.0 - Math.abs(slenderness - 4.7) * 0.15);
        double overall = clamp01(0.9 * 0.4 + ratioScore * 0.35 + clamp01(0.75 + levels / 20.0) * 0.25);
        String desc = String.format(
                "FamenPagoda v2 (regular-octagon, arched niches, levels=%d, h=%d, base=%d, facing=%s, score=%.2f)",
                levels, height, baseW, facing.asString(), overall
        );
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    private static void carveDoor(List<PlannedBlock> blocks, BlockPos origin, Direction facing, int y0, int half) {
        BlockPos cell = ChineseLandmarkDetailUtil.octagonFaceCell(half, facing);
        int x = cell.getX();
        int z = cell.getZ();
        for (int dy = 0; dy < 3; dy++) {
            blocks.add(new PlannedBlock(origin.add(x, y0 + dy, z), Blocks.AIR.getDefaultState()));
        }
        blocks.add(new PlannedBlock(origin.add(x, y0, z), Blocks.OAK_DOOR.getDefaultState()));
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

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}
