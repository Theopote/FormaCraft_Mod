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
 * GothicCathedralGenerator（哥特大教堂强原型，v1）
 *
 * 识别性目标（v1）：
 * - 垂直性：双塔 + 尖顶（spires）
 * - 飞扶壁：侧墙外扶壁 + 斜撑（stairs 近似）
 * - 光线：正立面玫瑰花窗（stained glass）
 *
 * 触发建议：extra.template = "gothic_cathedral" / "cathedral" / "notre_dame" / "cologne"
 */
public class GothicCathedralGenerator implements StructureGenerator {

    private static final FcaLog LOG = FcaLog.of("GothicCathedralGenerator");

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> blocks = new ArrayList<>();

        BuildingStyle style = (spec != null && spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.DEFAULT;
        StyleProfile profile = (spec != null) ? StyleProfileRegistry.resolve(spec) : StyleProfileRegistry.forStyle(style);
        DetailPreferences details = profile != null ? profile.details() : null;

        String paletteId = getStringExtra(spec, "paletteId", null);
        if ((paletteId == null || paletteId.isBlank()) && details != null && details.paletteId != null && !details.paletteId.isBlank()) {
            paletteId = details.paletteId.trim();
        }

        Direction entrance = resolveEntranceFacing(spec);

        int w = clampOdd(getIntExtra(spec, "width", spec != null && spec.getFootprint() != null ? spec.getFootprint().getWidth() : 25), 17, 101);
        int d = clampOdd(getIntExtra(spec, "depth", spec != null && spec.getFootprint() != null ? spec.getFootprint().getDepth() : 45), 25, 151);
        int wallH = clamp(getIntExtra(spec, "wallHeight", 16), 12, 40);
        int towerH = clamp(getIntExtra(spec, "towerHeight", (int) Math.round(wallH * 1.25)), wallH + 4, 80);
        int spireH = clamp(getIntExtra(spec, "spireHeight", 10), 6, 26);
        int buttressStep = clamp(getIntExtra(spec, "buttressStep", 5), 4, 8);
        int naveW = Math.max(9, w - 10);
        int naveX0 = (w - naveW) / 2;
        int naveX1 = naveX0 + naveW - 1;

        // Materials (palette-driven)
        BlockState foundation = Blocks.STONE_BRICKS.getDefaultState();
        BlockState wall = Blocks.STONE_BRICKS.getDefaultState();
        BlockState trim = Blocks.POLISHED_BLACKSTONE.getDefaultState();
        BlockState floor = Blocks.POLISHED_ANDESITE.getDefaultState();
        BlockState window = Blocks.RED_STAINED_GLASS_PANE.getDefaultState();
        BlockState roofTile = Blocks.DEEPSLATE_TILES.getDefaultState();
        BlockState roofSlab = Blocks.DEEPSLATE_TILE_SLAB.getDefaultState();
        BlockState roofStairs = Blocks.DEEPSLATE_TILE_STAIRS.getDefaultState();
        BlockState lantern = Blocks.LANTERN.getDefaultState();

        if (paletteId != null && !paletteId.isBlank()) {
            foundation = PaletteResolver.pick(world, paletteId, "WALL_FOUNDATION", origin, 0xC701L, foundation);
            wall = PaletteResolver.pick(world, paletteId, "WALL_BASE", origin, 0xC702L, wall);
            trim = PaletteResolver.pick(world, paletteId, "FRAME", origin, 0xC703L, trim);
            trim = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xC704L, trim);
            floor = PaletteResolver.pick(world, paletteId, "FLOORING", origin, 0xC705L, floor);
            window = PaletteResolver.pick(world, paletteId, "WINDOW", origin, 0xC706L, window);
            roofTile = PaletteResolver.pick(world, paletteId, "ROOF_TILE", origin, 0xC707L, roofTile);
            roofSlab = PaletteResolver.pick(world, paletteId, "FLOOR_SLAB", origin, 0xC708L, roofSlab);
            roofStairs = PaletteResolver.pick(world, paletteId, "ROOF_SLOPE", origin, 0xC709L, roofStairs);
            lantern = PaletteResolver.pick(world, paletteId, "LIGHTING", origin, 0xC70AL, lantern);
            lantern = PaletteResolver.pick(world, paletteId, "ROAD_LIGHT", origin, 0xC70BL, lantern);
        }

