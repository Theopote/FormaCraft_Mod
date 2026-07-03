package com.formacraft.common.generation.structure;

import com.formacraft.common.generation.structure.util.StructureSpecParsers;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.style.profile.DetailPreferences;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.server.material.PaletteResolver;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ElvenMushroomHouseGenerator (v1 / P0)
 *
 * 识别性目标：
 * - 蘑菇柄（mushroom stem）+ 蘑菇伞盖（red/brown mushroom block）
 * - 伞沿下“菌褶”感（用 slab/leaf 做稀疏内圈）
 * - 柔光（shroomlight / palette LIGHTING）
 *
 * 触发：用 template（mushroom_house / 蘑菇屋），避免误伤其它精灵建筑。
 */
public class ElvenMushroomHouseGenerator implements StructureGenerator {

    private static final FcaLog LOG = FcaLog.of("ElvenMushroomHouseGenerator");

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> blocks = new ArrayList<>();

        int stemR = clamp(getIntExtra(spec, "stemRadius", 4), 3, 7);
        int stemH = clamp(getIntExtra(spec, "stemHeight", 11), 8, 18);
        int capR = clamp(getIntExtra(spec, "capRadius", stemR * 2 + 5), stemR * 2 + 3, stemR * 2 + 12);
        int capH = clamp(getIntExtra(spec, "capHeight", Math.max(6, capR / 2)), 5, 12);
        boolean addPorch = getBoolExtra(spec, "porch", true);

        // Style & palette
        BuildingStyle style = (spec != null && spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.ASIAN;
        StyleProfile profile = (spec != null) ? StyleProfileRegistry.resolve(spec) : StyleProfileRegistry.forStyle(style);
        DetailPreferences details = profile != null ? profile.details() : null;
        String paletteId = (spec != null && spec.getExtra() != null && spec.getExtra().get("paletteId") != null)
                ? String.valueOf(spec.getExtra().get("paletteId")).trim()
                : null;
        if ((paletteId == null || paletteId.isBlank()) && details != null && details.paletteId != null && !details.paletteId.isBlank()) {
            paletteId = details.paletteId.trim();
        }

        Direction facing = resolveEntranceFacing(spec);

        // Materials (semantic first)
        BlockState stem = getStateOrDefault(world, "minecraft:mushroom_stem", Blocks.OAK_LOG.getDefaultState());
        BlockState cap = getStateOrDefault(world, "minecraft:red_mushroom_block", Blocks.RED_MUSHROOM_BLOCK.getDefaultState());
        BlockState capAlt = getStateOrDefault(world, "minecraft:brown_mushroom_block", Blocks.BROWN_MUSHROOM_BLOCK.getDefaultState());
        BlockState floor = Blocks.MOSS_BLOCK.getDefaultState();
        BlockState slab = Blocks.MOSSY_COBBLESTONE_SLAB.getDefaultState();
        BlockState leaf = Blocks.OAK_LEAVES.getDefaultState();
        BlockState glow = Blocks.SHROOMLIGHT.getDefaultState();
        BlockState rail = Blocks.OAK_FENCE.getDefaultState();

        if (paletteId != null && !paletteId.isBlank() && world != null) {
            stem = PaletteResolver.pick(world, paletteId, "WALL_BASE", origin, 0xE6F0001L, stem);
            stem = PaletteResolver.pick(world, paletteId, "STRUCTURAL_BEAM", origin, 0xE6F0002L, stem);
            cap = PaletteResolver.pick(world, paletteId, "ROOF_TILE", origin, 0xE6F0003L, cap);
            capAlt = PaletteResolver.pick(world, paletteId, "ROOF_TILE", origin, 0xE6F0004L, capAlt);
            floor = PaletteResolver.pick(world, paletteId, "FLOORING", origin, 0xE6F0005L, floor);
            slab = PaletteResolver.pick(world, paletteId, "FLOOR_SLAB", origin, 0xE6F0006L, slab);
            leaf = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xE6F0007L, leaf);
            glow = PaletteResolver.pick(world, paletteId, "LIGHTING", origin, 0xE6F0008L, glow);
            glow = PaletteResolver.pick(world, paletteId, "ROAD_LIGHT", origin, 0xE6F0009L, glow);
            rail = PaletteResolver.pick(world, paletteId, "ROAD_BORDER", origin, 0xE6F000AL, rail);
        }

        // 1) Stem cylinder (hollow)
        for (int y = 0; y <= stemH; y++) {
            for (int x = -stemR; x <= stemR; x++) {
                for (int z = -stemR; z <= stemR; z++) {
                    int d2 = x * x + z * z;
                    if (d2 > stemR * stemR) continue;
                    boolean shell = d2 >= (int) (stemR * stemR * 0.65);
                    BlockPos p = rotateLocal(origin, x, y, z, facing);
                    if (shell) blocks.add(new PlannedBlock(p, stem));
                    else if (y == 0) blocks.add(new PlannedBlock(p, floor)); // floor fill
                    else blocks.add(new PlannedBlock(p, Blocks.AIR.getDefaultState())); // hollow
                }
            }
        }

        // door opening at y=1..2 on facing side (+Z in local)
        for (int y = 1; y <= 2; y++) {
            for (int x = -1; x <= 1; x++) {
                blocks.add(new PlannedBlock(rotateLocal(origin, x, y, stemR, facing), Blocks.AIR.getDefaultState()));
            }
        }
        // door light
        blocks.add(new PlannedBlock(rotateLocal(origin, 0, 3, stemR + 1, facing), glow));

