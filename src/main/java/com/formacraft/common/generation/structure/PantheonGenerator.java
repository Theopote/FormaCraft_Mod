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
 * PantheonGenerator（万神殿强原型，v1）
 *
 * 识别性目标：
 * - 圆形鼓座（drum）
 * - 半球穹顶（dome）+ 顶部天窗（oculus）
 * - 前廊（portico）：柱廊 + 简化 pediment
 *
 * 触发建议：extra.template = "pantheon"
 */
public class PantheonGenerator implements StructureGenerator {

    private static final FcaLog LOG = FcaLog.of("PantheonGenerator");

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

        int r = clamp(getIntExtra(spec, "radius", (spec != null && spec.getFootprint() != null ? spec.getFootprint().getRadius() : 12)), 9, 45);
        int drumH = clamp(getIntExtra(spec, "drumHeight", 10), 8, 24);
        int domeR = clamp(getIntExtra(spec, "domeRadius", r), 8, 50);
        int oculusR = clamp(getIntExtra(spec, "oculusRadius", 2), 1, 4);
        int wallT = clamp(getIntExtra(spec, "wallThickness", 2), 1, 4);
        int baseH = clamp(getIntExtra(spec, "baseHeight", 2), 1, 5);

        // Materials (palette-driven)
        BlockState foundation = Blocks.SMOOTH_QUARTZ.getDefaultState();
        BlockState wall = Blocks.QUARTZ_BLOCK.getDefaultState();
        BlockState floor = Blocks.SMOOTH_QUARTZ.getDefaultState();
        BlockState pillar = Blocks.QUARTZ_PILLAR.getDefaultState();
        BlockState trim = Blocks.CHISELED_QUARTZ_BLOCK.getDefaultState();
        BlockState dome = Blocks.SMOOTH_STONE.getDefaultState();
        BlockState domeSlab = Blocks.SMOOTH_QUARTZ_SLAB.getDefaultState();
        BlockState roofStairs = Blocks.QUARTZ_STAIRS.getDefaultState();
        BlockState lantern = Blocks.LANTERN.getDefaultState();