        // Clear (best-effort)
        int pad = 6;
        for (int x = -pad; x <= w - 1 + pad; x++) {
            for (int z = -pad; z <= d - 1 + pad; z++) {
                for (int y = 0; y <= towerH + spireH + 12; y++) {
                    blocks.add(new PlannedBlock(local(origin, entrance, x, y, z), Blocks.AIR.getDefaultState()));
                }
            }
        }

        // 1) Foundation slab + floor
        fillRect(blocks, origin, entrance, 0, 0, 0, w - 1, 1, d - 1, foundation);
        fillRect(blocks, origin, entrance, 0, 2, 0, w - 1, 2, d - 1, floor);

        // 2) Nave walls (tall) + hollow interior
        int y0 = 3;
        int y1 = y0 + wallH;
        for (int y = y0; y <= y1; y++) {
            // outer shell
            ringRect(blocks, origin, entrance, naveX0, y, 0, naveX1, y, d - 1, wall);
            // reinforce corners with trim
            put(blocks, origin, entrance, naveX0, y, 0, trim, null);
            put(blocks, origin, entrance, naveX1, y, 0, trim, null);
            put(blocks, origin, entrance, naveX0, y, d - 1, trim, null);
            put(blocks, origin, entrance, naveX1, y, d - 1, trim, null);
        }
        fillRect(blocks, origin, entrance, naveX0 + 1, y0, 1, naveX1 - 1, y1, d - 2, Blocks.AIR.getDefaultState());

        // 3) Side aisles walls (lower) to increase “vertical contrast”
        int aisleY1 = y0 + Math.max(8, wallH - 6);
        for (int y = y0; y <= aisleY1; y++) {
            ringRect(blocks, origin, entrance, 0, y, 0, w - 1, y, d - 1, wall);
        }
        fillRect(blocks, origin, entrance, 1, y0, 1, w - 2, aisleY1, d - 2, Blocks.AIR.getDefaultState());

        // 4) Windows along nave sides (stained)
        int wy = y0 + Math.max(6, wallH / 2);
        for (int z = 4; z <= d - 5; z += buttressStep) {
            put(blocks, origin, entrance, naveX0, wy, z, window, null);
            put(blocks, origin, entrance, naveX1, wy, z, window, null);
        }

        // 5) Entrance portal opening (local SOUTH)
        int mx = w / 2;
        int zFront = d - 1;
        int doorW = 3;
        int doorH = 6;
        for (int dx = -doorW / 2; dx <= doorW / 2; dx++) {
            for (int dy = 1; dy <= doorH; dy++) {
                put(blocks, origin, entrance, mx + dx, y0 + dy, zFront, Blocks.AIR.getDefaultState(), null);
            }
        }
        // pointed arch hint above door
        put(blocks, origin, entrance, mx, y0 + doorH + 1, zFront, trim, null);
        put(blocks, origin, entrance, mx - 1, y0 + doorH, zFront, withFacingIfPossible(roofStairs, rotateDir(Direction.EAST, entrance)), null);
        put(blocks, origin, entrance, mx + 1, y0 + doorH, zFront, withFacingIfPossible(roofStairs, rotateDir(Direction.WEST, entrance)), null);

        // 6) Rose window on front facade (local SOUTH)
        addRoseWindow(blocks, origin, entrance, w, y0 + doorH + 2, zFront, Math.max(4, Math.min(7, w / 4)), window, trim);

        // 7) Roof (steep gable)
        int roofBaseY = y1 + 1;
        addGableRoof(blocks, origin, entrance, naveX0 - 1, roofBaseY, -1, naveX1 + 1, roofBaseY, d, Math.max(6, wallH / 2), roofStairs, roofSlab, roofTile);

        // 8) Flying buttresses (best-effort): outer piers + diagonal braces
        addFlyingButtresses(blocks, origin, entrance, w, d, y0 + 2, y1 - 2, buttressStep, wall, trim, roofStairs, naveX0, naveX1);

