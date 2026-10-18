package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.generation.component.util.PlannedOccupancy;
import com.formacraft.common.generation.component.util.WalkabilityGraph;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.style.profile.DetailPreferences;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
import com.formacraft.common.typology.TypologyParamResolver;
import com.formacraft.common.typology.TypologyPatchBridge;
import com.formacraft.server.generation.structure.util.StructureSpecParsers;
import com.formacraft.server.material.PaletteResolver;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;

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
 * Multi-tier builds require full floor connectivity; otherwise {@code status=FAILED} with empty blocks.
 */
public final class TieredMountainPalaceBuilder {

    public static final String TYPOLOGY_ID = "tiered_mountain_palace";
    public static final String LANDMARK_POTALA = "potala_palace";

    /** Max exterior approach depth (local +Z from facade) and max terrain drop filled with stairs/columns. */
    public static final int MAX_APPROACH_DEPTH = 4;
    public static final int MAX_APPROACH_DROP = 8;

    private static final FcaLog LOG = FcaLog.of("TieredMountainPalaceBuilder");
    private static final int MIN_SPAN = 8;
    private static final ThreadLocal<List<BuildDiagnostic>> LAST_DIAGNOSTICS =
            ThreadLocal.withInitial(ArrayList::new);

    private TieredMountainPalaceBuilder() {}

    /** Structured generation diagnostic (stair gaps, walkability, openings). */
    public record BuildDiagnostic(String code, String detail) {
        @Override
        public String toString() {
            return code + (detail == null || detail.isBlank() ? "" : ":" + detail);
        }
    }

    /** Last diagnostics from the calling thread's most recent {@link #generate} call. */
    public static List<BuildDiagnostic> lastDiagnostics() {
        return List.copyOf(LAST_DIAGNOSTICS.get());
    }

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
        LAST_DIAGNOSTICS.get().clear();
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
        boolean requireConnectivity = getBoolExtra(spec, "requireFloorConnectivity", true);

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
        List<BuildDiagnostic> diagnostics = LAST_DIAGNOSTICS.get();
        List<Vec3i> wingSamples = new ArrayList<>();
        List<FlightPlan> emittedFlights = new ArrayList<>();
        int doorMx = -1;

        fillRect(voxels, 0, 0, 0, bw - 1, platformH - 1, bd - 1, foundation);
        ringRect(voxels, 0, platformH - 1, 0, bw - 1, bd - 1, trim);

        int upperBand = Math.max(1, (actualTiers + 2) / 3);
        for (int i = 0; i < actualTiers; i++) {
            TierRect t = keepTiers.get(i);
            BlockState wallMat = (i >= actualTiers - upperBand) ? upperWall : wall;
            placeMassShell(voxels, t, floor, wallMat, trim);
        }

        if (potala && actualTiers >= 2) {
            placePotalaWings(voxels, roofs, wingSamples, bw, bd, platformH, tierHeight, actualTiers,
                    floor, upperWall, trim, accent);
        }

        if (!keepTiers.isEmpty()) {
            TierRect base = keepTiers.get(0);
            doorMx = (base.x0() + base.x1()) / 2;
            carveEntrance(voxels, base, platformH, foundation, floor);
            placeEntranceApproach(voxels, world, origin, entrance, doorMx, base.z1(), platformH,
                    foundation, floor, stair);
            placeWindows(voxels, keepTiers, window);
            placeInterTierStairs(voxels, keepTiers, stair, floor, diagnostics, emittedFlights);

            TierRect top = keepTiers.get(actualTiers - 1);
            roofs.add(new RoofUnit(
                    top.x0(), top.z0(), top.x1(), top.z1(),
                    top.y1() + 1, top.width() >= 7 ? 2 : 1, accent, trim));
        }

        for (RoofUnit roof : roofs) {
            placeRoofUnit(voxels, roof);
        }

