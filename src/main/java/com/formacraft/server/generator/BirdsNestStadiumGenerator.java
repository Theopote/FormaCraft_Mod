package com.formacraft.server.generator;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.style.profile.DetailPreferences;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
import com.formacraft.server.build.GeneratedStructure;
import com.formacraft.server.build.PlannedBlock;
import com.formacraft.server.material.PaletteResolver;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BirdsNestStadiumGenerator（鸟巢体育馆，v1）
 *
 * 识别性目标：
 * - 椭圆形平面
 * - 碗状看台体量
 * - 外露钢网立面（iron bars / chain 交织感）
 *
 * 触发：extra.landmark = "birds_nest_stadium" / extra.template 含 birds_nest / 鸟巢
 */
public class BirdsNestStadiumGenerator implements StructureGenerator {

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> blocks = new ArrayList<>();

        BuildingStyle style = (spec != null && spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.MODERN;
        StyleProfile profile = (spec != null) ? StyleProfileRegistry.resolve(spec) : StyleProfileRegistry.forStyle(style);
        DetailPreferences details = profile != null ? profile.details() : null;

        String paletteId = getStringExtra(spec, "paletteId", null);
        if ((paletteId == null || paletteId.isBlank()) && details != null && details.paletteId != null) {
            paletteId = details.paletteId.trim();
        }

        Direction entrance = resolveEntranceFacing(spec);

        int halfW = clamp(getIntExtra(spec, "width",
                spec != null && spec.getFootprint() != null ? spec.getFootprint().getWidth() / 2 : 30), 16, 70);
        int halfD = clamp(getIntExtra(spec, "depth",
                spec != null && spec.getFootprint() != null ? spec.getFootprint().getDepth() / 2 : 40), 20, 90);
        int height = clamp(getIntExtra(spec, "height", spec != null ? spec.getHeight() : 28), 14, 60);
        boolean mesh = getBoolExtra(spec, "meshStructure", true);

        BlockState concrete = Blocks.GRAY_CONCRETE.getDefaultState();
        BlockState slab = Blocks.SMOOTH_STONE_SLAB.getDefaultState();
        BlockState meshBlock = Blocks.IRON_BARS.getDefaultState();
        BlockState accent = Blocks.IRON_CHAIN.getDefaultState();
        BlockState floor = Blocks.SMOOTH_STONE.getDefaultState();

        if (paletteId != null && !paletteId.isBlank()) {
            concrete = PaletteResolver.pick(world, paletteId, "WALL_BASE", origin, 0xB1A0101L, concrete);
            slab = PaletteResolver.pick(world, paletteId, "FLOOR_SLAB", origin, 0xB1A0102L, slab);
            meshBlock = PaletteResolver.pick(world, paletteId, "FRAME", origin, 0xB1A0103L, meshBlock);
            accent = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xB1A0104L, accent);
            floor = PaletteResolver.pick(world, paletteId, "FLOORING", origin, 0xB1A0105L, floor);
        }

        int pad = 6;
        clearBox(blocks, origin, entrance, -halfW - pad, 0, -halfD - pad,
                halfW + pad, height + 8, halfD + pad);

        // 场地地坪
        fillEllipse(blocks, origin, entrance, 0, halfW - 4, halfD - 6, 0, floor);

        // 碗状看台（逐层收缩的椭圆环）
        for (int y = 1; y <= height; y++) {
            float t = y / (float) height;
            int innerA = Math.max(4, (int) (halfW * (0.55f + 0.35f * t)));
            int innerB = Math.max(4, (int) (halfD * (0.55f + 0.35f * t)));
            int outerA = innerA + 2;
            int outerB = innerB + 2;
            ringEllipse(blocks, origin, entrance, y, innerA, innerB, outerA, outerB, concrete);
            if (y % 4 == 0) {
                ringEllipse(blocks, origin, entrance, y, innerA, innerB, outerA, outerB, slab);
            }
        }

        // 外露钢网外壳
        if (mesh) {
            buildMeshShell(blocks, origin, entrance, halfW + 2, halfD + 2, height + 2, meshBlock, accent);
        }

