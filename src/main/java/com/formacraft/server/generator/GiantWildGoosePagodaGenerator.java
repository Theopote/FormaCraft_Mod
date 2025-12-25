package com.formacraft.server.generator;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.server.build.GeneratedStructure;
import com.formacraft.server.build.PlannedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
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

        Direction facing = parseFacing(getStringExtra(spec, "facing", "SOUTH"));

        BlockState body = getStateOrDefault(world, getStringExtra(spec, "bodyBlock", "minecraft:bricks"), Blocks.BRICKS.getDefaultState());
        BlockState trim = getStateOrDefault(world, getStringExtra(spec, "trimBlock", "minecraft:stone_brick_slab"), Blocks.STONE_BRICK_SLAB.getDefaultState());
        BlockState eave = getStateOrDefault(world, getStringExtra(spec, "eaveBlock", "minecraft:brick_slab"), Blocks.BRICK_SLAB.getDefaultState());
        BlockState accent = getStateOrDefault(world, getStringExtra(spec, "accentBlock", "minecraft:chiseled_stone_bricks"), Blocks.CHISELED_STONE_BRICKS.getDefaultState());

        List<PlannedBlock> blocks = new ArrayList<>(Math.max(5000, baseW * baseW * height / 3));

        // Distribute height by levels (each level 4~6 blocks tall)
        int minPer = refined ? 5 : 4;
        int maxPer = refined ? 7 : 6;
        int per = clamp(height / levels, minPer, maxPer);
        int usedHeight = per * levels;
        int extraTop = Math.max(0, height - usedHeight);

        int y = 0;
        int w = baseW;
        int shrinkEvery = refined ? 2 : 2; // shrink by 2 each level (symmetry)

        for (int lv = 0; lv < levels; lv++) {
            int levelH = per;
            if (lv == levels - 1) levelH += extraTop; // push remaining to top

            int half = w / 2;

            // body: hollow core (like stairwell) if large enough
            boolean hollow = w >= (refined ? 11 : 13);
            int inner = Math.max(1, half - (refined ? 2 : 3));

            for (int dy = 0; dy < levelH; dy++) {
                int yy = y + dy;
                for (int x = -half; x <= half; x++) {
                    for (int z = -half; z <= half; z++) {
                        boolean edge = Math.abs(x) == half || Math.abs(z) == half;
                        boolean corner = Math.abs(x) == half && Math.abs(z) == half;

                        // hollow: leave interior air except at edges
                        if (hollow && !edge && Math.abs(x) <= inner && Math.abs(z) <= inner) {
                            continue;
                        }

                        BlockState s = body;
                        if (corner && (dy % 3 == 0)) s = accent;
                        blocks.add(new PlannedBlock(origin.add(x, yy, z), s));
                    }
                }
            }

            // door opening at ground level only
            if (lv == 0) {
                carveDoor(blocks, origin, facing, y + 1, w);
            }

            // eave band at top of each level
            int eY = y + levelH;
            ringSquare(blocks, origin, half + 1, eY, eave);
            ringSquare(blocks, origin, half, eY, trim);

            y += levelH + 1; // +1 to separate levels visually

            // shrink for next level
            w = Math.max(7, w - shrinkEvery);
            if (w % 2 == 0) w -= 1;
        }

        // top spire
        int topY = y + 1;
        blocks.add(new PlannedBlock(origin.add(0, topY, 0), accent));
        blocks.add(new PlannedBlock(origin.add(0, topY + 1, 0), Blocks.LIGHTNING_ROD.getDefaultState()));

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

    private static void ringSquare(List<PlannedBlock> blocks, BlockPos origin, int half, int y, BlockState s) {
        for (int x = -half; x <= half; x++) {
            blocks.add(new PlannedBlock(origin.add(x, y, -half), s));
            blocks.add(new PlannedBlock(origin.add(x, y, half), s));
        }
        for (int z = -half; z <= half; z++) {
            blocks.add(new PlannedBlock(origin.add(-half, y, z), s));
            blocks.add(new PlannedBlock(origin.add(half, y, z), s));
        }
    }

    private static void carveDoor(List<PlannedBlock> blocks, BlockPos origin, Direction facing, int y0, int w) {
        int half = w / 2;
        // door position at center of facing side
        int x = 0;
        int z = 0;
        if (facing == Direction.SOUTH) z = half;
        if (facing == Direction.NORTH) z = -half;
        if (facing == Direction.EAST) x = half;
        if (facing == Direction.WEST) x = -half;

        // carve 2x3 opening
        for (int dy = 0; dy < 3; dy++) {
            blocks.add(new PlannedBlock(origin.add(x, y0 + dy, z), Blocks.AIR.getDefaultState()));
            // widen if possible
            if (half >= 5) {
                if (facing == Direction.SOUTH || facing == Direction.NORTH) {
                    blocks.add(new PlannedBlock(origin.add(x + 1, y0 + dy, z), Blocks.AIR.getDefaultState()));
                    blocks.add(new PlannedBlock(origin.add(x - 1, y0 + dy, z), Blocks.AIR.getDefaultState()));
                } else {
                    blocks.add(new PlannedBlock(origin.add(x, y0 + dy, z + 1), Blocks.AIR.getDefaultState()));
                    blocks.add(new PlannedBlock(origin.add(x, y0 + dy, z - 1), Blocks.AIR.getDefaultState()));
                }
            }
        }
        // simple door block (doesn't have to be perfect orientation)
        blocks.add(new PlannedBlock(origin.add(x, y0, z), Blocks.OAK_DOOR.getDefaultState()));
    }

    private static Direction parseFacing(String s) {
        String v = (s == null ? "" : s).trim().toUpperCase();
        return switch (v) {
            case "N", "NORTH", "北", "朝北" -> Direction.NORTH;
            case "S", "SOUTH", "南", "朝南" -> Direction.SOUTH;
            case "E", "EAST", "东", "朝东" -> Direction.EAST;
            case "W", "WEST", "西", "朝西" -> Direction.WEST;
            default -> Direction.SOUTH;
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


