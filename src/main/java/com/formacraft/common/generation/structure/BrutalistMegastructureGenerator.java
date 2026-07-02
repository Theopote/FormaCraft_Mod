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
 * BrutalistMegastructureGenerator（粗野主义巨构，v1）
 *
 * 识别性目标：
 * - 倒金字塔（头重脚轻）：高度越高，体量越外扩（cantilever）
 * - 重复模块：module_grid（通过条带/凹凸与少量 slit 开口表现）
 * - 材质：灰混凝土/粉末/粗石（由 paletteId 语义决定）
 *
 * 触发建议：extra.template = "brutalism_megastructure" / "soviet_megastructure" / "brutalism"
 */
public class BrutalistMegastructureGenerator implements StructureGenerator {

    private static final FcaLog LOG = FcaLog.of("BrutalistMegastructureGenerator");

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> blocks = new ArrayList<>();

        BuildingStyle style = (spec != null && spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.MODERN;
        StyleProfile profile = (spec != null) ? StyleProfileRegistry.resolve(spec) : StyleProfileRegistry.forStyle(style);
        DetailPreferences details = profile != null ? profile.details() : null;

        String paletteId = null;
        if (spec != null && spec.getExtra() != null && spec.getExtra().get("paletteId") != null) {
            paletteId = String.valueOf(spec.getExtra().get("paletteId")).trim();
        }
        if ((paletteId == null || paletteId.isBlank()) && details != null && details.paletteId != null && !details.paletteId.isBlank()) {
            paletteId = details.paletteId.trim();
        }

        Direction entrance = resolveEntranceFacing(spec);

        int baseW = clampOdd(getIntExtra(spec, "baseWidth", (spec != null && spec.getFootprint() != null ? spec.getFootprint().getWidth() : 17)), 11, 81);
        int baseD = clampOdd(getIntExtra(spec, "baseDepth", (spec != null && spec.getFootprint() != null ? spec.getFootprint().getDepth() : 17)), 11, 81);
        int height = clamp(getIntExtra(spec, "height", spec != null ? spec.getHeight() : 42), 18, 180);
        int floorH = clamp(getIntExtra(spec, "floorHeight", 4), 3, 7);
        int floors = Math.max(4, height / floorH);
        int expandEvery = clamp(getIntExtra(spec, "expandEveryFloors", 3), 2, 8);
        int expandStep = clamp(getIntExtra(spec, "expandStep", 1), 1, 4);
        int maxExpand = clamp(getIntExtra(spec, "maxExpand", 8), 2, 22);
        boolean addSlits = getBoolExtra(spec, "slitWindows", true);

        // Materials (palette-driven)
        BlockState foundation = Blocks.SMOOTH_STONE.getDefaultState();
        BlockState wall = Blocks.GRAY_CONCRETE.getDefaultState();
        BlockState trim = Blocks.POLISHED_ANDESITE.getDefaultState();
        BlockState floor = Blocks.SMOOTH_STONE.getDefaultState();
        BlockState roof = Blocks.SMOOTH_STONE_SLAB.getDefaultState();
        BlockState slit = Blocks.IRON_BARS.getDefaultState();
        BlockState internal = Blocks.SEA_LANTERN.getDefaultState();

        if (paletteId != null && !paletteId.isBlank()) {
            foundation = PaletteResolver.pick(world, paletteId, "WALL_FOUNDATION", origin, 0xB00A01L, foundation);
            wall = PaletteResolver.pick(world, paletteId, "WALL_BASE", origin, 0xB00A02L, wall);
            trim = PaletteResolver.pick(world, paletteId, "FRAME", origin, 0xB00A03L, trim);
            trim = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xB00A04L, trim);
            floor = PaletteResolver.pick(world, paletteId, "FLOORING", origin, 0xB00A05L, floor);
            roof = PaletteResolver.pick(world, paletteId, "FLOOR_SLAB", origin, 0xB00A06L, roof);
            slit = PaletteResolver.pick(world, paletteId, "WINDOW", origin, 0xB00A07L, slit);
            internal = PaletteResolver.pick(world, paletteId, "INTERNAL_LIGHT", origin, 0xB00A08L, internal);
            internal = PaletteResolver.pick(world, paletteId, "LIGHTING", origin, 0xB00A09L, internal);
        }

        // Start with a narrow base, expand upward (inverted pyramid)
        int x0 = 0;
        int z0 = 0;
        int x1 = baseW - 1;
        int z1 = baseD - 1;
        int y0 = 0;

