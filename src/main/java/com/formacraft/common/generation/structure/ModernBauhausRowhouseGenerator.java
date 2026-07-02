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
 * ModernBauhausRowhouseGenerator（包豪斯/联排盒子强原型，v1）
 *
 * 识别性目标：
 * - 平屋顶 + 女儿墙（parapet）
 * - 盒子体块（联排共享侧墙）
 * - “ribbon windows” 水平带状窗
 * - 内透光（sea lantern / INTERNAL_LIGHT）
 *
 * 触发建议：extra.template = "bauhaus_rowhouse" / "bauhaus" / "rowhouse" / "townhouse" / "terrace"
 */
public class ModernBauhausRowhouseGenerator implements StructureGenerator {

    private static final FcaLog LOG = FcaLog.of("ModernBauhausRowhouseGenerator");

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> blocks = new ArrayList<>();

        BuildingStyle style = (spec != null && spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.MODERN;
        StyleProfile profile = (spec != null) ? StyleProfileRegistry.resolve(spec) : StyleProfileRegistry.forStyle(style);
        DetailPreferences details = profile != null ? profile.details() : null;

        String paletteId = getStringExtra(spec, "paletteId", null);
        if ((paletteId == null || paletteId.isBlank()) && details != null && details.paletteId != null && !details.paletteId.isBlank()) {
            paletteId = details.paletteId.trim();
        }

        Direction entrance = resolveEntranceFacing(spec);

        int units = clamp(getIntExtra(spec, "units", 5), 2, 12);
        int unitW = clampOdd(getIntExtra(spec, "unitWidth", 7), 5, 15);
        int depth = clampOdd(getIntExtra(spec, "depth", 9), 7, 19);
        int floors = clamp(getIntExtra(spec, "floors", 2), 1, 4);
        int floorH = clamp(getIntExtra(spec, "floorHeight", 4), 3, 6);
        int height = floors * floorH;

        int totalW = units * unitW;
        int x0All = 0;
        int x1All = totalW - 1;
        int z0 = 0;
        int z1 = depth - 1;

        // Materials (palette-driven)
        BlockState foundation = Blocks.SMOOTH_STONE.getDefaultState();
        BlockState wall = Blocks.WHITE_CONCRETE.getDefaultState();
        BlockState frame = Blocks.WHITE_CONCRETE.getDefaultState();
        BlockState glass = Blocks.LIGHT_BLUE_STAINED_GLASS_PANE.getDefaultState();
        BlockState floor = Blocks.SMOOTH_STONE.getDefaultState();
        BlockState roof = Blocks.SMOOTH_STONE_SLAB.getDefaultState();
        BlockState internal = Blocks.SEA_LANTERN.getDefaultState();

        if (paletteId != null && !paletteId.isBlank()) {
            foundation = PaletteResolver.pick(world, paletteId, "WALL_FOUNDATION", origin, 0xBA001L, foundation);
            wall = PaletteResolver.pick(world, paletteId, "WALL_BASE", origin, 0xBA002L, wall);
            frame = PaletteResolver.pick(world, paletteId, "FRAME", origin, 0xBA003L, frame);
            glass = PaletteResolver.pick(world, paletteId, "WINDOW", origin, 0xBA004L, glass);
            // Modern ribbon windows read better with a curtain-ish pane sometimes.
            glass = PaletteResolver.pick(world, paletteId, "FACADE_CURTAIN", origin, 0xBA005L, glass);
            floor = PaletteResolver.pick(world, paletteId, "FLOORING", origin, 0xBA006L, floor);
            roof = PaletteResolver.pick(world, paletteId, "FLOOR_SLAB", origin, 0xBA007L, roof);
            internal = PaletteResolver.pick(world, paletteId, "INTERNAL_LIGHT", origin, 0xBA008L, internal);
            internal = PaletteResolver.pick(world, paletteId, "LIGHTING", origin, 0xBA009L, internal);
        }

        // clear (best-effort)
        int pad = 4;
        for (int x = x0All - pad; x <= x1All + pad; x++) {
            for (int z = z0 - pad; z <= z1 + pad; z++) {
                for (int y = 0; y <= height + 10; y++) {
                    blocks.add(new PlannedBlock(local(origin, entrance, x, y, z), Blocks.AIR.getDefaultState()));
                }
            }
        }

        // foundation + ground slab
        fillRect(blocks, origin, entrance, x0All, 0, z0, x1All, 1, z1, foundation);
        fillRect(blocks, origin, entrance, x0All, 2, z0, x1All, 2, z1, floor);

        // outer shell (rowhouse shares inner party walls; we only need outer perimeter)
        int y0 = 3;
        int y1 = y0 + height;
        for (int y = y0; y <= y1; y++) {
            ringRect(blocks, origin, entrance, x0All, y, z0, x1All, y, z1, wall);
            // vertical frames rhythm
            for (int x = x0All; x <= x1All; x += 4) {
                put(blocks, origin, entrance, x, y, z0, frame);
                put(blocks, origin, entrance, x, y, z1, frame);
            }
        }

        // floors + interior air + internal lights
        for (int f = 0; f < floors; f++) {
            int fy = y0 + f * floorH;
            // floor slab
            fillRect(blocks, origin, entrance, x0All + 1, fy, z0 + 1, x1All - 1, fy, z1 - 1, floor);
            // hollow volume above
            fillRect(blocks, origin, entrance, x0All + 1, fy + 1, z0 + 1, x1All - 1, Math.min(y1, fy + floorH - 1), z1 - 1, Blocks.AIR.getDefaultState());
            // lights (sparse)
            if ((f & 1) == 0) {
                for (int x = x0All + 2; x <= x1All - 2; x += 6) {
                    for (int z = z0 + 2; z <= z1 - 2; z += 5) {
                        put(blocks, origin, entrance, x, fy + 2, z, internal);
                    }
                }
            }
        }

        // ribbon windows: a long band on front/back at mid height of each floor
        for (int f = 0; f < floors; f++) {
            int wy = y0 + f * floorH + 2;
            for (int x = x0All + 1; x <= x1All - 1; x++) {
                put(blocks, origin, entrance, x, wy, z0, glass);
                put(blocks, origin, entrance, x, wy, z1, glass);
            }
        }

        // entrances: one per unit on front facade (local SOUTH => z=z1)
        int doorH = Math.min(4, floorH);
        for (int i = 0; i < units; i++) {
            int ux0 = i * unitW;
            int mx = ux0 + unitW / 2;
            for (int dy = 1; dy <= doorH; dy++) {
                put(blocks, origin, entrance, mx, y0 + dy, z1, Blocks.AIR.getDefaultState());
                put(blocks, origin, entrance, mx - 1, y0 + dy, z1, Blocks.AIR.getDefaultState());
            }
            // small canopy hint
            put(blocks, origin, entrance, mx, y0 + doorH + 1, z1 + 1, roof);
            put(blocks, origin, entrance, mx - 1, y0 + doorH + 1, z1 + 1, roof);
        }

        // flat roof + parapet
        int roofY = y1 + 1;
        fillRect(blocks, origin, entrance, x0All, roofY, z0, x1All, roofY, z1, roof);
        ringRect(blocks, origin, entrance, x0All, roofY + 1, z0, x1All, roofY + 1, z1, frame);

        String desc = "Modern Bauhaus Rowhouse v1";
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    // ---- helpers ----

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
        } catch (Throwable ex) { LOG.debug("best-effort step failed", t); }
        return out;
    }

    private static Direction resolveEntranceFacing(BuildingSpec spec) {
        if (spec == null || spec.getExtra() == null) return Direction.SOUTH;
        try {
            Object layoutObj = spec.getExtra().get("layout");
            if (layoutObj instanceof Map<?, ?> m) {
                Object ef = m.get("entranceFacing");
                if (ef != null) {
                    String s = String.valueOf(ef).trim().toUpperCase(java.util.Locale.ROOT);
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
        if (spec == null || spec.getExtra() == null || key == null) return def;
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