        String mode = potala ? "potala" : "generic";
        boolean stairFailed = diagnostics.stream().anyMatch(d -> d.code().startsWith("STAIR_GAP"));
        if (requireConnectivity && actualTiers >= 2 && stairFailed) {
            return failStructure(origin, mode, bw, bd, requestedTiers, actualTiers, roofs.size(),
                    voxels.airCount(), diagnostics);
        }

        List<PlannedBlock> blocks = voxels.emit(origin, entrance);
        GeneratedStructure candidate = new GeneratedStructure(null, origin, "pending", blocks);
        List<BuildDiagnostic> occupancyIssues = validateFinalOccupancy(
                candidate, origin, entrance, keepTiers, platformH, tierHeight, potala, wingSamples,
                emittedFlights, doorMx);
        diagnostics.addAll(occupancyIssues);

        if (requireConnectivity && !occupancyIssues.isEmpty()) {
            return failStructure(origin, mode, bw, bd, requestedTiers, actualTiers, roofs.size(),
                    voxels.airCount(), diagnostics);
        }

        String diagSuffix = diagnostics.isEmpty() ? "" : ", diagnostics=" + formatDiagnostics(diagnostics);
        String desc = String.format(
                "Tiered Mountain Palace (tiered_mountain_palace, status=OK, mode=%s, %dx%d, requestedTiers=%d, actualTiers=%d, roofs=%d, air=%d%s)",
                mode, bw, bd, requestedTiers, actualTiers, roofs.size(), voxels.airCount(), diagSuffix
        );
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    private static GeneratedStructure failStructure(
            BlockPos origin, String mode, int bw, int bd, int requestedTiers, int actualTiers,
            int roofs, int air, List<BuildDiagnostic> diagnostics) {
        LOG.warn("tiered_mountain_palace FAILED: {}", formatDiagnostics(diagnostics));
        String desc = String.format(
                "Tiered Mountain Palace (tiered_mountain_palace, status=FAILED, mode=%s, %dx%d, requestedTiers=%d, actualTiers=%d, roofs=%d, air=%d, diagnostics=%s)",
                mode, bw, bd, requestedTiers, actualTiers, roofs, air, formatDiagnostics(diagnostics)
        );
        return new GeneratedStructure(null, origin, desc, List.of());
    }

