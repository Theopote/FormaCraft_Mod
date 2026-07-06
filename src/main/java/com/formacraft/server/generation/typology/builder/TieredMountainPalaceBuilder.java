package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.logging.FcaLog;
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
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parametric tiered mountain palace builder (typology: {@code tiered_mountain_palace}).
 * VERTICAL_STACK：台地基座 + 逐层退台宫城体量 + 顶层金顶。
 */
public final class TieredMountainPalaceBuilder {

    public static final String TYPOLOGY_ID = "tiered_mountain_palace";

    private static final FcaLog LOG = FcaLog.of("TieredMountainPalaceBuilder");

    private TieredMountainPalaceBuilder() {}

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

        BuildingStyle style = (spec != null && spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.ASIAN;
        StyleProfile profile = (spec != null) ? StyleProfileRegistry.resolve(spec) : StyleProfileRegistry.forStyle(style);
        DetailPreferences details = profile != null ? profile.details() : null;

        String paletteId = getStringExtra(spec, "paletteId", null);
        if ((paletteId == null || paletteId.isBlank()) && details != null
                && details.paletteId != null && !details.paletteId.isBlank()) {
            paletteId = details.paletteId.trim();
        }

        Direction entrance = StructureSpecParsers.resolveEntranceFacing(spec, Direction.SOUTH);

        int bw = clampOdd(getIntExtra(spec, "baseWidth", 48), 24, 96);
        int bd = clampOdd(getIntExtra(spec, "baseDepth", 40), 20, 88);
        int tiers = clamp(getIntExtra(spec, "tiers", 5), 2, 8);
        int tierInset = clamp(getIntExtra(spec, "tierInset", 3), 2, 6);
        int tierHeight = clamp(getIntExtra(spec, "tierHeight", 6), 4, 12);
        int platformH = clamp(getIntExtra(spec, "platformHeight", 4), 2, 10);

        BlockState foundation = Blocks.STONE_BRICKS.getDefaultState();
        BlockState wall = Blocks.WHITE_TERRACOTTA.getDefaultState();
        BlockState trim = Blocks.RED_TERRACOTTA.getDefaultState();
        BlockState accent = Blocks.GOLD_BLOCK.getDefaultState();
        BlockState floor = Blocks.POLISHED_ANDESITE.getDefaultState();

        if (paletteId != null && !paletteId.isBlank()) {
            foundation = PaletteResolver.pick(world, paletteId, "WALL_FOUNDATION", origin, 0xB10001L, foundation);
            wall = PaletteResolver.pick(world, paletteId, "WALL_BASE", origin, 0xB10002L, wall);
            trim = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xB10003L, trim);
            accent = PaletteResolver.pick(world, paletteId, "ROOF_TILE", origin, 0xB10004L, accent);
            floor = PaletteResolver.pick(world, paletteId, "FLOORING", origin, 0xB10005L, floor);
        }

        int clearH = platformH + tiers * tierHeight + 12;
        int pad = 3;
        for (int x = -pad; x <= bw - 1 + pad; x++) {
            for (int z = -pad; z <= bd - 1 + pad; z++) {
                for (int y = 0; y <= clearH; y++) {
                    put(blocks, origin, entrance, x, y, z, Blocks.AIR.getDefaultState());
                }
            }
        }

        fillRect(blocks, origin, entrance, 0, 0, 0, bw - 1, platformH - 1, bd - 1, foundation);
        ringRect(blocks, origin, entrance, 0, platformH - 1, 0, bw - 1, platformH - 1, bd - 1, trim);

        int topY = platformH;
        for (int tier = 0; tier < tiers; tier++) {
            int inset = tier * tierInset;
            int x0 = inset;
            int z0 = inset;
            int x1 = bw - 1 - inset;
            int z1 = bd - 1 - inset;
            if (x1 - x0 < 8 || z1 - z0 < 8) {
                break;
            }

            int y0 = platformH + tier * tierHeight;
            int y1 = y0 + tierHeight - 1;

            fillRect(blocks, origin, entrance, x0, y0, z0, x1, y0, z1, floor);
            for (int y = y0 + 1; y <= y1; y++) {
                ringRect(blocks, origin, entrance, x0, y, z0, x1, y, z1, wall);
            }
            if (x1 - x0 > 2 && z1 - z0 > 2) {
                fillRect(blocks, origin, entrance, x0 + 1, y0 + 1, z0 + 1, x1 - 1, y1, z1 - 1, Blocks.AIR.getDefaultState());
            }
            ringRect(blocks, origin, entrance, x0, y1, z0, x1, y1, z1, trim);
            topY = y1 + 1;
        }

