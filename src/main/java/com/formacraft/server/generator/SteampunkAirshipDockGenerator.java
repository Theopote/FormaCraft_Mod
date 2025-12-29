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
import java.util.Map;

/**
 * SteampunkAirshipDockGenerator (v1)
 *
 * 识别性目标（P0）：
 * - 栈桥平台（码头/空港平台）：BRIDGE_DECK + BRIDGE_RAIL
 * - 系泊塔：外露梁柱（STRUCTURAL_BEAM/FRAME）+ 系泊链（DECOR_DETAIL -> chain）
 * - 吊车梁/龙门架：横梁（STRUCTURAL_BEAM）+ 吊钩/链条（DECOR_DETAIL）
 *
 * 参数（spec.extra）：
 * - length / width：平台尺寸
 * - towerHeight：系泊塔高度
 * - craneHeight：吊车梁高度
 * - rails：是否加护栏（默认 true）
 *
 * Layout：
 * - extra.layout.entranceFacing 控制“入口/栈桥延伸方向”（把本地 +Z 旋转到 facing）。
 */
public class SteampunkAirshipDockGenerator implements StructureGenerator {

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> blocks = new ArrayList<>();

        int length = clamp(getIntExtra(spec, "length", 39), 17, 99);
        int width = clamp(getIntExtra(spec, "width", 17), 9, 61);
        int towerH = clamp(getIntExtra(spec, "towerHeight", 18), 10, 48);
        int craneH = clamp(getIntExtra(spec, "craneHeight", Math.max(10, towerH - 4)), 8, 44);
        boolean rails = getBoolExtra(spec, "rails", true);

