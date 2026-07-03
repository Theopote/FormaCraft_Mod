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
 * ElvenFlowerHouseGenerator (v1 / P0)
 *
 * 识别性目标：
 * - “茎干”空心塔（木/苔藓/石）
 * - 绕茎螺旋上升动线（环形踏步近似）
 * - “花瓣伞盖”顶部大冠（petals/leaves/glass），花蕊柔光
 *
 * 触发：用 template（flower_house / 花朵屋）。
 */
public class ElvenFlowerHouseGenerator implements StructureGenerator {

    private static final FcaLog LOG = FcaLog.of("ElvenFlowerHouseGenerator");

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> blocks = new ArrayList<>();

        int stemR = clamp(getIntExtra(spec, "stemRadius", 4), 3, 7);
        int stemH = clamp(getIntExtra(spec, "stemHeight", 18), 12, 42);
        int capR = clamp(getIntExtra(spec, "capRadius", stemR * 2 + 8), stemR * 2 + 6, stemR * 2 + 16);
        int capH = clamp(getIntExtra(spec, "capHeight", Math.max(7, capR / 2)), 6, 14);
        boolean addSpiral = getBoolExtra(spec, "spiral", true);

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
        BlockState stem = Blocks.STRIPPED_OAK_LOG.getDefaultState();
        BlockState beam = Blocks.OAK_LOG.getDefaultState();
        BlockState floor = Blocks.MOSS_BLOCK.getDefaultState();
        BlockState slab = Blocks.MOSSY_COBBLESTONE_SLAB.getDefaultState();
        BlockState petal = getStateOrDefault(world, "minecraft:pink_petals", Blocks.PINK_WOOL.getDefaultState());
        BlockState petalAlt = Blocks.FLOWERING_AZALEA_LEAVES.getDefaultState();
        BlockState glass = Blocks.PINK_STAINED_GLASS.getDefaultState();
        BlockState glow = Blocks.SHROOMLIGHT.getDefaultState();
        BlockState rail = Blocks.OAK_FENCE.getDefaultState();