        int cx = bw / 2;
        int cz = bd / 2;
        for (int y = topY; y < topY + 4; y++) {
            put(blocks, origin, entrance, cx, y, cz, accent);
        }
        put(blocks, origin, entrance, cx, topY + 4, cz, Blocks.LIGHTNING_ROD.getDefaultState());

        String desc = String.format(
                "Tiered Mountain Palace (tiered_mountain_palace, %dx%d, tiers=%d)",
                bw, bd, tiers
        );
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    private static void fillRect(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                 int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    put(blocks, origin, entrance, x, y, z, s);
                }
            }
        }
    }

    private static void ringRect(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                 int x0, int y, int z0, int x1, int y1, int z1, BlockState s) {
        int yy = y;
        for (int x = x0; x <= x1; x++) {
            put(blocks, origin, entrance, x, yy, z0, s);
            put(blocks, origin, entrance, x, yy, z1, s);
        }
        for (int z = z0; z <= z1; z++) {
            put(blocks, origin, entrance, x0, yy, z, s);
            put(blocks, origin, entrance, x1, yy, z, s);
        }
    }

    private static void put(List<PlannedBlock> blocks, BlockPos origin, Direction entrance, int x, int y, int z, BlockState s) {
        if (s == null) {
            return;
        }
        blocks.add(new PlannedBlock(local(origin, entrance, x, y, z), rotateState(s, entrance)));
    }

    private static BlockPos local(BlockPos origin, Direction entrance, int x, int y, int z) {
        return rotatePos(origin.add(x, y, z), origin, entrance);
    }

    private static BlockPos rotatePos(BlockPos p, BlockPos base, Direction entrance) {
        if (entrance == null || entrance == Direction.SOUTH) {
            return p;
        }
        int dx = p.getX() - base.getX();
        int dy = p.getY() - base.getY();
        int dz = p.getZ() - base.getZ();
        return switch (entrance) {
            case NORTH -> base.add(-dx, dy, -dz);
            case EAST -> base.add(dz, dy, -dx);
            case WEST -> base.add(-dz, dy, dx);
            default -> p;
        };
    }

    private static Direction rotateDir(Direction local, Direction entrance) {
        if (entrance == null || entrance == Direction.SOUTH || local == null) {
            return local;
        }
        if (!local.getAxis().isHorizontal()) {
            return local;
        }
        return switch (entrance) {
            case NORTH -> (local == Direction.NORTH) ? Direction.SOUTH
                    : (local == Direction.SOUTH) ? Direction.NORTH
                    : (local == Direction.EAST) ? Direction.WEST
                    : Direction.EAST;
            case EAST -> (local == Direction.SOUTH) ? Direction.EAST
                    : (local == Direction.NORTH) ? Direction.WEST
                    : (local == Direction.EAST) ? Direction.NORTH
                    : Direction.SOUTH;
            case WEST -> (local == Direction.SOUTH) ? Direction.WEST
                    : (local == Direction.NORTH) ? Direction.EAST
                    : (local == Direction.EAST) ? Direction.SOUTH
                    : Direction.NORTH;
            default -> local;
        };
    }

    private static BlockState rotateState(BlockState s, Direction entrance) {
        if (s == null) {
            return null;
        }
        BlockState out = s;
        try {
            if (out.contains(Properties.HORIZONTAL_FACING)) {
                Direction f = out.get(Properties.HORIZONTAL_FACING);
                out = out.with(Properties.HORIZONTAL_FACING, rotateDir(f, entrance));
            }
            if (out.contains(Properties.FACING)) {
                Direction f = out.get(Properties.FACING);
                out = out.with(Properties.FACING, rotateDir(f, entrance));
            }
        } catch (Throwable ex) {
            LOG.debug("best-effort step failed", ex);
        }
        return out;
    }

    private static String getStringExtra(BuildingSpec spec, String key, String def) {
        if (spec == null || spec.getExtra() == null) {
            return def;
        }
        Object v = spec.getExtra().get(key);
        if (v == null) {
            return def;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }

    private static int getIntExtra(BuildingSpec spec, String key, int def) {
        return StructureSpecParsers.extraInt(spec, key, def);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int clampOdd(int v, int min, int max) {
        int x = clamp(v, min, max);
        return (x % 2 == 0) ? (x + 1 <= max ? x + 1 : x - 1) : x;
    }
}
