package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.style.profile.DetailPreferences;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
import com.formacraft.common.typology.TypologyParamResolver;
import com.formacraft.server.generation.structure.util.StructureSpecParsers;
import com.formacraft.server.material.PaletteResolver;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parametric tiered mountain palace builder (typology: {@code tiered_mountain_palace}).
 * <p>
 * VERTICAL_STACK：石砌台地 + 逐层退台宫城（可选布达拉宫 keep/wings）+ 门窗楼梯 + 多单元金顶。
 * Clearance is scoped to interiors/openings only — no padded AABB AIR pre-clear.
 */
public final class TieredMountainPalaceBuilder {

    public static final String TYPOLOGY_ID = "tiered_mountain_palace";
    public static final String LANDMARK_POTALA = "potala_palace";

    private static final FcaLog LOG = FcaLog.of("TieredMountainPalaceBuilder");
    private static final int MIN_SPAN = 8;

    private TieredMountainPalaceBuilder() {}

    /** Local-axis tier footprint. */
    private record TierRect(int x0, int z0, int x1, int z1, int y0, int y1, boolean upperPalace) {
        int width() { return x1 - x0 + 1; }
        int depth() { return z1 - z0 + 1; }
    }

    /** Multi-block roof plate (never a single-column finial). */
    private record RoofUnit(int x0, int z0, int x1, int z1, int y, int rise, BlockState fill, BlockState ridge) {
        int footprint() { return Math.max(0, x1 - x0 + 1) * Math.max(0, z1 - z0 + 1); }
    }

    /** Last-write-wins local voxel buffer (coords before entrance rotation). */
    private static final class VoxelMap {
        private final LinkedHashMap<Long, BlockState> cells = new LinkedHashMap<>();

        void put(int x, int y, int z, BlockState s) {
            if (s == null) return;
            cells.put(pack(x, y, z), s);
        }

        BlockState get(int x, int y, int z) {
            return cells.get(pack(x, y, z));
        }

        List<PlannedBlock> emit(BlockPos origin, Direction entrance) {
            List<PlannedBlock> out = new ArrayList<>(cells.size());
            for (var e : cells.entrySet()) {
                int x = unpackX(e.getKey());
                int y = unpackY(e.getKey());
                int z = unpackZ(e.getKey());
                out.add(new PlannedBlock(local(origin, entrance, x, y, z), rotateState(e.getValue(), entrance)));
            }
            return out;
        }

        int airCount() {
            int n = 0;
            for (BlockState s : cells.values()) {
                if (s != null && s.isAir()) n++;
            }
            return n;
        }

        private static long pack(int x, int y, int z) {
            return ((long) (x & 0x3FF) << 20) | ((long) (y & 0x3FF) << 10) | (long) (z & 0x3FF);
        }

        private static int unpackX(long k) { return (int) ((k >> 20) & 0x3FF); }
        private static int unpackY(long k) { return (int) ((k >> 10) & 0x3FF); }
        private static int unpackZ(long k) { return (int) (k & 0x3FF); }
    }

    public static GeneratedStructure fromBuildingSpec(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        return generate(TypologyParamResolver.fromBuildingSpec(spec, TYPOLOGY_ID), origin, world, spec);
    }

    public static GeneratedStructure generate(
            Map<String, Object> params,
            BlockPos origin,
            ServerWorld world,
            BuildingSpec specHint
    ) {
        BuildingSpec spec = specHint != null ? specHint : new BuildingSpec();
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
        if (spec.getExtra() != null) {
            merged.putAll(spec.getExtra());
        }
        if (params != null) {
            merged.putAll(params);
        }
        spec.setExtra(merged);
        return generateFromSpec(spec, origin, world);
    }

