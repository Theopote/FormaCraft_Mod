package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.style.profile.DetailPreferences;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
import com.formacraft.common.typology.TypologyParamResolver;
import com.formacraft.server.generation.structure.util.StructureSpecParsers;
import com.formacraft.server.material.PaletteResolver;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parametric elliptical stadium bowl builder (typology: {@code stadium_bowl}).
 * 椭圆平面 + 碗状看台 + 可选钢网外壳（鸟巢意象）。
 */
public final class StadiumBowlBuilder {

    public static final String TYPOLOGY_ID = "stadium_bowl";

    private StadiumBowlBuilder() {}

    public static GeneratedStructure fromBuildingSpec(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        return generate(TypologyParamResolver.fromBuildingSpec(spec, TYPOLOGY_ID), origin, world, spec);
    }

    public static GeneratedStructure generate(
            Map<String, Object> params,
            BlockPos origin,
            ServerWorld world,
            BuildingSpec specHint
    ) {
        BuildingSpec spec = specHint != null ? specHint : new BuildingSpec();
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
        if (spec.getExtra() != null) {
            merged.putAll(spec.getExtra());
        }
        if (params != null) {
            merged.putAll(params);
        }
        spec.setExtra(merged);
        return generateFromSpec(spec, origin, world);
    }

    private static GeneratedStructure generateFromSpec(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> blocks = new ArrayList<>();

        BuildingStyle style = (spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.MODERN;
        StyleProfile profile = StyleProfileRegistry.resolve(spec);
        DetailPreferences details = profile != null ? profile.details() : null;

        String paletteId = getStringExtra(spec, "paletteId", null);
        if ((paletteId == null || paletteId.isBlank()) && details != null && details.paletteId != null) {
            paletteId = details.paletteId.trim();
        }

        Direction entrance = resolveEntranceFacing(spec);

        int fullW = clamp(getIntExtra(spec, "width",
                spec.getFootprint() != null ? spec.getFootprint().getWidth() : 60), 30, 120);
        int fullD = clamp(getIntExtra(spec, "depth",
                spec.getFootprint() != null ? spec.getFootprint().getDepth() : 80), 40, 160);
        int halfW = clamp(fullW / 2, 16, 70);
        int halfD = clamp(fullD / 2, 20, 90);
        int height = clamp(getIntExtra(spec, "height", spec.getHeight() > 0 ? spec.getHeight() : 28), 14, 60);
        boolean mesh = getBoolExtra(spec, "meshStructure", true);
        long designSeed = resolveDesignSeed(spec, origin);
        float bowlSteepness = clampFloat(getFloatExtra(spec, "bowlSteepness", 0.35f), 0.2f, 0.5f);
        int tierStep = clamp(getIntExtra(spec, "tierStep", 4), 2, 8);

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

        fillEllipse(blocks, origin, entrance, 0, halfW - 4, halfD - 6, 0, floor);

        for (int y = 1; y <= height; y++) {
            float t = y / (float) height;
            int innerA = Math.max(4, (int) (halfW * (0.55f + bowlSteepness * t)));
            int innerB = Math.max(4, (int) (halfD * (0.55f + bowlSteepness * t)));
            int outerA = innerA + 2;
            int outerB = innerB + 2;
            ringEllipse(blocks, origin, entrance, y, innerA, innerB, outerA, outerB, concrete);
            if (y % tierStep == 0) {
                ringEllipse(blocks, origin, entrance, y, innerA, innerB, outerA, outerB, slab);
            }
        }

        if (mesh) {
            buildMeshShell(blocks, origin, entrance, halfW + 2, halfD + 2, height + 2, meshBlock, accent, designSeed);
        }

        return new GeneratedStructure(null, origin, "Stadium Bowl (stadium_bowl)", blocks);
    }

    private static void buildMeshShell(
            List<PlannedBlock> blocks,
            BlockPos origin,
            Direction entrance,
            int halfW,
            int halfD,
            int height,
            BlockState mesh,
            BlockState accent,
            long designSeed
    ) {
        int seedMix = (int) (designSeed & 0xFFFFL);
        for (int y = 0; y <= height; y++) {
            float yNorm = y / (float) Math.max(1, height);
            int a = (int) (halfW * (0.9f + 0.15f * Math.sin(yNorm * Math.PI)));
            int b = (int) (halfD * (0.9f + 0.15f * Math.sin(yNorm * Math.PI)));
            for (int x = -a; x <= a; x++) {
                for (int z = -b; z <= b; z++) {
                    double norm = (x * x) / (double) (a * a) + (z * z) / (double) (b * b);
                    if (norm < 0.82 || norm > 1.05) continue;
                    int hash = Math.floorMod(x * 31 + z * 17 + y * 13 + seedMix, 7);
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
        return (x * x) / ((double) halfA * (double) halfA) + (z * z) / ((double) halfB * (double) halfB) <= 1.0;
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
            Map<String, Object> extra = spec.getExtra();
            if (extra != null) {
                Object v = extra.get("facing");
                if (v == null) v = extra.get("gateSide");
                if (v != null) {
                    return Direction.valueOf(String.valueOf(v).trim().toUpperCase(Locale.ROOT));
                }
            }
        } catch (Throwable ignored) {
        }
        return Direction.SOUTH;
    }

    private static long resolveDesignSeed(BuildingSpec spec, BlockPos origin) {
        if (spec.getExtra() != null) {
            Object v = spec.getExtra().get("designSeed");
            if (v instanceof Number n) {
                return n.longValue();
            }
            if (v != null) {
                try {
                    return Long.parseLong(String.valueOf(v).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (origin != null) {
            return origin.asLong() ^ 0xB1D05E57L;
        }
        return 42L;
    }

    private static float getFloatExtra(BuildingSpec spec, String key, float def) {
        if (spec.getExtra() == null || key == null) return def;
        Object v = spec.getExtra().get(key);
        if (v == null) return def;
        if (v instanceof Number n) return n.floatValue();
        try {
            return Float.parseFloat(String.valueOf(v).trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private static float clampFloat(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String getStringExtra(BuildingSpec spec, String key, String def) {
        if (spec.getExtra() == null || key == null) return def;
        Object v = spec.getExtra().get(key);
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }

    private static int getIntExtra(BuildingSpec spec, String key, int def) {
        return StructureSpecParsers.extraInt(spec, key, def);
    }

    private static boolean getBoolExtra(BuildingSpec spec, String key, boolean def) {
        if (spec.getExtra() == null || key == null) return def;
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