        // 2) Cap (hemisphere-ish)
        BlockPos capCenter = origin.up(stemH + 2);
        int capOuter = capR;
        for (int x = -capOuter; x <= capOuter; x++) {
            for (int y = -capH; y <= capH; y++) {
                for (int z = -capOuter; z <= capOuter; z++) {
                    double nx = x / (double) capOuter;
                    double ny = y / (double) capH;
                    double nz = z / (double) capOuter;
                    double v = nx * nx + ny * ny + nz * nz;
                    if (v > 1.02) continue;
                    // keep only top hemisphere + thick shell
                    if (y < -1) continue;
                    double inner = (x / (double) (capOuter * 0.78)) * (x / (double) (capOuter * 0.78))
                            + (y / (double) (capH * 0.78)) * (y / (double) (capH * 0.78))
                            + (z / (double) (capOuter * 0.78)) * (z / (double) (capOuter * 0.78));
                    boolean shell = inner >= 1.02;
                    if (!shell) continue;
                    BlockState s = (hash01(x, y, z) > 0.72) ? capAlt : cap;
                    // sprinkle glow dots under rim
                    if (y <= 2 && y >= 0 && v >= 0.90 && hash01(x + 9, y + 5, z + 3) > 0.90) s = glow;
                    blocks.add(new PlannedBlock(rotateLocal(capCenter, x, y, z, facing), s));
                }
            }
        }

        // 3) Gills under cap rim (inner ring slabs/leaves)
        int gillY = stemH + 1;
        int gillR0 = capR - 4;
        int gillR1 = capR - 1;
        for (int x = -gillR1; x <= gillR1; x++) {
            for (int z = -gillR1; z <= gillR1; z++) {
                int d2 = x * x + z * z;
                if (d2 < gillR0 * gillR0 || d2 > gillR1 * gillR1) continue;
                BlockState s = (hash01(x, gillY, z) > 0.55) ? slab : leaf;
                blocks.add(new PlannedBlock(rotateLocal(origin, x, gillY, z, facing), s));
            }
        }

        // 4) Small porch
        if (addPorch) {
            int py = 0;
            for (int k = 1; k <= 5; k++) {
                blocks.add(new PlannedBlock(rotateLocal(origin, 0, py, stemR + k, facing), slab));
                if (k >= 2 && (k % 2 == 0)) {
                    blocks.add(new PlannedBlock(rotateLocal(origin, -1, py + 1, stemR + k, facing), rail));
                    blocks.add(new PlannedBlock(rotateLocal(origin, 1, py + 1, stemR + k, facing), rail));
                }
            }
        }

        String desc = String.format("ElvenMushroomHouse (stemR=%d,stemH=%d,capR=%d,capH=%d,facing=%s)",
                stemR, stemH, capR, capH, facing.asString());
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    private static BlockPos rotateLocal(BlockPos origin, int lx, int ly, int lz, Direction facing) {
        if (origin == null) return null;
        if (facing == null) facing = Direction.SOUTH;
        return switch (facing) {
            case SOUTH -> origin.add(lx, ly, lz);
            case NORTH -> origin.add(-lx, ly, -lz);
            case EAST -> origin.add(lz, ly, -lx);
            case WEST -> origin.add(-lz, ly, lx);
            default -> origin.add(lx, ly, lz);
        };
    }

    private static Direction resolveEntranceFacing(BuildingSpec spec) {
        if (spec == null || spec.getExtra() == null) return Direction.SOUTH;
        try {
            Object layoutObj = spec.getExtra().get("layout");
            if (layoutObj instanceof Map<?, ?> m) {
                Object ef = m.get("entranceFacing");
                if (ef != null) {
                    String s = String.valueOf(ef).trim().toUpperCase();
                    return switch (s) {
                        case "N", "NORTH", "北", "朝北" -> Direction.NORTH;
                        case "S", "SOUTH", "南", "朝南" -> Direction.SOUTH;
                        case "E", "EAST", "东", "朝东" -> Direction.EAST;
                        case "W", "WEST", "西", "朝西" -> Direction.WEST;
                        default -> Direction.SOUTH;
                    };
                }
            }
        } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
        return Direction.SOUTH;
    }

    private static boolean getBoolExtra(BuildingSpec spec, String key, boolean def) {
        if (spec == null) return def;
        Map<String, Object> extra = spec.getExtra();
        if (extra == null) return def;
        Object v = extra.get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.isEmpty()) return def;
        return s.equals("1") || s.equals("true") || s.equals("yes") || s.equals("y") || s.equals("on");
    }

    private static int getIntExtra(BuildingSpec spec, String key, int def) {
        return StructureSpecParsers.extraInt(spec, key, def);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double hash01(int a, int b, int c) {
        long x = 1469598103934665603L;
        x ^= a; x *= 1099511628211L;
        x ^= b; x *= 1099511628211L;
        x ^= c; x *= 1099511628211L;
        x ^= (x >>> 33);
        return ((x & 0x7fffffffffffffffL) / (double) Long.MAX_VALUE);
    }

    private static BlockState getStateOrDefault(ServerWorld world, String id, BlockState def) {
        if (id == null || id.isBlank()) return def;
        try {
            Identifier ident = Identifier.tryParse(id);
            if (ident == null) return def;
            return net.minecraft.registry.Registries.BLOCK.get(ident).getDefaultState();
        } catch (Exception e) {
            return def;
        }
    }
}