    private static GeneratedStructure generateFromSpec(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        BuildingStyle style = (spec != null && spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.ASIAN;
        StyleProfile profile = (spec != null) ? StyleProfileRegistry.resolve(spec) : StyleProfileRegistry.forStyle(style);
        DetailPreferences details = profile != null ? profile.details() : null;

        String paletteId = getStringExtra(spec, "paletteId", null);
        if ((paletteId == null || paletteId.isBlank()) && details != null
                && details.paletteId != null && !details.paletteId.isBlank()) {
            paletteId = details.paletteId.trim();
        }

        Direction entrance = StructureSpecParsers.resolveEntranceFacing(spec, Direction.SOUTH);
        boolean potala = isPotalaLandmark(spec);

        int bw = clampOdd(getIntExtra(spec, "baseWidth", 48), 24, 96);
        int bd = clampOdd(getIntExtra(spec, "baseDepth", 40), 20, 88);
        int requestedTiers = clamp(getIntExtra(spec, "tiers", 5), 2, 8);
        int tierInset = clamp(getIntExtra(spec, "tierInset", 3), 2, 6);
        int tierHeight = clamp(getIntExtra(spec, "tierHeight", 6), 4, 12);
        int platformH = clamp(getIntExtra(spec, "platformHeight", 4), 2, 10);

        BlockState foundation = Blocks.STONE_BRICKS.getDefaultState();
        BlockState wall = Blocks.WHITE_TERRACOTTA.getDefaultState();
        BlockState upperWall = Blocks.RED_TERRACOTTA.getDefaultState();
        BlockState trim = Blocks.RED_TERRACOTTA.getDefaultState();
        BlockState accent = Blocks.GOLD_BLOCK.getDefaultState();
        BlockState floor = Blocks.POLISHED_ANDESITE.getDefaultState();
        BlockState window = Blocks.IRON_BARS.getDefaultState();
        BlockState stair = Blocks.STONE_BRICK_STAIRS.getDefaultState();

        if (paletteId != null && !paletteId.isBlank()) {
            foundation = PaletteResolver.pick(world, paletteId, "WALL_FOUNDATION", origin, 0xB10001L, foundation);
            wall = PaletteResolver.pick(world, paletteId, "WALL_BASE", origin, 0xB10002L, wall);
            trim = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xB10003L, trim);
            accent = PaletteResolver.pick(world, paletteId, "ROOF_TILE", origin, 0xB10004L, accent);
            floor = PaletteResolver.pick(world, paletteId, "FLOORING", origin, 0xB10005L, floor);
            upperWall = trim;
        }

        List<TierRect> keepTiers = computeConcentricTiers(bw, bd, platformH, requestedTiers, tierInset, tierHeight);
        int actualTiers = keepTiers.size();
        if (actualTiers < requestedTiers) {
            LOG.warn("tiered_mountain_palace early-stop: requestedTiers={} actualTiers={} inset={} base={}x{}",
                    requestedTiers, actualTiers, tierInset, bw, bd);
        }

        VoxelMap voxels = new VoxelMap();
        List<RoofUnit> roofs = new ArrayList<>();

        // Stone platform + trim ring (no exterior pad clear)
        fillRect(voxels, 0, 0, 0, bw - 1, platformH - 1, bd - 1, foundation);
        ringRect(voxels, 0, platformH - 1, 0, bw - 1, bd - 1, trim);

        int upperBand = Math.max(1, (actualTiers + 2) / 3);
        for (int i = 0; i < actualTiers; i++) {
            TierRect t = keepTiers.get(i);
            boolean upper = i >= actualTiers - upperBand;
            BlockState wallMat = upper ? upperWall : wall;
            placeMassShell(voxels, t, floor, wallMat, trim);
        }

        if (potala && actualTiers >= 2) {
            placePotalaWings(voxels, roofs, bw, bd, platformH, tierHeight, actualTiers,
                    floor, wall, upperWall, trim, accent);
        }

        if (!keepTiers.isEmpty()) {
            TierRect base = keepTiers.get(0);
            carveEntrance(voxels, base, platformH);
            placeWindows(voxels, keepTiers, window);
            placeInterTierStairs(voxels, keepTiers, stair, floor);

            TierRect top = keepTiers.get(actualTiers - 1);
            roofs.add(new RoofUnit(
                    top.x0(), top.z0(), top.x1(), top.z1(),
                    top.y1() + 1, top.width() >= 7 ? 2 : 1, accent, trim));
        }

        for (RoofUnit roof : roofs) {
            placeRoofUnit(voxels, roof);
        }

        List<PlannedBlock> blocks = voxels.emit(origin, entrance);
        String mode = potala ? "potala" : "generic";
        String desc = String.format(
                "Tiered Mountain Palace (tiered_mountain_palace, mode=%s, %dx%d, requestedTiers=%d, actualTiers=%d, roofs=%d, air=%d)",
                mode, bw, bd, requestedTiers, actualTiers, roofs.size(), voxels.airCount()
        );
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    private static List<TierRect> computeConcentricTiers(
            int bw, int bd, int platformH, int requestedTiers, int tierInset, int tierHeight) {
        List<TierRect> tiers = new ArrayList<>();
        int upperBand = Math.max(1, (requestedTiers + 2) / 3);
        for (int tier = 0; tier < requestedTiers; tier++) {
            int inset = tier * tierInset;
            int x0 = inset;
            int z0 = inset;
            int x1 = bw - 1 - inset;
            int z1 = bd - 1 - inset;
            if (x1 - x0 + 1 < MIN_SPAN || z1 - z0 + 1 < MIN_SPAN) {
                break;
            }
            int y0 = platformH + tier * tierHeight;
            int y1 = y0 + tierHeight - 1;
            boolean upper = tier >= requestedTiers - upperBand;
            tiers.add(new TierRect(x0, z0, x1, z1, y0, y1, upper));
        }
        // Re-tag upper palace using actual count
        int actual = tiers.size();
        int band = Math.max(1, (actual + 2) / 3);
        List<TierRect> retagged = new ArrayList<>(actual);
        for (int i = 0; i < actual; i++) {
            TierRect t = tiers.get(i);
            retagged.add(new TierRect(t.x0(), t.z0(), t.x1(), t.z1(), t.y0(), t.y1(), i >= actual - band));
        }
        return retagged;
    }

    private static void placeMassShell(VoxelMap voxels, TierRect t,
                                       BlockState floor, BlockState wall, BlockState trim) {
        fillRect(voxels, t.x0(), t.y0(), t.z0(), t.x1(), t.y0(), t.z1(), floor);
        for (int y = t.y0() + 1; y <= t.y1(); y++) {
            ringRect(voxels, t.x0(), y, t.z0(), t.x1(), t.z1(), wall);
        }
        if (t.width() >= 5 && t.depth() >= 5) {
            fillRect(voxels, t.x0() + 1, t.y0() + 1, t.z0() + 1, t.x1() - 1, t.y1(), t.z1() - 1,
                    Blocks.AIR.getDefaultState());
        }
        ringRect(voxels, t.x0(), t.y1(), t.z0(), t.x1(), t.z1(), trim);
    }

    /**
     * Side wings at mid height, ~60% of lowest keep width, flush to the front third of the platform.
     */
    private static void placePotalaWings(
            VoxelMap voxels, List<RoofUnit> roofs,
            int bw, int bd, int platformH, int tierHeight, int actualTiers,
            BlockState floor, BlockState wall, BlockState upperWall, BlockState trim, BlockState accent) {
        TierRect keep0 = new TierRect(0, 0, bw - 1, bd - 1, platformH, platformH + tierHeight - 1, false);
        // Use concentric first tier inset as keep footprint reference
        int keepInset = Math.min(tierHeight, (bw - MIN_SPAN) / 2);
        int kx0 = keepInset;
        int kz0 = keepInset;
        int kx1 = bw - 1 - keepInset;
        int kz1 = bd - 1 - keepInset;
        if (kx1 - kx0 + 1 < MIN_SPAN) return;

        int wingW = Math.max(MIN_SPAN, (int) Math.round((kx1 - kx0 + 1) * 0.6));
        int wingD = Math.max(6, (bd - 1) / 3);
        int wingH = Math.max(tierHeight, tierHeight * Math.max(1, actualTiers / 2));
        int wingY0 = platformH;
        int wingY1 = wingY0 + wingH - 1;
        int frontZ0 = bd - wingD;

        // Left wing (west)
        int lx0 = 0;
        int lx1 = Math.min(kx0 - 1, wingW - 1);
        if (lx1 - lx0 + 1 >= 6) {
            TierRect left = new TierRect(lx0, frontZ0, lx1, bd - 1, wingY0, wingY1, true);
            placeMassShell(voxels, left, floor, upperWall, trim);
            roofs.add(new RoofUnit(left.x0(), left.z0(), left.x1(), left.z1(),
                    left.y1() + 1, 1, accent, trim));
            placeWingWindows(voxels, left, windowOrBars(upperWall), upperWall);
        }

        // Right wing (east)
        int rx1 = bw - 1;
        int rx0 = Math.max(kx1 + 1, bw - wingW);
        if (rx1 - rx0 + 1 >= 6) {
            TierRect right = new TierRect(rx0, frontZ0, rx1, bd - 1, wingY0, wingY1, true);
            placeMassShell(voxels, right, floor, upperWall, trim);
            roofs.add(new RoofUnit(right.x0(), right.z0(), right.x1(), right.z1(),
                    right.y1() + 1, 1, accent, trim));
            placeWingWindows(voxels, right, windowOrBars(upperWall), upperWall);
        }

        // Suppress unused keep0 warning by using it for door-side clearance on platform approach
        fillRect(voxels, keep0.x0() + keep0.width() / 2 - 1, platformH, keep0.z1(),
                keep0.x0() + keep0.width() / 2 + 1, platformH + 2, keep0.z1(),
                Blocks.AIR.getDefaultState());
    }

    private static BlockState windowOrBars(BlockState fallback) {
        return Blocks.IRON_BARS.getDefaultState();
    }

    private static void placeWingWindows(VoxelMap voxels, TierRect t, BlockState window, BlockState wall) {
        int y = t.y0() + 2;
        if (y >= t.y1()) return;
        for (int x = t.x0() + 2; x <= t.x1() - 2; x += 4) {
            if (isWallCell(voxels, x, y, t.z1(), wall)) {
                voxels.put(x, y, t.z1(), window);
                voxels.put(x, y + 1, t.z1(), window);
            }
        }
    }

    private static boolean isWallCell(VoxelMap voxels, int x, int y, int z, BlockState wall) {
        BlockState s = voxels.get(x, y, z);
        return s != null && !s.isAir();
    }

    private static void carveEntrance(VoxelMap voxels, TierRect base, int platformH) {
        int mx = (base.x0() + base.x1()) / 2;
        int zFront = base.z1();
        int y0 = platformH;
        // Opening 3 wide × 3 high
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy < 3; dy++) {
                voxels.put(mx + dx, y0 + dy, zFront, Blocks.AIR.getDefaultState());
            }
        }
        // Door leaves on center column
        BlockState lower = Blocks.DARK_OAK_DOOR.getDefaultState()
                .with(Properties.HORIZONTAL_FACING, Direction.SOUTH)
                .with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        BlockState upper = Blocks.DARK_OAK_DOOR.getDefaultState()
                .with(Properties.HORIZONTAL_FACING, Direction.SOUTH)
                .with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        voxels.put(mx, y0, zFront, lower);
        voxels.put(mx, y0 + 1, zFront, upper);
        // Approach clearance just outside door (still within footprint)
        voxels.put(mx, y0, zFront - 1, Blocks.AIR.getDefaultState());
        voxels.put(mx, y0 + 1, zFront - 1, Blocks.AIR.getDefaultState());
        voxels.put(mx, y0 + 2, zFront - 1, Blocks.AIR.getDefaultState());
    }

    private static void placeWindows(VoxelMap voxels, List<TierRect> tiers, BlockState window) {
        for (TierRect t : tiers) {
            int y = t.y0() + 2;
            if (y >= t.y1()) continue;
            // South facade (skip entrance bay on ground tier)
            for (int x = t.x0() + 2; x <= t.x1() - 2; x += 5) {
                if (t == tiers.get(0) && Math.abs(x - (t.x0() + t.x1()) / 2) <= 2) continue;
                voxels.put(x, y, t.z1(), window);
                voxels.put(x, y + 1, t.z1(), window);
            }
            // North facade
            for (int x = t.x0() + 2; x <= t.x1() - 2; x += 5) {
                voxels.put(x, y, t.z0(), window);
                voxels.put(x, y + 1, t.z0(), window);
            }
            // East / west
            for (int z = t.z0() + 2; z <= t.z1() - 2; z += 5) {
                voxels.put(t.x0(), y, z, window);
                voxels.put(t.x0(), y + 1, z, window);
                voxels.put(t.x1(), y, z, window);
                voxels.put(t.x1(), y + 1, z, window);
            }
        }
    }

    /**
     * Straight stair run along the north (back) interior wall, rising through each tier slab opening.
     * Headroom clears {@code y+1..y+3} so {@link WalkabilityGraph} climb edges (need bodyClear at fromY+3) succeed.
     */
    private static void placeInterTierStairs(VoxelMap voxels, List<TierRect> tiers,
                                             BlockState stairBase, BlockState floor) {
        if (tiers.size() < 2) return;
        for (int i = 0; i < tiers.size() - 1; i++) {
            TierRect lower = tiers.get(i);
            TierRect upper = tiers.get(i + 1);
            int rise = upper.y0() - lower.y0();
            if (rise <= 0) continue;
            int sx = lower.x0() + 2;
            if (sx >= lower.x1() - 1) sx = (lower.x0() + lower.x1()) / 2;
            int sz0 = lower.z0() + 2;
            if (sz0 + rise >= lower.z1() - 1) {
                placeEastRunStairs(voxels, lower, upper, rise, stairBase, floor);
                continue;
            }
            for (int step = 0; step < rise; step++) {
                int y = lower.y0() + step;
                int z = sz0 + step;
                BlockState stair = stairBase.with(Properties.HORIZONTAL_FACING, Direction.SOUTH);
                voxels.put(sx, y, z, stair);
                clearHeadroom(voxels, sx, y, z);
            }
            // Arrival: last stair at (sx, upper.y0()-1, landZ) → floor at (sx, upper.y0(), landZ+1)
            int landZ = sz0 + rise - 1;
            voxels.put(sx, upper.y0(), landZ, Blocks.AIR.getDefaultState()); // shaft through slab
            voxels.put(sx, upper.y0(), landZ + 1, floor);
            clearHeadroom(voxels, sx, upper.y0() - 1, landZ);
            clearHeadroom(voxels, sx, upper.y0(), landZ + 1);
        }
    }

    private static void placeEastRunStairs(VoxelMap voxels, TierRect lower, TierRect upper,
                                           int rise, BlockState stairBase, BlockState floor) {
        int sz = lower.z0() + 2;
        int sx0 = lower.x0() + 2;
        for (int step = 0; step < rise; step++) {
            int y = lower.y0() + step;
            int x = sx0 + step;
            if (x >= lower.x1() - 1) break;
            BlockState stair = stairBase.with(Properties.HORIZONTAL_FACING, Direction.EAST);
            voxels.put(x, y, sz, stair);
            clearHeadroom(voxels, x, y, sz);
        }
        int landX = Math.min(lower.x1() - 2, sx0 + rise - 1);
        voxels.put(landX, upper.y0(), sz, Blocks.AIR.getDefaultState());
        voxels.put(landX + 1, upper.y0(), sz, floor);
        clearHeadroom(voxels, landX, upper.y0() - 1, sz);
        clearHeadroom(voxels, landX + 1, upper.y0(), sz);
    }

    private static void clearHeadroom(VoxelMap voxels, int x, int supportY, int z) {
        voxels.put(x, supportY + 1, z, Blocks.AIR.getDefaultState());
        voxels.put(x, supportY + 2, z, Blocks.AIR.getDefaultState());
        voxels.put(x, supportY + 3, z, Blocks.AIR.getDefaultState());
    }

    private static void placeRoofUnit(VoxelMap voxels, RoofUnit roof) {
        if (roof.footprint() < 9) {
            // Still at least a 3×3 plate when possible
            int cx = (roof.x0() + roof.x1()) / 2;
            int cz = (roof.z0() + roof.z1()) / 2;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    voxels.put(cx + dx, roof.y(), cz + dz, roof.fill());
                }
            }
            ringRect(voxels, cx - 1, roof.y() + 1, cz - 1, cx + 1, cz + 1, roof.ridge());
            return;
        }
        fillRect(voxels, roof.x0(), roof.y(), roof.z0(), roof.x1(), roof.y(), roof.z1(), roof.fill());
        ringRect(voxels, roof.x0(), roof.y() + 1, roof.z0(), roof.x1(), roof.z1(), roof.ridge());
        if (roof.rise() >= 2 && roof.x1() - roof.x0() >= 4 && roof.z1() - roof.z0() >= 4) {
            fillRect(voxels, roof.x0() + 1, roof.y() + 1, roof.z0() + 1,
                    roof.x1() - 1, roof.y() + 1, roof.z1() - 1, roof.fill());
            ringRect(voxels, roof.x0() + 1, roof.y() + 2, roof.z0() + 1, roof.x1() - 1, roof.z1() - 1, roof.ridge());
        }
    }

    private static boolean isPotalaLandmark(BuildingSpec spec) {
        String ref = getStringExtra(spec, "reference_landmark", null);
        if (ref == null || ref.isBlank()) {
            ref = getStringExtra(spec, "referenceLandmark", null);
        }
        return ref != null && LANDMARK_POTALA.equalsIgnoreCase(ref.trim());
    }

    private static void fillRect(VoxelMap voxels,
                                 int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    voxels.put(x, y, z, s);
                }
            }
        }
    }

    private static void ringRect(VoxelMap voxels, int x0, int y, int z0, int x1, int z1, BlockState s) {
        for (int x = x0; x <= x1; x++) {
            voxels.put(x, y, z0, s);
            voxels.put(x, y, z1, s);
        }
        for (int z = z0; z <= z1; z++) {
            voxels.put(x0, y, z, s);
            voxels.put(x1, y, z, s);
        }
    }

    private static BlockPos local(BlockPos origin, Direction entrance, int x, int y, int z) {
        return rotatePos(origin.add(x, y, z), origin, entrance);
    }

    private static BlockPos rotatePos(BlockPos p, BlockPos base, Direction entrance) {
        if (entrance == null || entrance == Direction.SOUTH) {
            return p;
        }
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
        if (entrance == null || entrance == Direction.SOUTH || local == null) {
            return local;
        }
        if (!local.getAxis().isHorizontal()) {
            return local;
        }
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
        if (s == null) {
            return null;
        }
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
        } catch (Throwable ex) {
            LOG.debug("best-effort step failed", ex);
        }
        return out;
    }

    private static String getStringExtra(BuildingSpec spec, String key, String def) {
        if (spec == null || spec.getExtra() == null) {
            return def;
        }
        Object v = spec.getExtra().get(key);
        if (v == null) {
            return def;
        }
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

    /** Package-visible parse helpers for tests. */
    static int parseActualTiers(String description) {
        return parseDescInt(description, "actualTiers=");
    }

    static int parseRequestedTiers(String description) {
        return parseDescInt(description, "requestedTiers=");
    }

    static int parseRoofCount(String description) {
        return parseDescInt(description, "roofs=");
    }

    static int parseAirCount(String description) {
        return parseDescInt(description, "air=");
    }

    static boolean parsePotalaMode(String description) {
        return description != null && description.toLowerCase(Locale.ROOT).contains("mode=potala");
    }

    private static int parseDescInt(String description, String key) {
        if (description == null) return -1;
        int i = description.indexOf(key);
        if (i < 0) return -1;
        int start = i + key.length();
        int end = start;
        while (end < description.length() && Character.isDigit(description.charAt(end))) end++;
        if (end == start) return -1;
        return Integer.parseInt(description.substring(start, end));
    }
}
