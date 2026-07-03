package com.formacraft.common.generation.structure;

import com.formacraft.common.generation.structure.util.StructureSpecParsers;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.model.build.BuildingType;
import com.formacraft.common.model.build.Footprint;
import com.formacraft.common.style.profile.DetailPreferences;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.server.material.PaletteResolver;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ModernOfficeCampusGenerator（现代办公园区强原型，v1）
 *
 * 识别性目标：
 * - 多栋简洁盒子楼（委托 OfficeBlockGenerator）
 * - 共享 podium（底座平台）
 * - 连廊（skybridges）把楼连接起来
 *
 * 触发建议：extra.template = "modern_office_campus" / "office_park" / "campus"
 */
public class ModernOfficeCampusGenerator implements StructureGenerator {

    private static final FcaLog LOG = FcaLog.of("ModernOfficeCampusGenerator");

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
        String styleProfileId = getStringExtra(spec, "styleProfileId", null);
        if (styleProfileId == null || styleProfileId.isBlank()) styleProfileId = (profile != null ? profile.id() : null);

        Direction entrance = resolveEntranceFacing(spec);

        int blockW = clampOdd(getIntExtra(spec, "blockWidth", 13), 9, 31);
        int blockD = clampOdd(getIntExtra(spec, "blockDepth", 13), 9, 31);
        int blockH = clamp(getIntExtra(spec, "blockHeight", 26), 14, 80);
        int spacing = clamp(getIntExtra(spec, "spacing", 18), 12, 40);
        int towers = clamp(getIntExtra(spec, "count", 4), 2, 6);

        int podiumMargin = clamp(getIntExtra(spec, "podiumMargin", 6), 4, 14);
        int podiumY = clamp(getIntExtra(spec, "podiumY", 1), 0, 3);
        int bridgeY = clamp(getIntExtra(spec, "bridgeY", 9), 6, 18);
        boolean bridges = getBoolExtra(spec, "bridges", true);

        // Materials (palette-driven)
        BlockState podium = Blocks.SMOOTH_STONE.getDefaultState();
        BlockState podiumEdge = Blocks.SMOOTH_STONE_SLAB.getDefaultState();
        BlockState deck = Blocks.SMOOTH_STONE.getDefaultState();
        BlockState rail = Blocks.IRON_BARS.getDefaultState();
        BlockState internal = Blocks.SEA_LANTERN.getDefaultState();

