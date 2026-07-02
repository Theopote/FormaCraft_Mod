package com.formacraft.common.generation.structure;

import com.formacraft.common.generation.structure.util.StructureSpecParsers;
import com.formacraft.common.logging.FcaLog;
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
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ElvenTreehouseGenerator (v1 / P0)
 *
 * 识别性目标：
 * - 仿生：大树树干 + 树冠（叶/根/浆果发光）
 * - 曲线：绕树干的“螺旋上升动线”（用环形踏步近似）
 * - 树屋：多层平台 + 简易护栏 + 柔和生物荧光
 *
 * 说明：
 * - 这是强原型入口：用 template 触发（避免把所有 Elven 请求都强制变树屋）。
 * - 只做 90° 旋转：extra.layout.entranceFacing。
 */
public class ElvenTreehouseGenerator implements StructureGenerator {

    private static final FcaLog LOG = FcaLog.of("ElvenTreehouseGenerator");

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> blocks = new ArrayList<>();

        int height = clamp(getIntExtra(spec, "height", 26), 16, 56);
        int trunkR = clamp(getIntExtra(spec, "trunkRadius", 3), 2, 5);
        int canopyR = clamp(getIntExtra(spec, "canopyRadius", Math.max(6, trunkR * 3)), 5, 14);
        int platformCount = clamp(getIntExtra(spec, "platforms", 3), 2, 6);
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
        BlockState trunk = Blocks.OAK_LOG.getDefaultState();
        BlockState beam = Blocks.STRIPPED_OAK_LOG.getDefaultState();
        BlockState leaf = Blocks.OAK_LEAVES.getDefaultState();
        BlockState vine = Blocks.HANGING_ROOTS.getDefaultState();
        BlockState floor = Blocks.MOSS_BLOCK.getDefaultState();
        BlockState slab = Blocks.MOSSY_COBBLESTONE_SLAB.getDefaultState();
        BlockState rail = Blocks.OAK_FENCE.getDefaultState();
        BlockState glow = Blocks.SHROOMLIGHT.getDefaultState();
        // Note: "glow_berries" is an ITEM; use cave vines / glow lichen as a block-level proxy.
        BlockState berryVine = getStateOrDefault(world, "minecraft:cave_vines", Blocks.GLOW_LICHEN.getDefaultState());

