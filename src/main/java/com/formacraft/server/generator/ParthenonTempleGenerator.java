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
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ParthenonTempleGenerator（帕特农神庙强原型，v1）
 *
 * 识别性目标：
 * - 矩形台基（stylobate）
 * - 四周密集柱廊（peristyle）
 * - 正立面三角门楣（pediment）
 *
 * 触发建议：extra.template = "parthenon" / "classical_temple"
 */
public class ParthenonTempleGenerator implements StructureGenerator {

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

        int w = clampOdd(getIntExtra(spec, "width", spec != null && spec.getFootprint() != null ? spec.getFootprint().getWidth() : 23), 17, 81);
        int d = clampOdd(getIntExtra(spec, "depth", spec != null && spec.getFootprint() != null ? spec.getFootprint().getDepth() : 35), 21, 101);
        int baseH = clamp(getIntExtra(spec, "baseHeight", 3), 2, 6);
        int colH = clamp(getIntExtra(spec, "columnHeight", 8), 6, 14);
        int roofH = clamp(getIntExtra(spec, "roofHeight", 5), 3, 9);
        int spacing = clamp(getIntExtra(spec, "colSpacing", (details != null ? details.colonnadeSpacing : 2)), 2, 4);

        // Materials (palette-driven)
        BlockState foundation = Blocks.SMOOTH_QUARTZ.getDefaultState();
        BlockState floor = Blocks.SMOOTH_QUARTZ.getDefaultState();
        BlockState wall = Blocks.QUARTZ_BLOCK.getDefaultState();
        BlockState pillar = Blocks.QUARTZ_PILLAR.getDefaultState();
        BlockState trim = Blocks.CHISELED_QUARTZ_BLOCK.getDefaultState();
        BlockState roofStairs = Blocks.QUARTZ_STAIRS.getDefaultState();
        BlockState roofSlab = Blocks.QUARTZ_SLAB.getDefaultState();
        BlockState roofTile = Blocks.SMOOTH_STONE.getDefaultState();