    private static String formatDiagnostics(List<BuildDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < diagnostics.size(); i++) {
            if (i > 0) sb.append('+');
            sb.append(diagnostics.get(i).toString().replace(' ', '_'));
        }
        return sb.toString();
    }

    private static boolean getBoolExtra(BuildingSpec spec, String key, boolean def) {
        if (spec == null || spec.getExtra() == null) return def;
        Object v = spec.getExtra().get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.equals("true") || s.equals("1") || s.equals("yes")) return true;
        if (s.equals("false") || s.equals("0") || s.equals("no")) return false;
        return def;
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
     * Side wings on the platform front third, flush to the keep corridor (no gap), with link openings.
     * Coords stay non-negative for {@link VoxelMap} packing.
     */
    private static void placePotalaWings(
            VoxelMap voxels, List<RoofUnit> roofs, List<Vec3i> wingSamples,
            int bw, int bd, int platformH, int tierHeight, int actualTiers,
            BlockState floor, BlockState upperWall, BlockState trim, BlockState accent) {
        int maxWing = Math.max(6, (bw - MIN_SPAN) / 2);
        int wingW = Math.min(maxWing, Math.max(6, (int) Math.round(bw * 0.35)));
        int wingD = Math.max(6, bd / 3);
        int wingH = Math.max(tierHeight, tierHeight * Math.max(1, actualTiers / 2));
        int wingY0 = platformH;
        int wingY1 = wingY0 + wingH - 1;
        int frontZ0 = bd - wingD;
        int linkZ = (frontZ0 + bd - 1) / 2;

        int lx0 = 0;
        int lx1 = wingW - 1;
        if (lx1 - lx0 + 1 >= 6 && lx1 + MIN_SPAN < bw) {
            TierRect left = new TierRect(lx0, frontZ0, lx1, bd - 1, wingY0, wingY1, true);
            placeMassShell(voxels, left, floor, upperWall, trim);
            carveWingKeepLink(voxels, lx1, lx1 + 1, linkZ, platformH, floor);
            roofs.add(new RoofUnit(left.x0(), left.z0(), left.x1(), left.z1(),
                    left.y1() + 1, 1, accent, trim));
            placeWingWindows(voxels, left, windowOrBars(upperWall), upperWall);
            wingSamples.add(new Vec3i((lx0 + lx1) / 2, platformH, linkZ));
        }

        int rx1 = bw - 1;
        int rx0 = bw - wingW;
        if (rx1 - rx0 + 1 >= 6 && rx0 - MIN_SPAN > lx1) {
            TierRect right = new TierRect(rx0, frontZ0, rx1, bd - 1, wingY0, wingY1, true);
            placeMassShell(voxels, right, floor, upperWall, trim);
            carveWingKeepLink(voxels, rx0, rx0 - 1, linkZ, platformH, floor);
            roofs.add(new RoofUnit(right.x0(), right.z0(), right.x1(), right.z1(),
                    right.y1() + 1, 1, accent, trim));
            placeWingWindows(voxels, right, windowOrBars(upperWall), upperWall);
            wingSamples.add(new Vec3i((rx0 + rx1) / 2, platformH, linkZ));
        }
    }

    /** 2-wide × 3-high opening through wing wall into keep corridor (walls start at floorY+1). */
    private static void carveWingKeepLink(
            VoxelMap voxels, int wingWallX, int keepCellX, int linkZ, int floorY, BlockState floor) {
        for (int dz = 0; dz <= 1; dz++) {
            voxels.put(wingWallX, floorY, linkZ + dz, floor);
            voxels.put(keepCellX, floorY, linkZ + dz, floor);
            for (int dy = 1; dy <= 3; dy++) {
                voxels.put(wingWallX, floorY + dy, linkZ + dz, Blocks.AIR.getDefaultState());
                voxels.put(keepCellX, floorY + dy, linkZ + dz, Blocks.AIR.getDefaultState());
            }
        }
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

    private static void carveEntrance(VoxelMap voxels, TierRect base, int platformH,
                                      BlockState foundation, BlockState floor) {
        int mx = (base.x0() + base.x1()) / 2;
        int zFront = base.z1();
        int y0 = platformH;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy < 3; dy++) {
                voxels.put(mx + dx, y0 + dy, zFront, Blocks.AIR.getDefaultState());
                voxels.put(mx + dx, y0 + dy, zFront - 1, Blocks.AIR.getDefaultState());
            }
            // Threshold floor under the doorway bay
            voxels.put(mx + dx, y0, zFront - 1, floor);
        }
        BlockState lower = Blocks.DARK_OAK_DOOR.getDefaultState()
                .with(Properties.HORIZONTAL_FACING, Direction.SOUTH)
                .with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        BlockState upper = Blocks.DARK_OAK_DOOR.getDefaultState()
                .with(Properties.HORIZONTAL_FACING, Direction.SOUTH)
                .with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        voxels.put(mx, y0, zFront, lower);
        voxels.put(mx, y0 + 1, zFront, upper);
        // Ensure sill support under door
        voxels.put(mx, y0 - 1, zFront, foundation);
    }

    /**
     * Exterior pad + bounded descending stairs toward terrain (or flat pad when world is null).
     * Terrain fill is capped by {@link #MAX_APPROACH_DEPTH} and {@link #MAX_APPROACH_DROP}.
     */
    private static void placeEntranceApproach(
            VoxelMap voxels, ServerWorld world, BlockPos origin, Direction entrance,
            int mx, int zFront, int platformH,
            BlockState foundation, BlockState floor, BlockState stairBase) {
        int padY = platformH;
        int depth = MAX_APPROACH_DEPTH;
        for (int dz = 1; dz <= depth; dz++) {
            int z = zFront + dz;
            for (int dx = -1; dx <= 1; dx++) {
                int x = mx + dx;
                voxels.put(x, padY, z, floor);
                voxels.put(x, padY + 1, z, Blocks.AIR.getDefaultState());
                voxels.put(x, padY + 2, z, Blocks.AIR.getDefaultState());
                voxels.put(x, padY + 3, z, Blocks.AIR.getDefaultState());
                // Column under pad toward platform base (flat / missing support)
                for (int y = 0; y < padY; y++) {
                    if (voxels.get(x, y, z) == null) {
                        voxels.put(x, y, z, foundation);
                    }
                }
            }
        }

        if (world == null || origin == null) {
            return;
        }

        // Bounded terrain connect: sample heightmap at outer pad edge; step down within MAX_APPROACH_DROP
        for (int dx = -1; dx <= 1; dx++) {
            int lx = mx + dx;
            int lz = zFront + depth;
            BlockPos worldPad = local(origin, entrance, lx, padY, lz);
            int groundY;
            try {
                groundY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, worldPad.getX(), worldPad.getZ());
            } catch (Throwable t) {
                LOG.debug("approach heightmap unavailable", t);
                continue;
            }
            int worldPadY = worldPad.getY();
            int drop = worldPadY - groundY;
            if (drop <= 0 || drop > MAX_APPROACH_DROP) {
                continue;
            }
            // Place descending stairs in local space (further +Z), one step per drop cell
            for (int s = 1; s <= drop && s <= MAX_APPROACH_DROP; s++) {
                int stepZ = lz + s;
                int stepY = padY - s;
                if (stepY < groundY - origin.getY()) break;
                BlockState st = stairBase.with(Properties.HORIZONTAL_FACING, Direction.SOUTH);
                voxels.put(lx, stepY, stepZ, st);
                clearHeadroom(voxels, lx, stepY, stepZ);
                for (int y = Math.max(0, stepY - 3); y < stepY; y++) {
                    if (voxels.get(lx, y, stepZ) == null) {
                        voxels.put(lx, y, stepZ, foundation);
                    }
                }
            }
        }
    }

    private static void placeWindows(VoxelMap voxels, List<TierRect> tiers, BlockState window) {
        for (TierRect t : tiers) {
            int y = t.y0() + 2;
            if (y >= t.y1()) continue;
            for (int x = t.x0() + 2; x <= t.x1() - 2; x += 5) {
                if (t == tiers.get(0) && Math.abs(x - (t.x0() + t.x1()) / 2) <= 2) continue;
                voxels.put(x, y, t.z1(), window);
                voxels.put(x, y + 1, t.z1(), window);
            }
            for (int x = t.x0() + 2; x <= t.x1() - 2; x += 5) {
                voxels.put(x, y, t.z0(), window);
                voxels.put(x, y + 1, t.z0(), window);
            }
            for (int z = t.z0() + 2; z <= t.z1() - 2; z += 5) {
                voxels.put(t.x0(), y, z, window);
                voxels.put(t.x0(), y + 1, z, window);
                voxels.put(t.x1(), y, z, window);
                voxels.put(t.x1(), y + 1, z, window);
            }
        }
    }

    private record FlightStep(int x, int y, int z, Direction facing) {}

    private record FlightPlan(List<FlightStep> steps, int shaftX, int shaftY, int shaftZ, int padX, int padZ) {
        boolean isComplete(int rise) {
            return steps != null && steps.size() == rise;
        }
    }

    /**
     * Plan-then-emit inter-tier stairs. Never places a landing for a truncated flight.
     * Failures append structured {@link BuildDiagnostic} codes ({@code STAIR_GAP_*}).
     */
    private static void placeInterTierStairs(VoxelMap voxels, List<TierRect> tiers,
                                             BlockState stairBase, BlockState floor,
                                             List<BuildDiagnostic> diagnostics,
                                             List<FlightPlan> emittedFlights) {
        if (tiers.size() < 2) return;
        for (int i = 0; i < tiers.size() - 1; i++) {
            TierRect lower = tiers.get(i);
            TierRect upper = tiers.get(i + 1);
            int rise = upper.y0() - lower.y0();
            if (rise <= 0) continue;

            FlightPlan plan = planFlightWithNudges(lower, upper, rise);
            if (plan == null || !plan.isComplete(rise)) {
                String detail = String.format(
                        "tier%d_rise%d_lower%dx%d_upper%dx%d_reason=no_complete_flight",
                        i, rise, lower.width(), lower.depth(), upper.width(), upper.depth());
                diagnostics.add(new BuildDiagnostic("STAIR_GAP", detail));
                LOG.warn("tiered_mountain_palace {}", detail);
                continue;
            }
            emitFlight(voxels, plan, stairBase, floor);
            emittedFlights.add(plan);
        }
    }

    private static FlightPlan planFlightWithNudges(TierRect lower, TierRect upper, int rise) {
        int[] bays = {
                lower.x0() + 2,
                lower.x0() + 3,
                (lower.x0() + lower.x1()) / 2,
                lower.x1() - 3,
                lower.x1() - 2
        };
        for (int sx : bays) {
            FlightPlan plan = planFlight(lower, upper, rise, sx);
            if (plan != null && plan.isComplete(rise)) {
                return plan;
            }
        }
        return null;
    }

    private static FlightPlan planFlight(TierRect lower, TierRect upper, int rise, int sx) {
        if (sx <= lower.x0() || sx >= lower.x1()) return null;
        int sz0 = lower.z0() + 2;
        FlightPlan south = planSouthRun(lower, upper, rise, sx, sz0);
        if (south != null) return south;
        FlightPlan east = planEastRun(lower, upper, rise, lower.x0() + 2, sz0);
        if (east != null) return east;
        FlightPlan west = planWestRun(lower, upper, rise, lower.x1() - 2, sz0);
        if (west != null) return west;
        FlightPlan sbEast = planSwitchback(lower, upper, rise, sx, sz0, Direction.EAST);
        if (sbEast != null) return sbEast;
        return planSwitchback(lower, upper, rise, sx, sz0, Direction.WEST);
    }

    private static FlightPlan planSouthRun(TierRect lower, TierRect upper, int rise, int sx, int sz0) {
        int lastZ = sz0 + rise - 1;
        int padZ = lastZ + 1;
        if (lastZ >= lower.z1() - 1) return null;
        if (padZ > upper.z1() - 1 || padZ < upper.z0() + 1) return null;
        if (sx < upper.x0() + 1 || sx > upper.x1() - 1) return null;
        List<FlightStep> steps = new ArrayList<>(rise);
        for (int step = 0; step < rise; step++) {
            steps.add(new FlightStep(sx, lower.y0() + step, sz0 + step, Direction.SOUTH));
        }
        return new FlightPlan(steps, sx, upper.y0(), lastZ, sx, padZ);
    }

    private static FlightPlan planEastRun(TierRect lower, TierRect upper, int rise, int sx0, int sz) {
        int lastX = sx0 + rise - 1;
        int padX = lastX + 1;
        if (lastX >= lower.x1() - 1) return null;
        if (sz <= lower.z0() || sz >= lower.z1()) return null;
        if (padX > upper.x1() - 1 || padX < upper.x0() + 1) return null;
        if (sz < upper.z0() + 1 || sz > upper.z1() - 1) return null;
        List<FlightStep> steps = new ArrayList<>(rise);
        for (int step = 0; step < rise; step++) {
            steps.add(new FlightStep(sx0 + step, lower.y0() + step, sz, Direction.EAST));
        }
        return new FlightPlan(steps, lastX, upper.y0(), sz, padX, sz);
    }

    private static FlightPlan planWestRun(TierRect lower, TierRect upper, int rise, int sx0, int sz) {
        int lastX = sx0 - (rise - 1);
        int padX = lastX - 1;
        if (lastX <= lower.x0() + 1) return null;
        if (sz <= lower.z0() || sz >= lower.z1()) return null;
        if (padX < upper.x0() + 1 || padX > upper.x1() - 1) return null;
        if (sz < upper.z0() + 1 || sz > upper.z1() - 1) return null;
        List<FlightStep> steps = new ArrayList<>(rise);
        for (int step = 0; step < rise; step++) {
            steps.add(new FlightStep(sx0 - step, lower.y0() + step, sz, Direction.WEST));
        }
        return new FlightPlan(steps, lastX, upper.y0(), sz, padX, sz);
    }

    /** South leg then horizontal turn (east or west) for remaining rise. */
    private static FlightPlan planSwitchback(
            TierRect lower, TierRect upper, int rise, int sx, int sz0, Direction turn) {
        int maxSouthZ = lower.z1() - 2;
        int southCapacity = maxSouthZ - sz0 + 1;
        if (southCapacity < 1) return null;
        int southSteps = Math.min(rise - 1, southCapacity);
        if (southSteps < 1) return null;
        int turnSteps = rise - southSteps;
        int turnZ = sz0 + southSteps - 1;
        if (turnSteps < 1) return null;
        if (turnZ < upper.z0() + 1 || turnZ > upper.z1() - 1) return null;

        List<FlightStep> steps = new ArrayList<>(rise);
        for (int step = 0; step < southSteps; step++) {
            steps.add(new FlightStep(sx, lower.y0() + step, sz0 + step, Direction.SOUTH));
        }
        if (turn == Direction.EAST) {
            int startX = sx + 1;
            int lastX = startX + turnSteps - 1;
            int padX = lastX + 1;
            if (lastX >= lower.x1() - 1) return null;
            if (padX > upper.x1() - 1 || padX < upper.x0() + 1) return null;
            for (int step = 0; step < turnSteps; step++) {
                steps.add(new FlightStep(startX + step, lower.y0() + southSteps + step, turnZ, Direction.EAST));
            }
            if (steps.size() != rise) return null;
            return new FlightPlan(steps, lastX, upper.y0(), turnZ, padX, turnZ);
        }
        int startX = sx - 1;
        int lastX = startX - (turnSteps - 1);
        int padX = lastX - 1;
        if (lastX <= lower.x0() + 1) return null;
        if (padX < upper.x0() + 1 || padX > upper.x1() - 1) return null;
        for (int step = 0; step < turnSteps; step++) {
            steps.add(new FlightStep(startX - step, lower.y0() + southSteps + step, turnZ, Direction.WEST));
        }
        if (steps.size() != rise) return null;
        return new FlightPlan(steps, lastX, upper.y0(), turnZ, padX, turnZ);
    }

    private static void emitFlight(VoxelMap voxels, FlightPlan plan, BlockState stairBase, BlockState floor) {
        for (FlightStep s : plan.steps()) {
            BlockState stair = stairBase.with(Properties.HORIZONTAL_FACING, s.facing());
            voxels.put(s.x(), s.y(), s.z(), stair);
            clearHeadroom(voxels, s.x(), s.y(), s.z());
        }
        voxels.put(plan.shaftX(), plan.shaftY(), plan.shaftZ(), Blocks.AIR.getDefaultState());
        voxels.put(plan.padX(), plan.shaftY(), plan.padZ(), floor);
        clearHeadroom(voxels, plan.shaftX(), plan.shaftY() - 1, plan.shaftZ());
        clearHeadroom(voxels, plan.padX(), plan.shaftY(), plan.padZ());
    }

    private static void clearHeadroom(VoxelMap voxels, int x, int supportY, int z) {
        voxels.put(x, supportY + 1, z, Blocks.AIR.getDefaultState());
        voxels.put(x, supportY + 2, z, Blocks.AIR.getDefaultState());
        voxels.put(x, supportY + 3, z, Blocks.AIR.getDefaultState());
    }

    /**
     * Final occupancy gate over emitted patches (same data preview/apply use).
     * Checks entrance→each tier, entrance→wings, door survival, flight tread counts, exterior pad.
     */
    private static List<BuildDiagnostic> validateFinalOccupancy(
            GeneratedStructure structure, BlockPos origin, Direction entrance,
            List<TierRect> tiers, int platformH, int tierHeight, boolean potala,
            List<Vec3i> wingSamplesLocal, List<FlightPlan> flights, int doorMx) {
        List<BuildDiagnostic> issues = new ArrayList<>();
        if (structure == null || structure.getBlocks() == null || structure.getBlocks().isEmpty()) {
            issues.add(new BuildDiagnostic("EMPTY_BLOCKS", "no_blocks_emitted"));
            return issues;
        }

        long doors = structure.getBlocks().stream()
                .filter(b -> b.getTargetState() != null && b.getTargetState().isOf(Blocks.DARK_OAK_DOOR))
                .count();
        if (doors < 2) {
            issues.add(new BuildDiagnostic("DOOR_MISSING", "door_leaves=" + doors));
        }

        List<BlockPatch> patches = TypologyPatchBridge.toBlockPatches(structure, origin);
        PlannedOccupancy occ = PlannedOccupancy.fromPatches(patches, true);

        Vec3i doorRel = null;
        for (PlannedBlock b : structure.getBlocks()) {
            if (b.getTargetState() != null && b.getTargetState().isOf(Blocks.DARK_OAK_DOOR)
                    && b.getTargetState().contains(Properties.DOUBLE_BLOCK_HALF)
                    && b.getTargetState().get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
                BlockPos rel = b.getPos().subtract(origin);
                doorRel = new Vec3i(rel.getX(), rel.getY(), rel.getZ());
                break;
            }
        }
        if (doorRel == null) {
            issues.add(new BuildDiagnostic("DOOR_MISSING", "lower_leaf_not_found"));
            return issues;
        }

        Vec3i start = findStandableNearDoor(occ, doorRel);
        if (start == null) {
            issues.add(new BuildDiagnostic("ENTRANCE_UNSTANDABLE", "no_stand_near_door"));
            return issues;
        }

        // Exterior approach pad: one step outward along facing must be standable (pad floor)
        BlockPos out = new BlockPos(doorRel.x(), doorRel.y(), doorRel.z()).offset(entrance);
        if (!WalkabilityGraph.canStand(occ, out.getX(), out.getY(), out.getZ())
                && !WalkabilityGraph.canStand(occ, out.getX(), platformH, out.getZ())) {
            issues.add(new BuildDiagnostic("APPROACH_UNSTANDABLE",
                    "out=" + out.getX() + "," + out.getY() + "," + out.getZ()));
        }

        for (int i = 0; i < tiers.size(); i++) {
            int floorY = platformH + i * tierHeight;
            Vec3i goal = findStandableNear(occ, floorY, doorRel.x(), doorRel.z(), 20);
            if (goal == null) {
                issues.add(new BuildDiagnostic("FLOOR_NO_STAND", "tier" + i + "_y" + floorY));
                continue;
            }
            WalkabilityGraph.ReachResult r = WalkabilityGraph.search(occ, start, goal, 2048, null);
            if (r != WalkabilityGraph.ReachResult.REACHABLE) {
                issues.add(new BuildDiagnostic("FLOOR_UNREACHABLE", "tier" + i + "_" + r.name()));
            }
        }

        if (potala) {
            for (int w = 0; w < wingSamplesLocal.size(); w++) {
                Vec3i local = wingSamplesLocal.get(w);
                BlockPos world = local(origin, entrance, local.x(), local.y(), local.z());
                BlockPos rel = world.subtract(origin);
                Vec3i wingGoal = findStandableNear(occ, rel.getY(), rel.getX(), rel.getZ(), 8);
                if (wingGoal == null) {
                    issues.add(new BuildDiagnostic("WING_NO_STAND", "wing" + w));
                    continue;
                }
                WalkabilityGraph.ReachResult r = WalkabilityGraph.search(occ, start, wingGoal, 2048, null);
                if (r != WalkabilityGraph.ReachResult.REACHABLE) {
                    issues.add(new BuildDiagnostic("WING_UNREACHABLE", "wing" + w + "_" + r.name()));
                }
            }
        }

        for (FlightPlan flight : flights) {
            if (flight.steps() == null || flight.steps().isEmpty()) {
                issues.add(new BuildDiagnostic("FLIGHT_EMPTY", "empty_steps"));
                continue;
            }
            long stairCells = structure.getBlocks().stream()
                    .filter(b -> b.getTargetState() != null
                            && b.getTargetState().contains(Properties.HORIZONTAL_FACING)
                            && b.getTargetState().getBlock().getTranslationKey().contains("stairs"))
                    .count();
            if (stairCells < flight.steps().size()) {
                // Soft: other flights share pool — only flag if total stairs below sum of steps
            }
        }
        int expectedTreads = flights.stream().mapToInt(f -> f.steps().size()).sum();
        long stairBlocks = structure.getBlocks().stream()
                .filter(b -> b.getTargetState() != null && b.getTargetState().isOf(Blocks.STONE_BRICK_STAIRS))
                .count();
        if (expectedTreads > 0 && stairBlocks < expectedTreads) {
            issues.add(new BuildDiagnostic("FLIGHT_TREAD_MISMATCH",
                    "expected=" + expectedTreads + "_got=" + stairBlocks));
        }

        // Patch consistency: AIR cells must be REMOVE actions
        for (BlockPatch p : patches) {
            if (p.targetBlock() != null && PlannedOccupancy.isAirId(p.targetBlock())
                    && !BlockPatch.REMOVE.equals(p.action())) {
                issues.add(new BuildDiagnostic("AIR_ACTION", "expected_REMOVE"));
                break;
            }
        }

        return issues;
    }

    private static Vec3i findStandableNearDoor(PlannedOccupancy occ, Vec3i door) {
        int[][] deltas = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}, {0, 0}};
        for (int[] d : deltas) {
            int x = door.x() + d[0];
            int z = door.z() + d[1];
            if (WalkabilityGraph.canStand(occ, x, door.y(), z)) {
                return new Vec3i(x, door.y(), z);
            }
            if (WalkabilityGraph.canStand(occ, x, door.y() - 1, z)) {
                return new Vec3i(x, door.y() - 1, z);
            }
        }
        return findStandableNear(occ, door.y(), door.x(), door.z(), 8);
    }

    private static Vec3i findStandableNear(PlannedOccupancy occ, int floorY, int cx, int cz, int searchRadius) {
        for (int r = 0; r <= searchRadius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r && r > 0) continue;
                    if (WalkabilityGraph.canStand(occ, cx + dx, floorY, cz + dz)) {
                        return new Vec3i(cx + dx, floorY, cz + dz);
                    }
                }
            }
        }
        return null;
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

    static boolean parseFailed(String description) {
        return description != null && description.contains("status=FAILED");
    }

    static boolean parseOk(String description) {
        return description != null && description.contains("status=OK");
    }

    /** True if description or last diagnostics report a stair-gap failure. */
    static boolean parseHasStairGap(String description) {
        if (description != null && (description.contains("stairGap=") || description.contains("STAIR_GAP"))) {
            return true;
        }
        return lastDiagnostics().stream().anyMatch(d -> d.code().startsWith("STAIR_GAP"));
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