        if (paletteId != null && !paletteId.isBlank() && world != null) {
            trunk = PaletteResolver.pick(world, paletteId, "STRUCTURAL_BEAM", origin, 0xE1F0001L, trunk);
            beam = PaletteResolver.pick(world, paletteId, "FRAME", origin, 0xE1F0002L, beam);
            floor = PaletteResolver.pick(world, paletteId, "FLOORING", origin, 0xE1F0003L, floor);
            slab = PaletteResolver.pick(world, paletteId, "FLOOR_SLAB", origin, 0xE1F0004L, slab);
            leaf = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xE1F0005L, leaf);
            vine = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xE1F0006L, vine);
            berryVine = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xE1F0007L, berryVine);
            glow = PaletteResolver.pick(world, paletteId, "LIGHTING", origin, 0xE1F0008L, glow);
            glow = PaletteResolver.pick(world, paletteId, "ROAD_LIGHT", origin, 0xE1F0009L, glow);
            rail = PaletteResolver.pick(world, paletteId, "ROAD_BORDER", origin, 0xE1F000AL, rail);
        }

        // Local coordinate frame: we treat +Z as "front" and rotate by entranceFacing
        BlockPos base = origin;

        // 1) Trunk (slightly tapered)
        for (int y = 0; y <= height; y++) {
            int r = trunkR - (y > height * 0.70 ? 1 : 0);
            r = Math.max(2, r);
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    if (x * x + z * z > r * r) continue;
                    blocks.add(new PlannedBlock(rotateLocal(base, x, y, z, facing), trunk));
                }
            }
            // occasional “knots” / braces
            if ((y % 6) == 0 && y >= 6 && y < height - 3) {
                blocks.add(new PlannedBlock(rotateLocal(base, r + 1, y, 0, facing), beam));
                blocks.add(new PlannedBlock(rotateLocal(base, -(r + 1), y, 0, facing), beam));
                blocks.add(new PlannedBlock(rotateLocal(base, 0, y, r + 1, facing), beam));
                blocks.add(new PlannedBlock(rotateLocal(base, 0, y, -(r + 1), facing), beam));
            }
        }

        // 2) Canopy (leafy sphere) + bioluminescent spots
        BlockPos canopyCenter = base.up(height - 2);
        for (int x = -canopyR; x <= canopyR; x++) {
            for (int y = -canopyR; y <= canopyR; y++) {
                for (int z = -canopyR; z <= canopyR; z++) {
                    int d2 = x * x + y * y + z * z;
                    if (d2 > canopyR * canopyR) continue;
                    boolean shell = d2 >= (int) (canopyR * canopyR * 0.62);
                    if (!shell && (hash01(x, y, z) < 0.72)) continue; // keep canopy airy
                    BlockState s = leaf;
                    // glow berries / shroomlight patches
                    double r01 = hash01(x + 7, y + 11, z + 13);
                    if (shell && r01 > 0.92) s = glow;
                    else if (shell && r01 > 0.82) s = berryVine;
                    blocks.add(new PlannedBlock(rotateLocal(canopyCenter, x, y, z, facing), s));
                }
            }
        }
        // hanging roots/vines under canopy rim
        for (int a = 0; a < 16; a++) {
            int x = (int) Math.round(Math.cos(a * Math.PI / 8.0) * (canopyR - 1));
            int z = (int) Math.round(Math.sin(a * Math.PI / 8.0) * (canopyR - 1));
            BlockPos p = rotateLocal(canopyCenter, x, -(canopyR / 2), z, facing);
            for (int dy = 0; dy < 4; dy++) blocks.add(new PlannedBlock(p.down(dy), vine));
        }

        // 3) Platforms (treehouse floors)
        for (int i = 0; i < platformCount; i++) {
            int py = 6 + i * Math.max(4, (height - 10) / (platformCount));
            int pr = Math.max(trunkR + 2, 5 + (i % 2));
            // offset some platforms for asymmetry
            int ox = (i % 2 == 0) ? 2 : -2;
            int oz = (i % 3 == 0) ? 1 : -1;
            placePlatform(blocks, base, facing, ox, py, oz, pr, floor, slab, rail, glow);
        }

        // 4) Spiral walkway (approximate curve with 8-step ring)
        if (addSpiral) {
            int ringR = trunkR + 3;
            int stepEvery = 1; // vertical step each layer
            for (int y = 1; y <= height - 4; y += stepEvery) {
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

                // make each step a 2-wide pad for readability
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (Math.abs(dx) + Math.abs(dz) > 1) continue;
                        blocks.add(new PlannedBlock(rotateLocal(base, sx + dx, y, sz + dz, facing), slab));
                    }
                }
                // occasional soft light
                if ((y % 7) == 0) {
                    blocks.add(new PlannedBlock(rotateLocal(base, sx, y + 1, sz, facing), glow));
                }
            }
        }

        // 5) Small entrance bridge from ground to first platform (in facing direction)
        int bridgeY = 5;
        int bridgeLen = trunkR + 6;
        for (int k = 1; k <= bridgeLen; k++) {
            BlockPos p = rotateLocal(base, 0, bridgeY, k, facing);
            blocks.add(new PlannedBlock(p, slab));
            if ((k & 2) == 0) {
                blocks.add(new PlannedBlock(p.up(), rail));
                blocks.add(new PlannedBlock(p.up().east(), rail));
            }
        }
        blocks.add(new PlannedBlock(rotateLocal(base, 0, bridgeY + 1, bridgeLen - 1, facing), glow));

        String desc = String.format("ElvenTreehouse (h=%d,trunkR=%d,canopyR=%d,platforms=%d,facing=%s)",
                height, trunkR, canopyR, platformCount, facing.asString());
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    private static void placePlatform(List<PlannedBlock> blocks,
                                      BlockPos base,
                                      Direction facing,
                                      int ox,
                                      int y,
                                      int oz,
                                      int r,
                                      BlockState floor,
                                      BlockState slab,
                                      BlockState rail,
                                      BlockState glow) {
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                int d2 = x * x + z * z;
                if (d2 > r * r) continue;
                BlockPos p = rotateLocal(base, ox + x, y, oz + z, facing);
                blocks.add(new PlannedBlock(p, floor));
                // edge guard
                if (d2 >= (int) (r * r * 0.82) && (hash01(x, y, z) > 0.25)) {
                    blocks.add(new PlannedBlock(p.up(), rail));
                }
                // a few plank/slab accents
                if ((x + z) % 7 == 0 && d2 <= (int) (r * r * 0.5)) {
                    blocks.add(new PlannedBlock(p.up(1), slab));
                }
            }
        }
        // one glow spot
        blocks.add(new PlannedBlock(rotateLocal(base, ox, y + 2, oz, facing), glow));
    }

    private static BlockPos rotateLocal(BlockPos origin, int lx, int ly, int lz, Direction facing) {
        if (origin == null) return null;
        if (facing == null) facing = Direction.SOUTH;
        // local forward = +Z
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
        } catch (Throwable ex) { LOG.debug("best-effort step failed", t); }
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