        // clear (best-effort)
        int pad = maxExpand + 8;
        int yMax = floors * floorH + 12;
        for (int x = -pad; x <= baseW - 1 + pad; x++) {
            for (int z = -pad; z <= baseD - 1 + pad; z++) {
                for (int y = 0; y <= yMax; y++) {
                    blocks.add(new PlannedBlock(local(origin, entrance, x, y, z), Blocks.AIR.getDefaultState()));
                }
            }
        }

        // foundation slab
        fillRect(blocks, origin, entrance, x0, y0, z0, x1, y0 + 1, z1, foundation);

        int expand = 0;
        int y = y0 + 2;
        for (int f = 0; f < floors; f++) {
            int fx0 = x0 - expand;
            int fz0 = z0 - expand;
            int fx1 = x1 + expand;
            int fz1 = z1 + expand;

            // floor slab
            fillRect(blocks, origin, entrance, fx0, y, fz0, fx1, y, fz1, floor);

            int yTop = y + floorH - 1;
            for (int yy = y + 1; yy <= yTop; yy++) {
                // module grid: horizontal bands every 3 blocks
                boolean band = ((yy - y0) % 3) == 0;
                BlockState shell = band ? trim : wall;
                ringRect(blocks, origin, entrance, fx0, yy, fz0, fx1, yy, fz1, shell);

                // subtle vertical ribs
                for (int x = fx0; x <= fx1; x++) {
                    if ((x & 3) == 0) {
                        put(blocks, origin, entrance, x, yy, fz0, trim);
                        put(blocks, origin, entrance, x, yy, fz1, trim);
                    }
                }
                for (int z = fz0; z <= fz1; z++) {
                    if ((z & 3) == 0) {
                        put(blocks, origin, entrance, fx0, yy, z, trim);
                        put(blocks, origin, entrance, fx1, yy, z, trim);
                    }
                }

                // slit windows (rare, oppressive)
                if (addSlits && (f % 2 == 0) && (yy == y + 2)) {
                    int midX = (fx0 + fx1) / 2;
                    int midZ = (fz0 + fz1) / 2;
                    put(blocks, origin, entrance, midX, yy, fz1, slit);
                    put(blocks, origin, entrance, midX, yy, fz0, slit);
                    put(blocks, origin, entrance, fx0, yy, midZ, slit);
                    put(blocks, origin, entrance, fx1, yy, midZ, slit);
                }
            }

            // hollow interior
            fillRect(blocks, origin, entrance, fx0 + 1, y + 1, fz0 + 1, fx1 - 1, yTop, fz1 - 1, Blocks.AIR.getDefaultState());

            // internal light (sparse, governmental vibe)
            if ((f % 3) == 0) {
                for (int lx = fx0 + 2; lx <= fx1 - 2; lx += 7) {
                    for (int lz = fz0 + 2; lz <= fz1 - 2; lz += 7) {
                        put(blocks, origin, entrance, lx, y + 2, lz, internal);
                    }
                }
            }

            // entrance door opening on ground floor, local SOUTH (fz1 side)
            if (f == 0) {
                int midX = (fx0 + fx1) / 2;
                for (int yy = y + 1; yy <= y + 4; yy++) {
                    put(blocks, origin, entrance, midX, yy, fz1, Blocks.AIR.getDefaultState());
                    put(blocks, origin, entrance, midX - 1, yy, fz1, Blocks.AIR.getDefaultState());
                }
            }

            y += floorH;
            if ((f + 1) % expandEvery == 0 && expand < maxExpand) {
                expand = Math.min(maxExpand, expand + expandStep);
            }
        }

        // roof + parapet
        int topExpand = expand;
        int rx0 = x0 - topExpand;
        int rz0 = z0 - topExpand;
        int rx1 = x1 + topExpand;
        int rz1 = z1 + topExpand;
        fillRect(blocks, origin, entrance, rx0, y, rz0, rx1, y, rz1, roof);
        ringRect(blocks, origin, entrance, rx0, y + 1, rz0, rx1, y + 1, rz1, trim);

        String desc = "Brutalist Megastructure v1";
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
        } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
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
        } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
        return Direction.SOUTH;
    }

    private static int getIntExtra(BuildingSpec spec, String key, int def) {
        return StructureSpecParsers.extraInt(spec, key, def);
    }

    private static boolean getBoolExtra(BuildingSpec spec, String key, boolean def) {
        if (spec == null || spec.getExtra() == null || key == null) return def;
        Object v = spec.getExtra().get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty()) return def;
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private static int clampOdd(int v, int min, int max) {
        int x = clamp(v, min, max);
        return (x % 2 == 0) ? (x + 1 <= max ? x + 1 : x - 1) : x;
    }
}