        // Style & palette
        BuildingStyle style = (spec != null && spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.MODERN;
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
        BlockState deck = Blocks.CUT_COPPER.getDefaultState();
        BlockState rail = Blocks.IRON_BARS.getDefaultState();
        BlockState beam = Blocks.SPRUCE_LOG.getDefaultState();
        BlockState frame = Blocks.CUT_COPPER.getDefaultState();
        BlockState foundation = Blocks.DEEPSLATE_BRICKS.getDefaultState();
        BlockState light = Blocks.LANTERN.getDefaultState();
        BlockState chain = getStateOrDefault(world, "minecraft:chain", Blocks.IRON_BARS.getDefaultState());
        BlockState decor = Blocks.IRON_BARS.getDefaultState();

        if (paletteId != null && !paletteId.isBlank() && world != null) {
            deck = PaletteResolver.pick(world, paletteId, "BRIDGE_DECK", origin, 0x5D0C1001L, deck);
            deck = PaletteResolver.pick(world, paletteId, "FLOORING", origin, 0x5D0C1002L, deck);
            rail = PaletteResolver.pick(world, paletteId, "BRIDGE_RAIL", origin, 0x5D0C1003L, rail);
            rail = PaletteResolver.pick(world, paletteId, "ROAD_BORDER", origin, 0x5D0C1004L, rail);
            beam = PaletteResolver.pick(world, paletteId, "STRUCTURAL_BEAM", origin, 0x5D0C1005L, beam);
            frame = PaletteResolver.pick(world, paletteId, "FRAME", origin, 0x5D0C1006L, frame);
            foundation = PaletteResolver.pick(world, paletteId, "WALL_FOUNDATION", origin, 0x5D0C1007L, foundation);
            decor = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0x5D0C1008L, decor);
            chain = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0x5D0C1009L, chain);
            light = PaletteResolver.pick(world, paletteId, "LIGHTING", origin, 0x5D0C100AL, light);
            light = PaletteResolver.pick(world, paletteId, "ROAD_LIGHT", origin, 0x5D0C100BL, light);
        }

        // Platform origin in local frame: x [0..w-1], z [0..len-1], centered by x around origin
        int x0 = -width / 2;

        // 1) Deck + foundation ring under corners
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < length; z++) {
                BlockPos p = rotateLocal(origin, x0 + x, 0, z, facing);
                blocks.add(new PlannedBlock(p, deck));
                if ((x == 0 || x == width - 1) && (z % 7 == 0)) {
                    // piles/legs
                    for (int y = -1; y >= -3; y--) blocks.add(new PlannedBlock(rotateLocal(origin, x0 + x, y, z, facing), foundation));
                }
            }
        }

        // 2) Rails
        if (rails) {
            for (int z = 0; z < length; z++) {
                blocks.add(new PlannedBlock(rotateLocal(origin, x0, 1, z, facing), rail));
                blocks.add(new PlannedBlock(rotateLocal(origin, x0 + width - 1, 1, z, facing), rail));
                if ((z % 9) == 0) {
                    blocks.add(new PlannedBlock(rotateLocal(origin, x0, 2, z, facing), light));
                    blocks.add(new PlannedBlock(rotateLocal(origin, x0 + width - 1, 2, z, facing), light));
                }
            }
            for (int x = 0; x < width; x++) {
                blocks.add(new PlannedBlock(rotateLocal(origin, x0 + x, 1, 0, facing), rail));
                blocks.add(new PlannedBlock(rotateLocal(origin, x0 + x, 1, length - 1, facing), rail));
            }
        }

        // 3) Mooring tower near the far end (z ~ len-6), slightly right-biased for asymmetry
        int towerZ = Math.max(6, length - 7);
        int towerX = x0 + (width / 4); // right-ish
        int towerR = 2;
        for (int y = 1; y <= towerH; y++) {
            // 4 posts
            blocks.add(new PlannedBlock(rotateLocal(origin, towerX - towerR, y, towerZ - towerR, facing), beam));
            blocks.add(new PlannedBlock(rotateLocal(origin, towerX + towerR, y, towerZ - towerR, facing), beam));
            blocks.add(new PlannedBlock(rotateLocal(origin, towerX - towerR, y, towerZ + towerR, facing), beam));
            blocks.add(new PlannedBlock(rotateLocal(origin, towerX + towerR, y, towerZ + towerR, facing), beam));
            // braces
            if ((y & 1) == 0 && y < towerH) {
                blocks.add(new PlannedBlock(rotateLocal(origin, towerX, y, towerZ - towerR, facing), decor));
                blocks.add(new PlannedBlock(rotateLocal(origin, towerX, y, towerZ + towerR, facing), decor));
                blocks.add(new PlannedBlock(rotateLocal(origin, towerX - towerR, y, towerZ, facing), decor));
                blocks.add(new PlannedBlock(rotateLocal(origin, towerX + towerR, y, towerZ, facing), decor));
            }
        }
        // top platform ring
        for (int dx = -towerR; dx <= towerR; dx++) {
            for (int dz = -towerR; dz <= towerR; dz++) {
                if (Math.abs(dx) == towerR || Math.abs(dz) == towerR) {
                    blocks.add(new PlannedBlock(rotateLocal(origin, towerX + dx, towerH + 1, towerZ + dz, facing), frame));
                }
            }
        }
        blocks.add(new PlannedBlock(rotateLocal(origin, towerX, towerH + 2, towerZ, facing), light));

        // mooring points (chains) hanging from top corners
        for (int k = 0; k < 2; k++) {
            int dx = (k == 0) ? -towerR : towerR;
            BlockPos top = rotateLocal(origin, towerX + dx, towerH + 1, towerZ + towerR, facing);
            for (int dy = 0; dy < 6; dy++) blocks.add(new PlannedBlock(top.down(dy), chain));
        }

        // 4) Crane gantry near mid platform: two columns + horizontal girder + hanging hook
        int craneZ = length / 2;
        int craneX0 = x0 + 1;
        int craneX1 = x0 + width - 2;
        // columns
        for (int y = 1; y <= craneH; y++) {
            blocks.add(new PlannedBlock(rotateLocal(origin, craneX0, y, craneZ, facing), beam));
            blocks.add(new PlannedBlock(rotateLocal(origin, craneX1, y, craneZ, facing), beam));
            if ((y & 1) == 0) {
                blocks.add(new PlannedBlock(rotateLocal(origin, craneX0 + 1, y, craneZ, facing), decor));
                blocks.add(new PlannedBlock(rotateLocal(origin, craneX1 - 1, y, craneZ, facing), decor));
            }
        }
        // girder
        for (int x = craneX0; x <= craneX1; x++) {
            blocks.add(new PlannedBlock(rotateLocal(origin, x, craneH + 1, craneZ, facing), beam));
            if ((x % 3) == 0) blocks.add(new PlannedBlock(rotateLocal(origin, x, craneH + 2, craneZ, facing), frame));
        }
        // hook (chain + decor block)
        int hookX = x0 + width / 2;
        BlockPos hookTop = rotateLocal(origin, hookX, craneH + 1, craneZ, facing);
        for (int dy = 1; dy <= 8; dy++) blocks.add(new PlannedBlock(hookTop.down(dy), chain));
        blocks.add(new PlannedBlock(hookTop.down(9), decor));

        // 5) Small workshop shed on the near end (z=2..10) as “function” hint
        int shedW = Math.min(9, Math.max(7, width / 2));
        int shedD = 9;
        int shedX = x0 - 1 + (width - shedW) / 2;
        int shedZ = 2;
        for (int x = 0; x < shedW; x++) {
            for (int z = 0; z < shedD; z++) {
                blocks.add(new PlannedBlock(rotateLocal(origin, shedX + x, 1, shedZ + z, facing), deck));
            }
        }
        for (int y = 2; y <= 5; y++) {
            for (int x = 0; x < shedW; x++) {
                blocks.add(new PlannedBlock(rotateLocal(origin, shedX + x, y, shedZ, facing), frame));
                blocks.add(new PlannedBlock(rotateLocal(origin, shedX + x, y, shedZ + shedD - 1, facing), frame));
            }
            for (int z = 0; z < shedD; z++) {
                blocks.add(new PlannedBlock(rotateLocal(origin, shedX, y, shedZ + z, facing), frame));
                blocks.add(new PlannedBlock(rotateLocal(origin, shedX + shedW - 1, y, shedZ + z, facing), frame));
            }
        }
        // door opening on near side (z=0)
        int doorX = shedX + shedW / 2;
        blocks.add(new PlannedBlock(rotateLocal(origin, doorX, 2, shedZ, facing), Blocks.AIR.getDefaultState()));
        blocks.add(new PlannedBlock(rotateLocal(origin, doorX, 3, shedZ, facing), Blocks.AIR.getDefaultState()));
        blocks.add(new PlannedBlock(rotateLocal(origin, doorX - 1, 2, shedZ, facing), Blocks.AIR.getDefaultState()));
        blocks.add(new PlannedBlock(rotateLocal(origin, doorX - 1, 3, shedZ, facing), Blocks.AIR.getDefaultState()));
        blocks.add(new PlannedBlock(rotateLocal(origin, doorX + 2, 3, shedZ + 2, facing), light));

        String desc = String.format("SteampunkAirshipDock (len=%d,w=%d,towerH=%d,craneH=%d,facing=%s)", length, width, towerH, craneH, facing.asString());
        return new GeneratedStructure(null, origin, desc, blocks);
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
        } catch (Throwable ignored) {}
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

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static BlockState getStateOrDefault(ServerWorld world, String id, BlockState def) {
        if (id == null || id.isBlank()) return def;
        try {
            var ident = net.minecraft.util.Identifier.tryParse(id);
            if (ident == null) return def;
            return net.minecraft.registry.Registries.BLOCK.get(ident).getDefaultState();
        } catch (Exception e) {
            return def;
        }
    }
}