        if (paletteId != null && !paletteId.isBlank()) {
            foundation = PaletteResolver.pick(world, paletteId, "WALL_FOUNDATION", origin, 0x941001L, foundation);
            wall = PaletteResolver.pick(world, paletteId, "WALL_BASE", origin, 0x941002L, wall);
            floor = PaletteResolver.pick(world, paletteId, "FLOORING", origin, 0x941003L, floor);
            pillar = PaletteResolver.pick(world, paletteId, "PILLAR", origin, 0x941004L, pillar);
            trim = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0x941005L, trim);
            dome = PaletteResolver.pick(world, paletteId, "ROOF_TILE", origin, 0x941006L, dome);
            domeSlab = PaletteResolver.pick(world, paletteId, "FLOOR_SLAB", origin, 0x941007L, domeSlab);
            roofStairs = PaletteResolver.pick(world, paletteId, "ROOF_SLOPE", origin, 0x941008L, roofStairs);
            lantern = PaletteResolver.pick(world, paletteId, "LIGHTING", origin, 0x941009L, lantern);
            lantern = PaletteResolver.pick(world, paletteId, "ROAD_LIGHT", origin, 0x94100AL, lantern);
        }

        // Clear (best-effort)
        int pad = domeR + 6;
        for (int x = -pad; x <= pad; x++) {
            for (int z = -pad; z <= pad + 10; z++) {
                for (int y = 0; y <= baseH + drumH + domeR + 8; y++) {
                    blocks.add(new PlannedBlock(local(origin, entrance, x, y, z), Blocks.AIR.getDefaultState()));
                }
            }
        }

        // 1) Base disk (stylobate-ish)
        for (int y = 0; y < baseH; y++) {
            fillDisk(blocks, origin, entrance, 0, y, 0, r + 2, foundation);
        }
        fillDisk(blocks, origin, entrance, 0, baseH, 0, r + 2, domeSlab);

        // 2) Drum: ring walls + interior hollow + floor
        int y0 = baseH + 1;
        int y1 = y0 + drumH;
        for (int y = y0; y <= y1; y++) {
            ringDisk(blocks, origin, entrance, 0, y, 0, r, r - wallT + 1, wall);
        }
        // interior air
        for (int y = y0; y <= y1; y++) {
            fillDisk(blocks, origin, entrance, 0, y, 0, r - wallT, Blocks.AIR.getDefaultState());
        }
        // floor
        fillDisk(blocks, origin, entrance, 0, y0, 0, r - wallT, floor);

        // 3) Entrance opening (local SOUTH)
        int doorW = 3;
        int doorH = 5;
        for (int dx = -doorW / 2; dx <= doorW / 2; dx++) {
            for (int dy = 1; dy <= doorH; dy++) {
                put(blocks, origin, entrance, dx, y0 + dy, r, Blocks.AIR.getDefaultState(), null);
            }
        }
        // simple lintel
        for (int dx = -doorW / 2 - 1; dx <= doorW / 2 + 1; dx++) {
            put(blocks, origin, entrance, dx, y0 + doorH + 1, r, trim, null);
        }

        // 4) Dome (hemisphere): build from y1+1 up
        int domeBaseY = y1 + 1;
        for (int dy = 0; dy <= domeR; dy++) {
            int rr = (int) Math.round(Math.sqrt(Math.max(0.0, (double) domeR * domeR - (double) dy * dy)));
            if (rr <= 0) continue;
            // shell thickness 1 (stone) + slabs near top for smoothing
            BlockState mat = (dy > domeR - 2) ? domeSlab : dome;
            ringDisk(blocks, origin, entrance, 0, domeBaseY + dy, 0, rr, Math.max(0, rr - 1), mat);
        }
        // oculus: carve a vertical hole at top
        int oculusY0 = domeBaseY + domeR - 2;
        int oculusY1 = domeBaseY + domeR + 2;
        for (int y = oculusY0; y <= oculusY1; y++) {
            fillDisk(blocks, origin, entrance, 0, y, 0, oculusR, Blocks.AIR.getDefaultState());
        }

        // 5) Portico: rectangular front colonnade + pediment (local SOUTH)
        int portD = 7;
        int portW = Math.min(2 * r - 1, 17);
        int px0 = -portW / 2;
        int px1 = portW / 2;
        int pz0 = r + 1;
        int pz1 = r + portD;
        // floor
        fillRect(blocks, origin, entrance, px0, baseH, pz0, px1, baseH, pz1, floor);
        // columns (front row)
        int colCount = clamp(getIntExtra(spec, "porticoColumns", 6), 4, 10);
        int step = Math.max(2, (portW - 2) / (colCount - 1));
        int yCol0 = baseH + 1;
        int yCol1 = yCol0 + 7;
        int x = px0 + 1;
        for (int i = 0; i < colCount; i++) {
            placeColumn(blocks, origin, entrance, x, yCol0, pz1, yCol1, pillar, trim);
            x += step;
        }
        // entablature + pediment
        int yEnt = yCol1 + 1;
        for (int xx = px0; xx <= px1; xx++) {
            put(blocks, origin, entrance, xx, yEnt, pz1, trim, null);
            put(blocks, origin, entrance, xx, yEnt + 1, pz1, domeSlab, null);
        }
        addSimplePediment(blocks, origin, entrance, px0, px1, pz1, yEnt + 1, roofStairs, domeSlab);

        // lanterns on portico corners
        put(blocks, origin, entrance, px0, yCol0 + 2, pz1 + 1, lantern, null);
        put(blocks, origin, entrance, px1, yCol0 + 2, pz1 + 1, lantern, null);

        String desc = "Pantheon v1";
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    private static void addSimplePediment(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                          int x0, int x1, int z, int yBase,
                                          BlockState stairs, BlockState slab) {
        int mid = (x0 + x1) / 2;
        int span = Math.min(8, Math.max(5, (x1 - x0) / 2));
        int a0 = mid - span;
        int a1 = mid + span;
        int yPeak = yBase + 4;
        for (int x = a0; x <= a1; x++) {
            int dx = Math.abs(x - mid);
            int y = yPeak - dx / 2;
            put(blocks, origin, entrance, x, y, z, slab, null);
            if (dx == span) {
                // edges: little sloped hint
                Direction f = (x < mid) ? Direction.EAST : Direction.WEST;
                put(blocks, origin, entrance, x, y - 1, z, withFacingIfPossible(stairs, rotateDir(f, entrance)), null);
            }
        }
    }

    private static void placeColumn(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                    int x, int y0, int z, int y1,
                                    BlockState pillar, BlockState capital) {
        for (int y = y0; y <= y1; y++) {
            put(blocks, origin, entrance, x, y, z, pillar, null);
        }
        put(blocks, origin, entrance, x, y1 + 1, z, capital, null);
        put(blocks, origin, entrance, x, y1 + 2, z, capital, null);
    }

    // --- primitives ---

    private static void fillDisk(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                 int cx, int y, int cz, int r, BlockState s) {
        int rr = Math.max(0, r);
        int rr2 = rr * rr;
        for (int x = -rr; x <= rr; x++) {
            for (int z = -rr; z <= rr; z++) {
                if (x * x + z * z <= rr2) {
                    put(blocks, origin, entrance, cx + x, y, cz + z, s, null);
                }
            }
        }
    }

    private static void ringDisk(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                 int cx, int y, int cz, int rOuter, int rInner, BlockState s) {
        int ro = Math.max(0, rOuter);
        int ri = Math.max(0, rInner);
        int ro2 = ro * ro;
        int ri2 = ri * ri;
        for (int x = -ro; x <= ro; x++) {
            for (int z = -ro; z <= ro; z++) {
                int d2 = x * x + z * z;
                if (d2 <= ro2 && d2 >= ri2) {
                    put(blocks, origin, entrance, cx + x, y, cz + z, s, null);
                }
            }
        }
    }

    private static void fillRect(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                 int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) for (int y = y0; y <= y1; y++) put(blocks, origin, entrance, x, y, z, s, null);
    }

    private static void put(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                            int x, int y, int z, BlockState state, Direction face) {
        if (state == null) return;
        BlockState s = state;
        if (face != null) s = withFacingIfPossible(s, rotateDir(face, entrance));
        else s = rotateState(s, entrance);
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
            if (s.contains(Properties.HORIZONTAL_FACING) && facing.getAxis().isHorizontal()) return s.with(Properties.HORIZONTAL_FACING, facing);
            if (s.contains(Properties.FACING)) return s.with(Properties.FACING, facing);
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

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
}