        if (paletteId != null && !paletteId.isBlank()) {
            foundation = PaletteResolver.pick(world, paletteId, "WALL_FOUNDATION", origin, 0xB001L, foundation);
            floor = PaletteResolver.pick(world, paletteId, "FLOORING", origin, 0xB002L, floor);
            wall = PaletteResolver.pick(world, paletteId, "WALL_BASE", origin, 0xB003L, wall);
            pillar = PaletteResolver.pick(world, paletteId, "PILLAR", origin, 0xB004L, pillar);
            trim = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xB005L, trim);
            roofStairs = PaletteResolver.pick(world, paletteId, "ROOF_SLOPE", origin, 0xB006L, roofStairs);
            roofSlab = PaletteResolver.pick(world, paletteId, "FLOOR_SLAB", origin, 0xB007L, roofSlab);
            roofTile = PaletteResolver.pick(world, paletteId, "ROOF_TILE", origin, 0xB008L, roofTile);
        }

        // Clear (best-effort)
        int pad = 4;
        for (int x = -pad; x <= w - 1 + pad; x++) {
            for (int z = -pad; z <= d - 1 + pad; z++) {
                for (int y = 0; y <= baseH + colH + roofH + 8; y++) {
                    blocks.add(new PlannedBlock(local(origin, entrance, x, y, z), Blocks.AIR.getDefaultState()));
                }
            }
        }

        // 1) Stylobate (3-tier look: fill + outline trims)
        int outer = 2;
        fillRect(blocks, origin, entrance, -outer, 0, -outer, w - 1 + outer, baseH - 1, d - 1 + outer, foundation);
        ringRect(blocks, origin, entrance, -outer, baseH, -outer, w - 1 + outer, baseH, d - 1 + outer, roofSlab);

        // 2) Peristyle columns on the stylobate top (y=baseH+1..)
        int yCol0 = baseH + 1;
        int yCol1 = yCol0 + colH;

        for (int x = 1; x <= w - 2; x += spacing) {
            placeColumn(blocks, origin, entrance, x, yCol0, 0, yCol1, pillar, trim);
            placeColumn(blocks, origin, entrance, x, yCol0, d - 1, yCol1, pillar, trim);
        }
        for (int z = 1; z <= d - 2; z += spacing) {
            placeColumn(blocks, origin, entrance, 0, yCol0, z, yCol1, pillar, trim);
            placeColumn(blocks, origin, entrance, w - 1, yCol0, z, yCol1, pillar, trim);
        }

        // 3) Entablature band on top of columns (horizontal line)
        int yEnt = yCol1 + 1;
        ringRect(blocks, origin, entrance, 0, yEnt, 0, w - 1, yEnt, d - 1, trim);
        ringRect(blocks, origin, entrance, 0, yEnt + 1, 0, w - 1, yEnt + 1, d - 1, roofSlab);

        // 4) Inner cella (simple)
        int cx0 = 3, cz0 = 3, cx1 = w - 4, cz1 = d - 4;
        for (int y = yCol0; y <= yCol1 - 1; y++) {
            ringRect(blocks, origin, entrance, cx0, y, cz0, cx1, y, cz1, wall);
        }
        fillRect(blocks, origin, entrance, cx0 + 1, yCol0, cz0 + 1, cx1 - 1, yCol1 - 1, cz1 - 1, Blocks.AIR.getDefaultState());
        fillRect(blocks, origin, entrance, cx0 + 1, baseH, cz0 + 1, cx1 - 1, baseH, cz1 - 1, floor);

        // 5) Gable roof + pediment on entrance side (local SOUTH)
        int roofBaseY = yEnt + 2;
        addGableRoof(blocks, origin, entrance, -1, roofBaseY, -1, w, roofBaseY, d, roofH, roofStairs, roofSlab, roofTile);
        addPediment(blocks, origin, entrance, w, d, roofBaseY, roofH, trim, roofSlab);

        // 6) Entrance steps (local SOUTH)
        int mx = w / 2;
        for (int dx = -3; dx <= 3; dx++) {
            put(blocks, origin, entrance, mx + dx, baseH, d + 1, withFacingIfPossible(roofStairs, rotateDir(Direction.NORTH, entrance)));
        }

        String desc = "Parthenon Temple v1";
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    private static void placeColumn(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                    int x, int y0, int z, int y1,
                                    BlockState pillar, BlockState capital) {
        for (int y = y0; y <= y1; y++) {
            put(blocks, origin, entrance, x, y, z, pillar);
        }
        put(blocks, origin, entrance, x, y1 + 1, z, capital);
        put(blocks, origin, entrance, x, y1 + 2, z, capital);
    }

    private static void addPediment(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                    int w, int d, int roofBaseY, int roofH,
                                    BlockState trim, BlockState slab) {
        // On entrance facade (local SOUTH): z = d
        int z = d;
        int mid = w / 2;
        int span = Math.min(8, Math.max(5, w / 3));
        int x0 = Math.max(1, mid - span);
        int x1 = Math.min(w - 2, mid + span);
        int yPeak = roofBaseY + roofH + 1;
        for (int x = x0; x <= x1; x++) {
            int dx = Math.abs(x - mid);
            int y = yPeak - dx / 2;
            put(blocks, origin, entrance, x, y, z, slab);
            if ((dx & 1) == 0) put(blocks, origin, entrance, x, y - 1, z, trim);
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
                put(blocks, origin, entrance, x, y, lz0, withFacingIfPossible(stairs, rotateDir(Direction.SOUTH, entrance)));
                put(blocks, origin, entrance, x, y, lz1, withFacingIfPossible(stairs, rotateDir(Direction.NORTH, entrance)));
            }
            lz0 += 1;
            lz1 -= 1;
            if (lz0 > lz1) break;
        }
        int topY = y0 + h;
        if (lz0 <= lz1) {
            int zMid = (lz0 + lz1) / 2;
            for (int x = lx0 + 1; x <= lx1 - 1; x++) {
                put(blocks, origin, entrance, x, topY, zMid, slab);
            }
            put(blocks, origin, entrance, (lx0 + lx1) / 2, topY + 1, zMid, cap);
        }
    }

    // ---- basic rotated local placement helpers ----

    private static void fillRect(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                 int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) for (int y = y0; y <= y1; y++) put(blocks, origin, entrance, x, y, z, s);
    }

    private static void ringRect(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                 int x0, int y, int z0, int x1, int y1, int z1, BlockState s) {
        int yy = y;
        for (int x = x0; x <= x1; x++) { put(blocks, origin, entrance, x, yy, z0, s); put(blocks, origin, entrance, x, yy, z1, s); }
        for (int z = z0; z <= z1; z++) { put(blocks, origin, entrance, x0, yy, z, s); put(blocks, origin, entrance, x1, yy, z, s); }
    }

    private static void put(List<PlannedBlock> blocks, BlockPos origin, Direction entrance, int x, int y, int z, BlockState s) {
        if (s == null) return;
        blocks.add(new PlannedBlock(local(origin, entrance, x, y, z), rotateState(s, entrance)));
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
        } catch (Throwable ignored) {}
        return out;
    }

    private static BlockState withFacingIfPossible(BlockState s, Direction facing) {
        if (s == null || facing == null) return s;
        try {
            if (s.contains(Properties.HORIZONTAL_FACING) && facing.getAxis().isHorizontal()) return s.with(Properties.HORIZONTAL_FACING, facing);
            if (s.contains(Properties.FACING)) return s.with(Properties.FACING, facing);
        } catch (Throwable ignored) {}
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
        } catch (Throwable ignored) {}
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
        if (spec == null || spec.getExtra() == null) return def;
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

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    private static int clampOdd(int v, int min, int max) {
        int x = clamp(v, min, max);
        return (x % 2 == 0) ? (x + 1 <= max ? x + 1 : x - 1) : x;
    }
}