        return new GeneratedStructure(null, origin, "Bird's Nest Stadium (v1)", blocks);
    }

    private static void buildMeshShell(
            List<PlannedBlock> blocks,
            BlockPos origin,
            Direction entrance,
            int halfW,
            int halfD,
            int height,
            BlockState mesh,
            BlockState accent
    ) {
        for (int y = 0; y <= height; y++) {
            float yNorm = y / (float) Math.max(1, height);
            int a = (int) (halfW * (0.9f + 0.15f * Math.sin(yNorm * Math.PI)));
            int b = (int) (halfD * (0.9f + 0.15f * Math.sin(yNorm * Math.PI)));
            for (int x = -a; x <= a; x++) {
                for (int z = -b; z <= b; z++) {
                    double norm = (x * x) / (double) (a * a) + (z * z) / (double) (b * b);
                    if (norm < 0.82 || norm > 1.05) continue;
                    int hash = Math.floorMod(x * 31 + z * 17 + y * 13, 7);
                    if (hash == 0) continue;
                    BlockState state = (hash % 3 == 0) ? accent : mesh;
                    blocks.add(new PlannedBlock(local(origin, entrance, x, y, z), state));
                }
            }
        }
    }

    private static void fillEllipse(
            List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
            int y, int halfA, int halfB, int thickness, BlockState state
    ) {
        for (int x = -halfA; x <= halfA; x++) {
            for (int z = -halfB; z <= halfB; z++) {
                if (insideEllipse(x, z, halfA, halfB)) {
                    for (int dy = 0; dy < thickness; dy++) {
                        blocks.add(new PlannedBlock(local(origin, entrance, x, y + dy, z), state));
                    }
                }
            }
        }
    }

    private static void ringEllipse(
            List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
            int y, int innerA, int innerB, int outerA, int outerB, BlockState state
    ) {
        for (int x = -outerA; x <= outerA; x++) {
            for (int z = -outerB; z <= outerB; z++) {
                boolean inOuter = insideEllipse(x, z, outerA, outerB);
                boolean inInner = insideEllipse(x, z, innerA, innerB);
                if (inOuter && !inInner) {
                    blocks.add(new PlannedBlock(local(origin, entrance, x, y, z), state));
                }
            }
        }
    }

    private static boolean insideEllipse(int x, int z, int halfA, int halfB) {
        if (halfA <= 0 || halfB <= 0) return false;
        double na = halfA;
        double nb = halfB;
        return (x * x) / (na * na) + (z * z) / (nb * nb) <= 1.0;
    }

    private static void clearBox(
            List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
            int x0, int y0, int z0, int x1, int y1, int z1
    ) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    blocks.add(new PlannedBlock(local(origin, entrance, x, y, z), Blocks.AIR.getDefaultState()));
                }
            }
        }
    }

    private static BlockPos local(BlockPos origin, Direction entrance, int x, int y, int z) {
        return switch (entrance) {
            case SOUTH -> origin.add(x, y, z);
            case NORTH -> origin.add(-x, y, -z);
            case EAST -> origin.add(z, y, -x);
            case WEST -> origin.add(-z, y, x);
            default -> origin.add(x, y, z);
        };
    }

    private static Direction resolveEntranceFacing(BuildingSpec spec) {
        try {
            Map<String, Object> extra = spec != null ? spec.getExtra() : null;
            if (extra != null) {
                Object v = extra.get("facing");
                if (v == null) v = extra.get("gateSide");
                if (v != null) {
                    return Direction.valueOf(String.valueOf(v).trim().toUpperCase(Locale.ROOT));
                }
            }
        } catch (Throwable ignored) {}
        return Direction.SOUTH;
    }

    private static String getStringExtra(BuildingSpec spec, String key, String def) {
        if (spec == null || spec.getExtra() == null || key == null) return def;
        Object v = spec.getExtra().get(key);
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }

    private static int getIntExtra(BuildingSpec spec, String key, int def) {
        if (spec == null || spec.getExtra() == null || key == null) return def;
        Object v = spec.getExtra().get(key);
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
        if (spec == null || spec.getExtra() == null || key == null) return def;
        Object v = spec.getExtra().get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("1") || s.equals("yes");
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
