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
import com.formacraft.server.interior.BspFloorPlanGenerator;
import com.formacraft.server.interior.FloorPlanConfig;
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
 * ModernSkyscraperGenerator（摩天楼强原型，v1）
 *
 * 识别性目标：
 * - “少即是多”：方盒子体块堆叠（setback）
 * - 幕墙系统：FRAME + FACADE_CURTAIN
 * - 内透光：INTERNAL_LIGHT（现代风格默认 sea lantern）
 *
 * 触发建议：extra.template = "modern_skyscraper" / "highrise"
 */
public class ModernSkyscraperGenerator implements StructureGenerator {

    private static final FcaLog LOG = FcaLog.of("ModernSkyscraperGenerator");

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

        int w = clampOdd(getIntExtra(spec, "width", spec != null && spec.getFootprint() != null ? spec.getFootprint().getWidth() : 19), 11, 71);
        int d = clampOdd(getIntExtra(spec, "depth", spec != null && spec.getFootprint() != null ? spec.getFootprint().getDepth() : 19), 11, 71);
        int height = clamp(getIntExtra(spec, "height", spec != null ? spec.getHeight() : 64), 24, 220);
        int floors = clamp(getIntExtra(spec, "floors", spec != null ? spec.getFloors() : Math.max(6, height / 6)), 4, 60);

        int setbackEvery = clamp(getIntExtra(spec, "setbackEveryFloors", 6), 3, 12);
        int setbackStep = clamp(getIntExtra(spec, "setbackStep", 2), 1, 4);
        int coreMin = 9;

        // Materials (palette-driven)
        BlockState frame = Blocks.WHITE_CONCRETE.getDefaultState();
        BlockState glass = Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState();
        BlockState floor = Blocks.SMOOTH_STONE.getDefaultState();
        BlockState roof = Blocks.SMOOTH_STONE_SLAB.getDefaultState();
        BlockState internal = Blocks.SEA_LANTERN.getDefaultState();
        BlockState foundation = Blocks.SMOOTH_STONE.getDefaultState();

        if (paletteId != null && !paletteId.isBlank()) {
            frame = PaletteResolver.pick(world, paletteId, "FRAME", origin, 0x5A0001L, frame);
            glass = PaletteResolver.pick(world, paletteId, "FACADE_CURTAIN", origin, 0x5A0002L, glass);
            floor = PaletteResolver.pick(world, paletteId, "FLOORING", origin, 0x5A0003L, floor);
            roof = PaletteResolver.pick(world, paletteId, "FLOOR_SLAB", origin, 0x5A0004L, roof);
            internal = PaletteResolver.pick(world, paletteId, "INTERNAL_LIGHT", origin, 0x5A0005L, internal);
            internal = PaletteResolver.pick(world, paletteId, "LIGHTING", origin, 0x5A0006L, internal);
            foundation = PaletteResolver.pick(world, paletteId, "WALL_FOUNDATION", origin, 0x5A0007L, foundation);
        }

        int floorH = Math.max(4, Math.min(6, height / Math.max(4, floors)));
        int yMax = floors * floorH + 6;

        // clear (best-effort)
        int pad = 4;
        for (int x = -pad; x <= w - 1 + pad; x++) {
            for (int z = -pad; z <= d - 1 + pad; z++) {
                for (int y = 0; y <= yMax; y++) {
                    blocks.add(new PlannedBlock(local(origin, entrance, x, y, z), Blocks.AIR.getDefaultState()));
                }
            }
        }

        // foundation
        fillRect(blocks, origin, entrance, 0, 0, 0, w - 1, 1, d - 1, foundation);