        if (paletteId != null && !paletteId.isBlank()) {
            podium = PaletteResolver.pick(world, paletteId, "FLOORING", origin, 0xC4A001L, podium);
            podiumEdge = PaletteResolver.pick(world, paletteId, "FLOOR_SLAB", origin, 0xC4A002L, podiumEdge);
            deck = PaletteResolver.pick(world, paletteId, "BRIDGE_DECK", origin, 0xC4A003L, deck);
            rail = PaletteResolver.pick(world, paletteId, "BRIDGE_RAIL", origin, 0xC4A004L, rail);
            rail = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xC4A005L, rail);
            internal = PaletteResolver.pick(world, paletteId, "INTERNAL_LIGHT", origin, 0xC4A006L, internal);
            internal = PaletteResolver.pick(world, paletteId, "LIGHTING", origin, 0xC4A007L, internal);
        }

        // Layout: place buildings on a simple grid around origin (local coords; +Z is SOUTH)
        // Default: 2x2, extend to 3x2 when count > 4.
        List<BlockPos> anchors = new ArrayList<>();
        int half = spacing / 2;
        anchors.add(new BlockPos(-half, 0, -half));
        anchors.add(new BlockPos(half, 0, -half));
        anchors.add(new BlockPos(-half, 0, half));
        anchors.add(new BlockPos(half, 0, half));
        if (towers > 4) {
            anchors.add(new BlockPos(-half - spacing, 0, 0));
            if (towers > 5) anchors.add(new BlockPos(half + spacing, 0, 0));
        }
        while (anchors.size() > towers) anchors.remove(anchors.size() - 1);

        // Compute podium bounds in local space
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos a : anchors) {
            int ax0 = a.getX() - blockW / 2;
            int az0 = a.getZ() - blockD / 2;
            int ax1 = ax0 + blockW - 1;
            int az1 = az0 + blockD - 1;
            minX = Math.min(minX, ax0);
            minZ = Math.min(minZ, az0);
            maxX = Math.max(maxX, ax1);
            maxZ = Math.max(maxZ, az1);
        }
        minX -= podiumMargin;
        minZ -= podiumMargin;
        maxX += podiumMargin;
        maxZ += podiumMargin;

        // Clear a bit (best-effort)
        int pad = 6;
        int yMax = Math.max(bridgeY + 6, blockH + 12);
        for (int x = minX - pad; x <= maxX + pad; x++) {
            for (int z = minZ - pad; z <= maxZ + pad; z++) {
                for (int y = 0; y <= yMax; y++) {
                    blocks.add(new PlannedBlock(local(origin, entrance, x, y, z), Blocks.AIR.getDefaultState()));
                }
            }
        }

        // Podium slab (one layer) + edge ring
        fillRect(blocks, origin, entrance, minX, podiumY, minZ, maxX, podiumY, maxZ, podium);
        ringRect(blocks, origin, entrance, minX - 1, podiumY, minZ - 1, maxX + 1, podiumY, maxZ + 1, podiumEdge);

        // Spawn office blocks (delegate)
        OfficeBlockGenerator office = new OfficeBlockGenerator();
        List<BlockPos> worldAnchors = new ArrayList<>(anchors.size());
        for (BlockPos a : anchors) {
            BlockPos buildingOriginLocal = new BlockPos(a.getX() - blockW / 2, podiumY + 1, a.getZ() - blockD / 2);
            BlockPos buildingOriginWorld = local(origin, entrance, buildingOriginLocal.getX(), buildingOriginLocal.getY(), buildingOriginLocal.getZ());
            worldAnchors.add(buildingOriginWorld);

            BuildingSpec b = makeOfficeBlockSpec(blockW, blockD, blockH, paletteId, styleProfileId, entrance);
            blocks.addAll(office.generate(b, buildingOriginWorld, world).getBlocks());
        }

        // Skybridges: connect adjacent anchors in local space with simple straight decks
        if (bridges && anchors.size() >= 2) {
            // Connect 0-1, 2-3, and 0-2 by default (like an H)
            connect( blocks, origin, entrance, anchors.get(0), anchors.get(1), bridgeY, deck, rail, internal);
            if (anchors.size() >= 4) connect(blocks, origin, entrance, anchors.get(2), anchors.get(3), bridgeY, deck, rail, internal);
            connect( blocks, origin, entrance, anchors.get(0), anchors.get(2), bridgeY, deck, rail, internal);
            // Optional extra connection for 5th/6th
            if (anchors.size() >= 5) connect(blocks, origin, entrance, anchors.get(4), anchors.get(0), bridgeY, deck, rail, internal);
            if (anchors.size() >= 6) connect(blocks, origin, entrance, anchors.get(5), anchors.get(1), bridgeY, deck, rail, internal);
        }

        String desc = "Modern Office Campus v1";
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    private static BuildingSpec makeOfficeBlockSpec(int w, int d, int h, String paletteId, String styleProfileId, Direction entranceFacing) {
        BuildingSpec s = new BuildingSpec();
        // BuildingType has no OFFICE in v1; keep CUSTOM while routing via template=office_block.
        s.setType(BuildingType.CUSTOM);
        s.setStyle(BuildingStyle.MODERN);
        s.setFootprint(new Footprint(w, d));
        s.setHeight(h);
        s.setFloors(Math.max(3, h / 6));
        Map<String, Object> extra = new HashMap<>();
        extra.put("template", "office_block");
        if (paletteId != null && !paletteId.isBlank()) extra.put("paletteId", paletteId);
        if (styleProfileId != null && !styleProfileId.isBlank()) extra.put("styleProfileId", styleProfileId);
        extra.put("layout", Map.of(
                "entranceFacing", entranceFacing.asString(),
                "symmetry", "MIRROR",
                "plan", "none",
                "courtyard", false
        ));
        s.setExtra(extra);
        return s;
    }

    private static void connect(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                BlockPos a, BlockPos b, int y,
                                BlockState deck, BlockState rail, BlockState light) {
        // a/b are local-space centers; build a 3-wide bridge along axis-aligned L path
        int ax = a.getX();
        int az = a.getZ();
        int bx = b.getX();
        int bz = b.getZ();

        // L path: x then z
        int xStep = (bx >= ax) ? 1 : -1;
        for (int x = ax; x != bx; x += xStep) {
            placeBridgeSlice(blocks, origin, entrance, x, az, y, deck, rail, light);
        }
        int zStep = (bz >= az) ? 1 : -1;
        for (int z = az; z != bz; z += zStep) {
            placeBridgeSlice(blocks, origin, entrance, bx, z, y, deck, rail, light);
        }
        placeBridgeSlice(blocks, origin, entrance, bx, bz, y, deck, rail, light);
    }

    private static void placeBridgeSlice(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                         int cx, int cz, int y,
                                         BlockState deck, BlockState rail, BlockState light) {
        for (int dx = -1; dx <= 1; dx++) {
            put(blocks, origin, entrance, cx + dx, y, cz, deck);
            // rails on edges
            if (dx == -1 || dx == 1) {
                put(blocks, origin, entrance, cx + dx, y + 1, cz, rail);
            }
        }
        if (((cx + cz) & 7) == 0) {
            put(blocks, origin, entrance, cx, y + 1, cz, light);
        }
    }

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

    private static boolean getBoolExtra(BuildingSpec spec, String key, boolean def) {
        if (spec == null || spec.getExtra() == null || key == null) return def;
        Object v = spec.getExtra().get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty()) return def;
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
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

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private static int clampOdd(int v, int min, int max) {
        int x = clamp(v, min, max);
        return (x % 2 == 0) ? (x + 1 <= max ? x + 1 : x - 1) : x;
    }
}


