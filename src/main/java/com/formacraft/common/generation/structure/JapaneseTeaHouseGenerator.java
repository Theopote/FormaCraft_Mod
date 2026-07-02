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
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JapaneseTeaHouseGenerator（茶室强原型，v1）
 *
 * 识别性目标（v1 简化但可用）：
 * - 架空地板（桩柱 + 平台）
 * - 缘侧（外围走廊/平台边）
 * - 障子推拉门（简化：正面大开口 + 白色“纸门”面）
 * - 轻量屋顶（小双坡，出檐）
 *
 * 触发建议：extra.template = "japanese_tea_house" / "chashitsu" / "茶室"
 */
public class JapaneseTeaHouseGenerator implements StructureGenerator {

    private static final FcaLog LOG = FcaLog.of("JapaneseTeaHouseGenerator");

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> blocks = new ArrayList<>();

        BuildingStyle style = (spec != null && spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.ASIAN;
        StyleProfile profile = (spec != null) ? StyleProfileRegistry.resolve(spec) : StyleProfileRegistry.forStyle(style);
        DetailPreferences details = profile != null ? profile.details() : null;

        String paletteId = getStringExtra(spec, "paletteId", null);
        if ((paletteId == null || paletteId.isBlank()) && details != null && details.paletteId != null && !details.paletteId.isBlank()) {
            paletteId = details.paletteId.trim();
        }

        Direction entrance = resolveEntranceFacing(spec);

        int w = clampOdd(getIntExtra(spec, "width", spec != null && spec.getFootprint() != null ? spec.getFootprint().getWidth() : 11), 9, 31);
        int d = clampOdd(getIntExtra(spec, "depth", spec != null && spec.getFootprint() != null ? spec.getFootprint().getDepth() : 9), 7, 29);
        int stiltH = clamp(getIntExtra(spec, "stiltHeight", 2), 1, 4);
        int wallH = clamp(getIntExtra(spec, "wallHeight", 4), 3, 6);
        int roofH = clamp(getIntExtra(spec, "roofHeight", 3), 2, 5);
        int overhang = clamp(getIntExtra(spec, "overhang", 2), 1, 3);
        int engawa = clamp(getIntExtra(spec, "engawa", 1), 1, 2);

        // Materials (palette-driven)
        BlockState post = Blocks.SPRUCE_LOG.getDefaultState();
        BlockState frame = Blocks.BIRCH_PLANKS.getDefaultState();
        BlockState deck = Blocks.BIRCH_PLANKS.getDefaultState();
        BlockState deckSlab = Blocks.BIRCH_SLAB.getDefaultState();
        BlockState wall = Blocks.BIRCH_PLANKS.getDefaultState();
        BlockState shoji = Blocks.WHITE_STAINED_GLASS_PANE.getDefaultState();
        BlockState roofStairs = Blocks.DEEPSLATE_TILE_STAIRS.getDefaultState();
        BlockState roofSlab = Blocks.DEEPSLATE_TILE_SLAB.getDefaultState();
        BlockState roofTile = Blocks.DEEPSLATE_TILES.getDefaultState();
        BlockState lantern = Blocks.LANTERN.getDefaultState();

        if (paletteId != null && !paletteId.isBlank()) {
            post = PaletteResolver.pick(world, paletteId, "STRUCTURAL_BEAM", origin, 0x7E1101L, post);
            post = PaletteResolver.pick(world, paletteId, "FRAME", origin, 0x7E1102L, post);
            frame = PaletteResolver.pick(world, paletteId, "FRAME", origin, 0x7E1103L, frame);
            deck = PaletteResolver.pick(world, paletteId, "FLOORING", origin, 0x7E1104L, deck);
            deckSlab = PaletteResolver.pick(world, paletteId, "FLOOR_SLAB", origin, 0x7E1105L, deckSlab);
            wall = PaletteResolver.pick(world, paletteId, "WALL_BASE", origin, 0x7E1106L, wall);
            shoji = PaletteResolver.pick(world, paletteId, "WINDOW", origin, 0x7E1107L, shoji);
            roofTile = PaletteResolver.pick(world, paletteId, "ROOF_TILE", origin, 0x7E1108L, roofTile);
            roofStairs = PaletteResolver.pick(world, paletteId, "ROOF_SLOPE", origin, 0x7E1109L, roofStairs);
            roofSlab = PaletteResolver.pick(world, paletteId, "FLOOR_SLAB", origin, 0x7E110AL, roofSlab);
            lantern = PaletteResolver.pick(world, paletteId, "LIGHTING", origin, 0x7E110BL, lantern);
            lantern = PaletteResolver.pick(world, paletteId, "ROAD_LIGHT", origin, 0x7E110CL, lantern);
        }

        // Clear (best-effort) in a small box around structure
        int pad = Math.max(2, overhang + 2);
        for (int x = -pad; x <= w - 1 + pad; x++) {
            for (int z = -pad; z <= d - 1 + pad; z++) {
                for (int y = 0; y <= stiltH + wallH + roofH + 6; y++) {
                    blocks.add(new PlannedBlock(local(origin, entrance, x, y, z), Blocks.AIR.getDefaultState()));
                }
            }
        }

        int yDeck = stiltH;

        // 1) Stilts: corner posts + periodic posts
        for (int x = -engawa; x <= w - 1 + engawa; x++) {
            for (int z = -engawa; z <= d - 1 + engawa; z++) {
                boolean edge = (x == -engawa || x == w - 1 + engawa || z == -engawa || z == d - 1 + engawa);
                if (!edge) continue;
                boolean corner = (x == -engawa || x == w - 1 + engawa) && (z == -engawa || z == d - 1 + engawa);
                boolean periodic = ((x + z) % 4 == 0);
                if (!corner && !periodic) continue;
                for (int y = 0; y <= yDeck; y++) {
                    put(blocks, origin, entrance, x, y, z, post, null);
                }
            }
        }

        // 2) Platform deck (including engawa ring)
        fillRect(blocks, origin, entrance, -engawa, yDeck, -engawa, w - 1 + engawa, yDeck, d - 1 + engawa, deck, null);
        // A thin slab edge for “轻盈”
        ringRect(blocks, origin, entrance, -engawa - 1, yDeck, -engawa - 1, w + engawa, yDeck, d + engawa, deckSlab, null);

        // 3) Low wall base ring (tea houses often have low wall + screens)
        int y0 = yDeck + 1;
        int y1 = y0 + wallH;
        for (int y = y0; y <= y1; y++) {
            // corners / structural frame
            put(blocks, origin, entrance, 0, y, 0, frame, null);
            put(blocks, origin, entrance, w - 1, y, 0, frame, null);
            put(blocks, origin, entrance, 0, y, d - 1, frame, null);
            put(blocks, origin, entrance, w - 1, y, d - 1, frame, null);
        }

        // Walls + shoji panels
        for (int y = y0; y <= y1; y++) {
            ringRect(blocks, origin, entrance, 0, y, 0, w - 1, y, d - 1, wall, null);
        }
        // Carve interior air
        fillRect(blocks, origin, entrance, 1, y0, 1, w - 2, y1, d - 2, Blocks.AIR.getDefaultState(), null);

        // 4) Shoji sliding door facade (local SOUTH)
        int cx = w / 2;
        int zFront = d - 1;
        int doorW = clamp(getIntExtra(spec, "shojiDoorWidth", 3), 2, Math.max(2, w - 4));
        int x0Door = cx - doorW / 2;
        int x1Door = x0Door + doorW - 1;
        int doorY0 = y0 + 1;
        int doorY1 = Math.min(y1 - 1, doorY0 + 2);
        // opening
        for (int x = x0Door; x <= x1Door; x++) {
            for (int y = doorY0; y <= doorY1; y++) {
                put(blocks, origin, entrance, x, y, zFront, Blocks.AIR.getDefaultState(), null);
            }
        }
        // shoji panels on sides of opening (white “paper”)
        for (int y = doorY0; y <= doorY1; y++) {
            put(blocks, origin, entrance, x0Door - 1, y, zFront, shoji, Direction.SOUTH);
            put(blocks, origin, entrance, x1Door + 1, y, zFront, shoji, Direction.SOUTH);
        }
        // lintel
        for (int x = x0Door - 1; x <= x1Door + 1; x++) {
            put(blocks, origin, entrance, x, doorY1 + 1, zFront, frame, null);
        }
        // small lanterns at entrance
        put(blocks, origin, entrance, x0Door - 3, y0 + 2, zFront + 1, lantern, null);
        put(blocks, origin, entrance, x1Door + 3, y0 + 2, zFront + 1, lantern, null);

        // 5) Light roof: small gable with overhang
        int roofBaseY = y1 + 1;
        addGableRoof(blocks, origin, entrance,
                -overhang, roofBaseY, -overhang,
                w - 1 + overhang, roofBaseY, d - 1 + overhang,
                roofH, roofStairs, roofSlab, roofTile);

        String desc = "Japanese Tea House (chashitsu) v1";
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    // --- helpers (mostly copied pattern from other generators; local SOUTH then rotate to entrance) ---

    private static void addGableRoof(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                     int x0, int y0, int z0, int x1, int y1, int z1,
                                     int h, BlockState stairs, BlockState slab, BlockState cap) {
        // Gable ridge runs along X axis (local), roof slopes on Z.
        int lx0 = x0, lx1 = x1;
        int lz0 = z0, lz1 = z1;
        for (int i = 0; i < h; i++) {
            int y = y0 + i;
            // north slope edge faces inward (south), south slope edge faces inward (north)
            for (int x = lx0; x <= lx1; x++) {
                put(blocks, origin, entrance, x, y, lz0, stairs, Direction.SOUTH);
                put(blocks, origin, entrance, x, y, lz1, stairs, Direction.NORTH);
            }
            lz0 += 1;
            lz1 -= 1;
            if (lz0 > lz1) break;
        }
        // ridge cap
        int topY = y0 + h;
        if (lz0 <= lz1) {
            int zMid = (lz0 + lz1) / 2;
            for (int x = lx0 + 1; x <= lx1 - 1; x++) {
                put(blocks, origin, entrance, x, topY, zMid, slab, null);
            }
            put(blocks, origin, entrance, (lx0 + lx1) / 2, topY + 1, zMid, cap, null);
        }
    }

    private static void fillRect(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                 int x0, int y0, int z0, int x1, int y1, int z1,
                                 BlockState state, Direction face) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    put(blocks, origin, entrance, x, y, z, state, face);
                }
            }
        }
    }

    private static void ringRect(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                 int x0, int y, int z0, int x1, int y1, int z1,
                                 BlockState state, Direction face) {
        int yy = y;
        for (int x = x0; x <= x1; x++) {
            put(blocks, origin, entrance, x, yy, z0, state, face);
            put(blocks, origin, entrance, x, yy, z1, state, face);
        }
        for (int z = z0; z <= z1; z++) {
            put(blocks, origin, entrance, x0, yy, z, state, face);
            put(blocks, origin, entrance, x1, yy, z, state, face);
        }
    }

    private static void put(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                            int x, int y, int z, BlockState state, Direction face) {
        if (state == null) return;
        BlockState s = state;
        if (face != null) {
            s = withFacingIfPossible(s, rotateDir(face, entrance));
        } else {
            s = rotateState(s, entrance);
        }
        blocks.add(new PlannedBlock(local(origin, entrance, x, y, z), s));
    }

    private static BlockPos local(BlockPos origin, Direction entrance, int x, int y, int z) {
        BlockPos p = origin.add(x, y, z);
        return rotatePos(p, origin, entrance);
    }

    private static BlockPos rotatePos(BlockPos p, BlockPos base, Direction entrance) {
        if (entrance == null || entrance == Direction.SOUTH) return p;
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
        if (entrance == null || entrance == Direction.SOUTH || local == null) return local;
        if (!local.getAxis().isHorizontal()) return local;
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
        if (s == null) return null;
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
            if (out.contains(Properties.AXIS)) {
                if (entrance == Direction.EAST || entrance == Direction.WEST) {
                    var axis = out.get(Properties.AXIS);
                    if (axis == Direction.Axis.X) out = out.with(Properties.AXIS, Direction.Axis.Z);
                    else if (axis == Direction.Axis.Z) out = out.with(Properties.AXIS, Direction.Axis.X);
                }
            }
        } catch (Throwable ex) { LOG.debug("best-effort step failed", t); }
        return out;
    }

    private static BlockState withFacingIfPossible(BlockState s, Direction facing) {
        if (s == null || facing == null) return s;
        try {
            if (s.contains(Properties.HORIZONTAL_FACING) && facing.getAxis().isHorizontal()) {
                return s.with(Properties.HORIZONTAL_FACING, facing);
            }
            if (s.contains(Properties.FACING)) {
                return s.with(Properties.FACING, facing);
            }
        } catch (Throwable ex) { LOG.debug("best-effort step failed", t); }
        return s;
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

    private static String getStringExtra(BuildingSpec spec, String key, String def) {
        if (spec == null || spec.getExtra() == null) return def;
        Object v = spec.getExtra().get(key);
        if (v == null) return def;
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