        if (paletteId != null && !paletteId.isBlank() && world != null) {
            stem = PaletteResolver.pick(world, paletteId, "STRUCTURAL_BEAM", origin, 0xE110001L, stem);
            stem = PaletteResolver.pick(world, paletteId, "WALL_BASE", origin, 0xE110002L, stem);
            beam = PaletteResolver.pick(world, paletteId, "FRAME", origin, 0xE110003L, beam);
            floor = PaletteResolver.pick(world, paletteId, "FLOORING", origin, 0xE110004L, floor);
            slab = PaletteResolver.pick(world, paletteId, "FLOOR_SLAB", origin, 0xE110005L, slab);
            petal = PaletteResolver.pick(world, paletteId, "ROOF_TILE", origin, 0xE110006L, petal);
            petal = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xE110007L, petal);
            petalAlt = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xE110008L, petalAlt);
            glass = PaletteResolver.pick(world, paletteId, "FACADE_CURTAIN", origin, 0xE110009L, glass);
            glass = PaletteResolver.pick(world, paletteId, "WINDOW", origin, 0xE11000AL, glass);
            glow = PaletteResolver.pick(world, paletteId, "LIGHTING", origin, 0xE11000BL, glow);
            glow = PaletteResolver.pick(world, paletteId, "ROAD_LIGHT", origin, 0xE11000CL, glow);
            rail = PaletteResolver.pick(world, paletteId, "ROAD_BORDER", origin, 0xE11000DL, rail);
        }

        // 1) Stem (hollow)
        for (int y = 0; y <= stemH; y++) {
            for (int x = -stemR; x <= stemR; x++) {
                for (int z = -stemR; z <= stemR; z++) {
                    int d2 = x * x + z * z;
                    if (d2 > stemR * stemR) continue;
                    boolean shell = d2 >= (int) (stemR * stemR * 0.62);
                    BlockPos p = rotateLocal(origin, x, y, z, facing);
                    if (shell) {
                        BlockState s = (y % 6 == 0 && (Math.abs(x) == stemR || Math.abs(z) == stemR)) ? beam : stem;
                        blocks.add(new PlannedBlock(p, s));
                    } else if (y == 0) {
                        blocks.add(new PlannedBlock(p, floor));
                    } else {
                        blocks.add(new PlannedBlock(p, Blocks.AIR.getDefaultState()));
                    }
                }
            }
        }

        // door opening on facing side (+Z in local)
        for (int y = 1; y <= 2; y++) {
            for (int x = -1; x <= 1; x++) {
                blocks.add(new PlannedBlock(rotateLocal(origin, x, y, stemR, facing), Blocks.AIR.getDefaultState()));
            }
        }
        blocks.add(new PlannedBlock(rotateLocal(origin, 0, 3, stemR + 1, facing), glow));

        // 2) Spiral walkway around stem (approx curve with 8-phase ring)
        if (addSpiral) {
            int ringR = stemR + 3;
            for (int y = 1; y <= stemH - 2; y++) {
                int phase = y % 8;
                int sx = switch (phase) {
                    case 0 -> ringR;
                    case 1 -> ringR;
                    case 2 -> 0;
                    case 3 -> -ringR;
                    case 4 -> -ringR;
                    case 5 -> -ringR;
                    case 6 -> 0;
                    default -> ringR;
                };
                int sz = switch (phase) {
                    case 0 -> 0;
                    case 1 -> ringR;
                    case 2 -> ringR;
                    case 3 -> ringR;
                    case 4 -> 0;
                    case 5 -> -ringR;
                    case 6 -> -ringR;
                    default -> -ringR;
                };
                // step pad
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (Math.abs(dx) + Math.abs(dz) > 1) continue;
                        blocks.add(new PlannedBlock(rotateLocal(origin, sx + dx, y, sz + dz, facing), slab));
                    }
                }
                if ((y % 9) == 0) blocks.add(new PlannedBlock(rotateLocal(origin, sx, y + 1, sz, facing), glow));
            }
        }

        // 3) Petal canopy (top hemisphere)
        BlockPos capCenter = origin.up(stemH + 3);
        for (int x = -capR; x <= capR; x++) {
            for (int y = -capH; y <= capH; y++) {
                for (int z = -capR; z <= capR; z++) {
                    double nx = x / (double) capR;
                    double ny = y / (double) capH;
                    double nz = z / (double) capR;
                    double v = nx * nx + ny * ny + nz * nz;
                    if (v > 1.02) continue;
                    if (y < -1) continue; // top hemisphere-ish
                    double inner = (x / (double) (capR * 0.80)) * (x / (double) (capR * 0.80))
                            + (y / (double) (capH * 0.80)) * (y / (double) (capH * 0.80))
                            + (z / (double) (capR * 0.80)) * (z / (double) (capR * 0.80));
                    boolean shell = inner >= 1.02;
                    if (!shell) continue;

                    BlockState s = (hash01(x, y, z) > 0.78) ? petalAlt : petal;
                    // some translucent patches
                    if (y >= 2 && v >= 0.90 && hash01(x + 5, y + 9, z + 17) > 0.88) s = glass;
                    // some glow near "pollen" area
                    if (x * x + z * z <= (capR * capR * 0.10) && y <= 3 && hash01(x + 19, y + 3, z + 7) > 0.65) s = glow;
                    blocks.add(new PlannedBlock(rotateLocal(capCenter, x, y, z, facing), s));
                }
            }
        }

        // 4) Top platform under canopy (treehouse feel)
        int py = stemH + 2;
        int pr = Math.max(stemR + 2, 6);
        for (int x = -pr; x <= pr; x++) {
            for (int z = -pr; z <= pr; z++) {
                int d2 = x * x + z * z;
                if (d2 > pr * pr) continue;
                BlockPos p = rotateLocal(origin, x, py, z, facing);
                blocks.add(new PlannedBlock(p, floor));
                if (d2 >= (int) (pr * pr * 0.85) && hash01(x, py, z) > 0.35) {
                    blocks.add(new PlannedBlock(p.up(), rail));
                }
            }
        }
        blocks.add(new PlannedBlock(rotateLocal(origin, 0, py + 2, 0, facing), glow));

        String desc = String.format("ElvenFlowerHouse (stemR=%d,stemH=%d,capR=%d,capH=%d,facing=%s)",
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