        // stack volumes (setbacks)
        int x0 = 0, z0 = 0, x1 = w - 1, z1 = d - 1;
        int y = 2;
        for (int f = 0; f < floors; f++) {
            // floor slab
            fillRect(blocks, origin, entrance, x0, y, z0, x1, y, z1, floor);

            // facade shell for this floor segment
            int yWallTop = y + floorH - 1;
            for (int yy = y + 1; yy <= yWallTop; yy++) {
                // frame corners
                put(blocks, origin, entrance, x0, yy, z0, frame);
                put(blocks, origin, entrance, x1, yy, z0, frame);
                put(blocks, origin, entrance, x0, yy, z1, frame);
                put(blocks, origin, entrance, x1, yy, z1, frame);

                // edges: glass with occasional mullion frame
                for (int x = x0 + 1; x <= x1 - 1; x++) {
                    BlockState s = ((x % 4) == 0) ? frame : glass;
                    put(blocks, origin, entrance, x, yy, z0, s);
                    put(blocks, origin, entrance, x, yy, z1, s);
                }
                for (int z = z0 + 1; z <= z1 - 1; z++) {
                    BlockState s = ((z % 4) == 0) ? frame : glass;
                    put(blocks, origin, entrance, x0, yy, z, s);
                    put(blocks, origin, entrance, x1, yy, z, s);
                }
            }

            // hollow interior
            fillRect(blocks, origin, entrance, x0 + 1, y + 1, z0 + 1, x1 - 1, yWallTop, z1 - 1, Blocks.AIR.getDefaultState());

            // internal lights (sea lantern) on a sparse grid
            if ((f % 2) == 0) {
                for (int lx = x0 + 2; lx <= x1 - 2; lx += 5) {
                    for (int lz = z0 + 2; lz <= z1 - 2; lz += 5) {
                        put(blocks, origin, entrance, lx, y + 1, lz, internal);
                    }
                }
            }

            // entrance door opening on ground floor (local SOUTH)
            if (f == 0) {
                int mx = (x0 + x1) / 2;
                int zFront = z1;
                for (int yy = y + 1; yy <= y + 3; yy++) {
                    put(blocks, origin, entrance, mx, yy, zFront, Blocks.AIR.getDefaultState());
                    put(blocks, origin, entrance, mx - 1, yy, zFront, Blocks.AIR.getDefaultState());
                }
            }

            y += floorH;

            // setbacks
            if ((f + 1) % setbackEvery == 0) {
                x0 += setbackStep;
                z0 += setbackStep;
                x1 -= setbackStep;
                z1 -= setbackStep;
                if ((x1 - x0 + 1) < coreMin || (z1 - z0 + 1) < coreMin) break;
            }
        }

        // ------------------------------------------------------------
        // Optional functional interior (meta-assembly):
        // If LLM provides extra.floor_plan_logic, compile it into partitions + core stairs.
        // Note: this runs in local (unrotated) coords like the rest of this generator; rotation is applied in put().
        // ------------------------------------------------------------
        FloorPlanConfig fpc = null;
        if (spec != null && spec.getExtra() != null) {
            Object fpl = spec.getExtra().get("floor_plan_logic");
            if (fpl == null) fpl = spec.getExtra().get("floorPlanLogic");
            fpc = FloorPlanConfig.fromExtra(fpl);
        }
        if (fpc != null) {
            BlockState coreWall = frame; // modern core uses frame material
            BlockState roomWall = (fpc.partitionStyle != null && fpc.partitionStyle.contains("OPEN"))
                    ? Blocks.GLASS_PANE.getDefaultState()
                    : frame;
            // Let palette drive room partitions when possible (glass/frames already palette-driven above)
            BspFloorPlanGenerator.apply(
                    blocks,
                    origin,
                    world,
                    w,
                    d,
                    yMax,
                    fpc,
                    BspFloorPlanGenerator.Materials.of(coreWall, roomWall, Blocks.STONE_BRICK_STAIRS.getDefaultState())
            );
        }

        // roof slab (flat roof, minimal parapet)
        fillRect(blocks, origin, entrance, x0, y, z0, x1, y, z1, roof);
        ringRect(blocks, origin, entrance, x0, y + 1, z0, x1, y + 1, z1, frame);

        String desc = "Modern Skyscraper v1";
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    // ---- helpers (rotated local placement) ----

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
    private static int clampOdd(int v, int min, int max) {
        int x = clamp(v, min, max);
        return (x % 2 == 0) ? (x + 1 <= max ? x + 1 : x - 1) : x;
    }
}