        // 9) Front twin towers + spires
        int tw = clampOdd(Math.max(7, w / 5), 7, 15);
        int tLeftX0 = 1;
        int tLeftX1 = tLeftX0 + tw - 1;
        int tRightX1 = w - 2;
        int tRightX0 = tRightX1 - tw + 1;
        int tZ0 = d - tw - 2;
        int tZ1 = d - 2;
        buildTowerWithSpire(blocks, origin, entrance, tLeftX0, tZ0, tLeftX1, tZ1, y0, towerH, spireH, wall, trim, roofStairs, roofSlab);
        buildTowerWithSpire(blocks, origin, entrance, tRightX0, tZ0, tRightX1, tZ1, y0, towerH, spireH, wall, trim, roofStairs, roofSlab);

        // a bit of lighting in entrance
        put(blocks, origin, entrance, mx - 5, y0 + 2, zFront + 1, lantern, null);
        put(blocks, origin, entrance, mx + 5, y0 + 2, zFront + 1, lantern, null);

        String desc = "Gothic Cathedral v1";
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    private static void addRoseWindow(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                      int w, int yCenter, int z, int r,
                                      BlockState glass, BlockState frame) {
        int cx = w / 2;
        int r2 = r * r;
        int rInner2 = Math.max(0, (r - 1) * (r - 1));
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                int d2 = dx * dx + dy * dy;
                if (d2 > r2) continue;
                BlockState s = (d2 >= rInner2) ? frame : glass;
                put(blocks, origin, entrance, cx + dx, yCenter + dy, z, s, null);
            }
        }
        // simple mullion cross
        for (int dy = -r; dy <= r; dy++) put(blocks, origin, entrance, cx, yCenter + dy, z, frame, null);
        for (int dx = -r; dx <= r; dx++) put(blocks, origin, entrance, cx + dx, yCenter, z, frame, null);
    }

    private static void addFlyingButtresses(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                           int w, int d, int yFrom, int yTo, int step,
                                           BlockState wall, BlockState trim, BlockState stairs,
                                           int naveX0, int naveX1) {
        int yMid = (yFrom + yTo) / 2;
        for (int z = 4; z <= d - 5; z += step) {
            // left outer pier
            int xOuterL = 1;
            for (int y = yFrom; y <= yTo; y++) put(blocks, origin, entrance, xOuterL, y, z, wall, null);
            put(blocks, origin, entrance, xOuterL, yTo + 1, z, trim, null);
            // right outer pier
            int xOuterR = w - 2;
            for (int y = yFrom; y <= yTo; y++) put(blocks, origin, entrance, xOuterR, y, z, wall, null);
            put(blocks, origin, entrance, xOuterR, yTo + 1, z, trim, null);

            // diagonal braces to nave wall (stairs approximations)
            // left brace: from outer pier toward naveX0
            int x = xOuterL + 1;
            for (int i = 0; i < 4 && x < naveX0; i++, x++) {
                int yy = yMid + i;
                put(blocks, origin, entrance, x, yy, z, withFacingIfPossible(stairs, rotateDir(Direction.EAST, entrance)), null);
            }
            // right brace: from outer pier toward naveX1
            x = xOuterR - 1;
            for (int i = 0; i < 4 && x > naveX1; i++, x--) {
                int yy = yMid + i;
                put(blocks, origin, entrance, x, yy, z, withFacingIfPossible(stairs, rotateDir(Direction.WEST, entrance)), null);
            }
        }
    }

    private static void buildTowerWithSpire(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                           int x0, int z0, int x1, int z1, int y0,
                                           int towerH, int spireH,
                                           BlockState wall, BlockState trim,
                                           BlockState stairs, BlockState slab) {
        int y1 = y0 + towerH;
        for (int y = y0; y <= y1; y++) {
            ringRect(blocks, origin, entrance, x0, y, z0, x1, y, z1, wall);
            put(blocks, origin, entrance, x0, y, z0, trim, null);
            put(blocks, origin, entrance, x1, y, z0, trim, null);
            put(blocks, origin, entrance, x0, y, z1, trim, null);
            put(blocks, origin, entrance, x1, y, z1, trim, null);
        }
        fillRect(blocks, origin, entrance, x0 + 1, y0, z0 + 1, x1 - 1, y1, z1 - 1, Blocks.AIR.getDefaultState());

        // spire: taper upward
        int lx0 = x0, lz0 = z0, lx1 = x1, lz1 = z1;
        int yBase = y1 + 1;
        for (int i = 0; i < spireH; i++) {
            int y = yBase + i;
            for (int x = lx0; x <= lx1; x++) {
                put(blocks, origin, entrance, x, y, lz0, withFacingIfPossible(stairs, rotateDir(Direction.SOUTH, entrance)), null);
                put(blocks, origin, entrance, x, y, lz1, withFacingIfPossible(stairs, rotateDir(Direction.NORTH, entrance)), null);
            }
            for (int z = lz0; z <= lz1; z++) {
                put(blocks, origin, entrance, lx0, y, z, withFacingIfPossible(stairs, rotateDir(Direction.EAST, entrance)), null);
                put(blocks, origin, entrance, lx1, y, z, withFacingIfPossible(stairs, rotateDir(Direction.WEST, entrance)), null);
            }
            lx0 += 1; lz0 += 1; lx1 -= 1; lz1 -= 1;
            if (lx0 > lx1 || lz0 > lz1) break;
        }
        if (lx0 <= lx1 && lz0 <= lz1) {
            int topY = yBase + spireH;
            put(blocks, origin, entrance, (lx0 + lx1) / 2, topY, (lz0 + lz1) / 2, slab, null);
        }
    }

    private static void addGableRoof(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                     int x0, int y0, int z0, int x1, int y1, int z1,
                                     int h, BlockState stairs, BlockState slab, BlockState cap) {
        int lx0 = x0, lx1 = x1;
        int lz0 = z0, lz1 = z1;
        for (int i = 0; i < h; i++) {
            int y = y0 + i;
            for (int x = lx0; x <= lx1; x++) {
                put(blocks, origin, entrance, x, y, lz0, withFacingIfPossible(stairs, rotateDir(Direction.SOUTH, entrance)), null);
                put(blocks, origin, entrance, x, y, lz1, withFacingIfPossible(stairs, rotateDir(Direction.NORTH, entrance)), null);
            }
            lz0 += 1;
            lz1 -= 1;
            if (lz0 > lz1) break;
        }
        int topY = y0 + h;
        if (lz0 <= lz1) {
            int zMid = (lz0 + lz1) / 2;
            for (int x = lx0 + 1; x <= lx1 - 1; x++) {
                put(blocks, origin, entrance, x, topY, zMid, slab, null);
            }
            put(blocks, origin, entrance, (lx0 + lx1) / 2, topY + 1, zMid, cap, null);
        }
    }

    // --- primitives + rotation helpers (same conventions as other generators) ---

    private static void fillRect(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                 int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) for (int y = y0; y <= y1; y++) put(blocks, origin, entrance, x, y, z, s, null);
    }

    private static void ringRect(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                 int x0, int y, int z0, int x1, int y1, int z1, BlockState s) {
        int yy = y;
        for (int x = x0; x <= x1; x++) {
            put(blocks, origin, entrance, x, yy, z0, s, null);
            put(blocks, origin, entrance, x, yy, z1, s, null);
        }
        for (int z = z0; z <= z1; z++) {
            put(blocks, origin, entrance, x0, yy, z, s, null);
            put(blocks, origin, entrance, x1, yy, z, s, null);
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
        } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
        return out;
    }

    private static BlockState withFacingIfPossible(BlockState s, Direction facing) {
        if (s == null || facing == null) return s;
        try {
            if (s.contains(Properties.HORIZONTAL_FACING) && facing.getAxis().isHorizontal()) return s.with(Properties.HORIZONTAL_FACING, facing);
            if (s.contains(Properties.FACING)) return s.with(Properties.FACING, facing);
        } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
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
        } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
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

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private static int clampOdd(int v, int min, int max) {
        int x = clamp(v, min, max);
        return (x % 2 == 0) ? (x + 1 <= max ? x + 1 : x - 1) : x;
    }
}


