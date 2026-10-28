package com.formacraft.server.edit;

import com.formacraft.common.component.transform.BlockStateStringUtil;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.PillarBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StainedGlassBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Resolves window-edit authorization independently of candidate patches.
 * Flow: identify target wall/window → authorizedCells → compile → patches ⊆ authorized.
 */
public final class WindowEditScopeResolver {
    public record ResolvedScope(
            boolean ok,
            String refusalReason,
            String targetPartId,
            String hostWallId,
            int floorIndex,
            String facing,
            /** Independent wall/window authority — never derived from candidate patches. */
            Set<BlockPos> authorizedCells,
            /** Cells this ticket will actually write; must be ⊆ {@link #authorizedCells()}. */
            Set<BlockPos> editCells,
            Set<BlockPos> protectedCells,
            Map<BlockPos, String> expectedWorldStates
    ) {
        public ResolvedScope {
            authorizedCells = authorizedCells == null
                    ? Set.of()
                    : Set.copyOf(new LinkedHashSet<>(authorizedCells));
            editCells = editCells == null
                    ? Set.of()
                    : Set.copyOf(new LinkedHashSet<>(editCells));
            protectedCells = protectedCells == null
                    ? Set.of()
                    : Set.copyOf(new LinkedHashSet<>(protectedCells));
            expectedWorldStates = expectedWorldStates == null
                    ? Map.of()
                    : Map.copyOf(new LinkedHashMap<>(expectedWorldStates));
            floorIndex = Math.max(0, floorIndex);
            facing = facing == null ? "" : facing;
            targetPartId = targetPartId == null ? "" : targetPartId;
            hostWallId = hostWallId == null ? "" : hostWallId;
            refusalReason = refusalReason == null ? "" : refusalReason;
        }

        public static ResolvedScope refuse(String reason) {
            return new ResolvedScope(false, reason, "", "", 0, "", Set.of(), Set.of(), Set.of(), Map.of());
        }
    }

    /** Optional façade targeting for precise multi-storey / multi-face edits. */
    public record InsertTarget(Integer floorIndex, String facing) {
        public InsertTarget {
            facing = facing == null || facing.isBlank()
                    ? null
                    : facing.trim().toUpperCase(Locale.ROOT);
        }

        public static InsertTarget any() {
            return new InsertTarget(null, null);
        }

        public boolean hasFloor() {
            return floorIndex != null;
        }

        public boolean hasFacing() {
            return facing != null && !facing.isBlank();
        }
    }

    private WindowEditScopeResolver() {}

    /**
     * Authorize a façade wall envelope beside a unique entrance, then place the window
     * only inside that envelope. Allowed cells come from the wall — never from patches.
     */
    public static ResolvedScope resolveInsert(
            BuildingInstance building,
            BuildingPartIndex index,
            Map<BlockPos, BlockState> live,
            int width,
            int height
    ) {
        return resolveInsert(building, index, live, width, height, InsertTarget.any());
    }

    public static ResolvedScope resolveInsert(
            BuildingInstance building,
            BuildingPartIndex index,
            Map<BlockPos, BlockState> live,
            int width,
            int height,
            InsertTarget target
    ) {
        if (building == null) return ResolvedScope.refuse("building_missing");
        if (live == null || live.isEmpty()) return ResolvedScope.refuse("live_world_states_missing");
        if (width < 1 || height < 1 || width > 8 || height > 8 || width * height > 64) {
            return ResolvedScope.refuse("WINDOW_SIZE_INVALID");
        }
        InsertTarget want = target == null ? InsertTarget.any() : target;
        var bands = BuildingFloorBands.fromLive(building, live);

        EntranceLocate locate = locateEntrances(live);
        if (locate.groups().isEmpty()) return ResolvedScope.refuse("ENTRANCE_NOT_FOUND");

        List<EntranceFacade> candidates = new ArrayList<>();
        for (Set<BlockPos> group : locate.groups()) {
            EntranceFacade facade = toFacade(building, live, group, locate.normals(), bands);
            if (facade == null) continue;
            if (want.hasFacing() && !want.facing().equalsIgnoreCase(facade.facing().asString())) continue;
            if (want.hasFloor() && want.floorIndex() != facade.floorIndex()) continue;
            candidates.add(facade);
        }
        if (candidates.isEmpty()) {
            if (want.hasFloor() || want.hasFacing()) {
                return ResolvedScope.refuse("WALL_TARGET_UNMATCHED");
            }
            return ResolvedScope.refuse("ENTRANCE_NOT_FOUND");
        }
        if (candidates.size() != 1) return ResolvedScope.refuse("ENTRANCE_AMBIGUOUS");
        EntranceFacade facade = candidates.getFirst();
        // Explicit floor/facing must not silently fall back to another storey/façade.
        if (want.hasFloor() && want.floorIndex() != facade.floorIndex()) {
            return ResolvedScope.refuse("WALL_FLOOR_MISMATCH");
        }
        if (want.hasFacing() && !want.facing().equalsIgnoreCase(facade.facing().asString())) {
            return ResolvedScope.refuse("WALL_FACING_MISMATCH");
        }

        Set<BlockPos> protectedCells = collectProtected(building, index, live);
        BuildingFloorBands.FloorBand band = BuildingFloorBands.bandOf(bands, facade.floorIndex());
        Set<BlockPos> authorized = collectInsertWallEnvelope(building, live, facade, protectedCells, band);
        if (authorized.isEmpty()) {
            return ResolvedScope.refuse("WALL_ENVELOPE_UNAVAILABLE");
        }

        // Placement must land entirely inside the independently authorized envelope.
        List<BlockPos> placement = chooseInsertRectangle(live, facade, width, height, authorized, protectedCells);
        if (placement.isEmpty()) {
            return ResolvedScope.refuse("WALL_RECTANGLE_UNAVAILABLE");
        }
        Set<BlockPos> edit = new LinkedHashSet<>(placement);
        for (BlockPos cell : edit) {
            if (!authorized.contains(cell) || protectedCells.contains(cell) || !building.contains(cell)) {
                return ResolvedScope.refuse("AUTHORIZED_WALL_MISMATCH@" + cell.toShortString());
            }
            if (band != null && !band.containsY(cell.getY())) {
                return ResolvedScope.refuse("WALL_FLOOR_BAND_MISMATCH@" + cell.toShortString());
            }
        }

        Map<BlockPos, String> expected = expectedOf(live, authorized);
        String wallId = "wall:" + facade.facing().asString()
                + ":f" + facade.floorIndex()
                + ":" + facade.planeKey();
        return new ResolvedScope(
                true, "", wallId, "", facade.floorIndex(), facade.facing().asString().toUpperCase(Locale.ROOT),
                authorized, edit, protectedCells, expected);
    }

    /**
     * Authorize a unique sealable window group. Multiple groups → refuse (no silent multi-seal).
     */
    public static ResolvedScope resolveSeal(
            BuildingInstance building,
            BuildingPartIndex index,
            Map<BlockPos, BlockState> live,
            BlockPos center,
            Predicate<BlockPos> skyVisible,
            boolean woodOnly,
            boolean courtyardOnly
    ) {
        if (building == null) return ResolvedScope.refuse("building_missing");
        if (live == null || live.isEmpty()) return ResolvedScope.refuse("live_world_states_missing");
        Set<BlockPos> roof = index == null ? Set.of() : index.roof().cells();
        Set<BlockPos> protectedCells = collectProtected(building, index, live);

        List<Set<BlockPos>> groups = findSealableWindowGroups(
                live, roof, center == null ? building.origin() : center,
                skyVisible == null ? p -> false : skyVisible, woodOnly, courtyardOnly);
        if (groups.isEmpty()) return ResolvedScope.refuse("WINDOW_GROUP_NOT_FOUND");
        if (groups.size() != 1) return ResolvedScope.refuse("WINDOW_GROUP_AMBIGUOUS");

        Set<BlockPos> group = groups.getFirst();
        Set<BlockPos> allowed = new LinkedHashSet<>();
        for (BlockPos cell : group) {
            if (cell == null) continue;
            if (!building.contains(cell)) return ResolvedScope.refuse("WINDOW_OUTSIDE_BUILDING@" + cell.toShortString());
            if (protectedCells.contains(cell) || roof.contains(cell)) {
                return ResolvedScope.refuse("WINDOW_TOUCHES_PROTECTED@" + cell.toShortString());
            }
            allowed.add(cell.toImmutable());
        }
        if (allowed.isEmpty()) return ResolvedScope.refuse("WINDOW_GROUP_EMPTY");

        String facing = inferFacing(allowed);
        var bands = BuildingFloorBands.fromLive(building, live);
        int floorIndex = BuildingFloorBands.floorIndexOf(bands, allowed.iterator().next().getY());
        String hostWallId = "wall:" + facing.toLowerCase(Locale.ROOT) + ":f" + floorIndex;
        String windowId = "window:" + facing.toLowerCase(Locale.ROOT)
                + ":f" + floorIndex + ":" + canonicalKey(allowed);
        // Seal authority equals the unique window group (identity), which is also the edit set.
        return new ResolvedScope(
                true, "", windowId, hostWallId, floorIndex, facing,
                allowed, allowed, protectedCells, expectedOf(live, allowed));
    }

    /** Patches must be non-empty and every write cell must lie in authorized ∩ building − protected. */
    public static List<String> validatePatchesAgainstScope(
            BuildingInstance building,
            ResolvedScope scope,
            List<BlockPatch> patches
    ) {
        List<String> conflicts = new ArrayList<>();
        if (scope == null || !scope.ok()) {
            conflicts.add(scope == null ? "scope_missing" : scope.refusalReason());
            return conflicts;
        }
        if (patches == null || patches.isEmpty()) {
            conflicts.add("empty_patch");
            return conflicts;
        }
        BlockPos origin = building.origin();
        for (BlockPatch patch : patches) {
            if (patch == null) continue;
            BlockPos world = origin.add(patch.dx(), patch.dy(), patch.dz()).toImmutable();
            if (!building.contains(world)) {
                conflicts.add("outside_building@" + world.toShortString());
            }
            if (!scope.authorizedCells().contains(world)) {
                conflicts.add("unauthorized_write@" + world.toShortString());
            }
            if (scope.protectedCells().contains(world)) {
                conflicts.add("protected_cell@" + world.toShortString());
            }
        }
        return conflicts;
    }

    public static List<BlockPatch> compileInsertPatches(
            BuildingInstance building, ResolvedScope scope, String glassBlock
    ) {
        if (building == null || scope == null || !scope.ok()) return List.of();
        String block = glassBlock == null || glassBlock.isBlank() ? "minecraft:glass" : glassBlock;
        BlockPos origin = building.origin();
        List<BlockPatch> patches = new ArrayList<>();
        for (BlockPos cell : scope.editCells()) {
            if (!scope.authorizedCells().contains(cell)) continue;
            patches.add(new BlockPatch(
                    BlockPatch.REPLACE,
                    cell.getX() - origin.getX(),
                    cell.getY() - origin.getY(),
                    cell.getZ() - origin.getZ(),
                    block));
        }
        patches.sort(Comparator.comparingInt(BlockPatch::dx)
                .thenComparingInt(BlockPatch::dy)
                .thenComparingInt(BlockPatch::dz));
        return List.copyOf(patches);
    }

    public static List<BlockPatch> compileSealPatches(
            BuildingInstance building,
            ResolvedScope scope,
            Map<BlockPos, BlockState> live
    ) {
        if (building == null || scope == null || !scope.ok() || live == null) return List.of();
        BlockState material = voteWhiteWallMaterial(live, scope.editCells());
        if (material == null) return List.of();
        String block = BlockStateStringUtil.fromState(material);
        BlockPos origin = building.origin();
        List<BlockPatch> patches = new ArrayList<>();
        for (BlockPos cell : scope.editCells()) {
            if (!scope.authorizedCells().contains(cell)) continue;
            patches.add(new BlockPatch(
                    BlockPatch.REPLACE,
                    cell.getX() - origin.getX(),
                    cell.getY() - origin.getY(),
                    cell.getZ() - origin.getZ(),
                    block));
        }
        patches.sort(Comparator.comparingInt(BlockPatch::dx)
                .thenComparingInt(BlockPatch::dy)
                .thenComparingInt(BlockPatch::dz));
        return List.copyOf(patches);
    }

    // --- insert helpers ---

    private record EntranceFacade(
            Set<BlockPos> doorway,
            Direction facing,
            Direction right,
            BlockPos edge,
            int sillOffset,
            int floorIndex,
            String planeKey
    ) {}

    private record EntranceLocate(List<Set<BlockPos>> groups, Map<BlockPos, Direction> normals) {}

    private static EntranceLocate locateEntrances(Map<BlockPos, BlockState> live) {
        Set<BlockPos> doors = new HashSet<>();
        Map<BlockPos, Direction> normals = new HashMap<>();
        Set<BlockPos> floorEdges = new HashSet<>();
        live.forEach((p, s) -> {
            if (s.getBlock() instanceof DoorBlock
                    && s.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
                doors.add(p);
                normals.put(p, s.get(Properties.HORIZONTAL_FACING));
            }
        });
        if (doors.isEmpty()) {
            for (var e : live.entrySet()) {
                BlockPos p = e.getKey();
                if (!e.getValue().isAir() || !air(live, p.up()) || !solid(live, p.down())) continue;
                for (Direction normal : Direction.Type.HORIZONTAL) {
                    boolean floorEdge = !solid(live, p.offset(normal).down())
                            && solid(live, p.offset(normal.getOpposite()).down());
                    boolean shellEdge = air(live, p.offset(normal).up())
                            && air(live, p.offset(normal.getOpposite()).up())
                            && ceiling(live, p.offset(normal.getOpposite(), 3))
                            && !ceiling(live, p.offset(normal, 3));
                    if (!floorEdge && !shellEdge) continue;
                    Direction across = normal.rotateYClockwise();
                    boolean left = false, right = false, lintel = false;
                    for (int k = 1; k <= 3; k++) {
                        left |= solid(live, p.offset(across, k));
                        right |= solid(live, p.offset(across.getOpposite(), k));
                    }
                    for (int y = 2; y <= 4; y++) lintel |= solid(live, p.up(y));
                    if (left && right && lintel) {
                        doors.add(p);
                        normals.put(p, normal);
                        if (floorEdge) {
                            floorEdges.add(p);
                            break;
                        }
                    }
                }
            }
        }
        if (!floorEdges.isEmpty()) doors.retainAll(floorEdges);
        return new EntranceLocate(groupByNormal(doors, normals), normals);
    }

    private static EntranceFacade toFacade(
            BuildingInstance building,
            Map<BlockPos, BlockState> live,
            Set<BlockPos> group,
            Map<BlockPos, Direction> normals,
            List<BuildingFloorBands.FloorBand> bands
    ) {
        if (group == null || group.isEmpty()) return null;
        BlockPos seed = group.iterator().next();
        Direction facing = normals.get(seed);
        if (facing == null) return null;
        Direction right = facing.rotateYCounterclockwise();
        int sillOffset = solid(live, seed.offset(facing.getOpposite())) ? 1 : 0;
        BlockPos edge = group.stream()
                .max(Comparator.comparingInt(p -> p.getX() * right.getOffsetX() + p.getZ() * right.getOffsetZ()))
                .orElseThrow();
        int floorIndex = BuildingFloorBands.floorIndexOf(bands, seed.getY());
        String planeKey = facing.getAxis() == Direction.Axis.X
                ? "x" + edge.getX()
                : "z" + edge.getZ();
        return new EntranceFacade(Set.copyOf(group), facing, right, edge, sillOffset, floorIndex, planeKey);
    }

    private static List<Set<BlockPos>> groupByNormal(Set<BlockPos> doors, Map<BlockPos, Direction> normals) {
        Set<BlockPos> remaining = new HashSet<>(doors);
        List<Set<BlockPos>> groups = new ArrayList<>();
        while (!remaining.isEmpty()) {
            BlockPos seed = remaining.iterator().next();
            remaining.remove(seed);
            Set<BlockPos> group = new HashSet<>();
            group.add(seed);
            ArrayDeque<BlockPos> q = new ArrayDeque<>();
            q.add(seed);
            while (!q.isEmpty()) {
                BlockPos p = q.remove();
                for (Direction dir : Direction.Type.HORIZONTAL) {
                    BlockPos n = p.offset(dir);
                    if (remaining.contains(n) && Objects.equals(normals.get(n), normals.get(seed))) {
                        remaining.remove(n);
                        group.add(n);
                        q.add(n);
                    }
                }
            }
            groups.add(group);
        }
        return groups;
    }

    /**
     * Independent wall envelope: solid façade cells to the entrance's right, same plane,
     * within a bounded lateral/vertical band — not the final window rectangle.
     */
    private static Set<BlockPos> collectInsertWallEnvelope(
            BuildingInstance building,
            Map<BlockPos, BlockState> live,
            EntranceFacade facade,
            Set<BlockPos> protectedCells,
            BuildingFloorBands.FloorBand band
    ) {
        Set<BlockPos> out = new LinkedHashSet<>();
        Direction right = facade.right();
        Direction facing = facade.facing();
        BlockPos edge = facade.edge();
        int sill = facade.sillOffset();
        int yStart = band == null ? edge.getY() + sill : Math.max(edge.getY() + sill, band.minY());
        int yEndExclusive = band == null
                ? yStart + 8
                : Math.min(yStart + 8, band.maxYExclusive());
        // Thin / foundation-clipped bands still need room for a normal window rectangle.
        if (yEndExclusive - yStart < 4) {
            yEndExclusive = Math.min(yStart + 8, building.max().getY() + 1);
        }
        // Lateral: 1..8 to the right of jamb; vertical clipped to the real FloorBand.
        for (int lat = 1; lat <= 8; lat++) {
            for (int y = yStart; y < yEndExclusive; y++) {
                BlockPos cell = new BlockPos(
                        edge.getX() + right.getOffsetX() * lat,
                        y,
                        edge.getZ() + right.getOffsetZ() * lat).toImmutable();
                if (!building.contains(cell)) continue;
                if (protectedCells.contains(cell)) continue;
                if (facade.doorway().contains(cell)) continue;
                BlockState state = live.get(cell);
                if (!isEditableWallCell(state)) continue;
                // Same façade plane as entrance jamb wall (constant X or Z).
                if (facing.getAxis() == Direction.Axis.X) {
                    if (cell.getX() != edge.getX()) continue;
                } else if (facing.getAxis() == Direction.Axis.Z) {
                    if (cell.getZ() != edge.getZ()) continue;
                }
                out.add(cell);
            }
        }
        return out;
    }

    private static List<BlockPos> chooseInsertRectangle(
            Map<BlockPos, BlockState> live,
            EntranceFacade facade,
            int width,
            int height,
            Set<BlockPos> authorized,
            Set<BlockPos> protectedCells
    ) {
        Direction right = facade.right();
        BlockPos edge = facade.edge();
        int sill = facade.sillOffset();
        for (int distance : new int[]{2, 1, 3, 4, 5, 6}) {
            List<BlockPos> cells = new ArrayList<>();
            boolean valid = solid(live, edge.offset(right, distance + width).up(sill));
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    BlockPos p = edge.offset(right, distance + x).up(y + sill).toImmutable();
                    if (!authorized.contains(p) || protectedCells.contains(p) || !isEditableWallCell(live.get(p))) {
                        valid = false;
                    }
                    cells.add(p);
                }
            }
            if (valid) return cells;
        }
        return List.of();
    }

    // --- seal helpers ---

    private static List<Set<BlockPos>> findSealableWindowGroups(
            Map<BlockPos, BlockState> live,
            Set<BlockPos> roof,
            BlockPos center,
            Predicate<BlockPos> skyVisible,
            boolean woodOnly,
            boolean courtyardOnly
    ) {
        Set<BlockPos> remaining = new HashSet<>();
        live.forEach((p, s) -> {
            if (roof.contains(p)) return;
            if (s.getBlock() instanceof FenceBlock
                    || (!woodOnly && (s.getBlock() instanceof PaneBlock
                    || s.isOf(Blocks.GLASS)
                    || s.getBlock() instanceof StainedGlassBlock))) {
                remaining.add(p);
            }
        });
        List<Set<BlockPos>> accepted = new ArrayList<>();
        while (!remaining.isEmpty()) {
            BlockPos start = remaining.iterator().next();
            remaining.remove(start);
            Set<BlockPos> group = new HashSet<>();
            group.add(start);
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                BlockPos p = queue.remove();
                for (BlockPos n : List.of(p.up(), p.down(), p.east(), p.west(), p.north(), p.south())) {
                    if (remaining.remove(n)) {
                        group.add(n);
                        queue.add(n);
                    }
                }
            }
            int x0 = group.stream().mapToInt(BlockPos::getX).min().orElseThrow();
            int x1 = group.stream().mapToInt(BlockPos::getX).max().orElseThrow();
            int y0 = group.stream().mapToInt(BlockPos::getY).min().orElseThrow();
            int y1 = group.stream().mapToInt(BlockPos::getY).max().orElseThrow();
            int z0 = group.stream().mapToInt(BlockPos::getZ).min().orElseThrow();
            int z1 = group.stream().mapToInt(BlockPos::getZ).max().orElseThrow();
            if (group.size() > 128 || x1 - x0 > 31 || z1 - z0 > 31 || y1 - y0 > 7 || (x1 != x0 && z1 != z0)) {
                continue;
            }
            boolean constantX = x0 == x1;
            if (courtyardOnly) {
                int normal = constantX
                        ? Integer.compare(center.getX(), x0)
                        : Integer.compare(center.getZ(), z0);
                if (normal == 0) continue;
                boolean inward = false;
                for (BlockPos p : group) {
                    for (int d = 1; d <= 3; d++) {
                        BlockPos probe = p.add(constantX ? normal * d : 0, 0, constantX ? 0 : normal * d);
                        BlockState state = live.get(probe);
                        if (state == null || !state.isAir()) break;
                        if (skyVisible.test(probe)) inward = true;
                    }
                }
                if (!inward) continue;
            }
            Map<BlockState, Integer> votes = new HashMap<>();
            Set<BlockPos> border = new HashSet<>();
            boolean above = false, below = false, left = false, right = false;
            for (BlockPos p : group) {
                for (int d = 1; d <= 2; d++) {
                    for (BlockPos n : List.of(
                            p.up(d), p.down(d),
                            constantX ? p.north(d) : p.west(d),
                            constantX ? p.south(d) : p.east(d))) {
                        if (group.contains(n) || roof.contains(n) || !border.add(n)) continue;
                        BlockState state = live.get(n);
                        if (!whiteWall(state)) continue;
                        votes.merge(state, 1, Integer::sum);
                        above |= n.getY() > y1 && (y1 != y0 || d == 1);
                        below |= n.getY() < y0 && (y1 != y0 || d == 1);
                        left |= constantX ? n.getZ() < z0 : n.getX() < x0;
                        right |= constantX ? n.getZ() > z1 : n.getX() > x1;
                    }
                }
            }
            if ((y1 == y0 ? !(above && below) : !(above && below || left && right)) || votes.isEmpty()) {
                continue;
            }
            var sorted = votes.entrySet().stream()
                    .sorted(Map.Entry.<BlockState, Integer>comparingByValue().reversed())
                    .toList();
            if (sorted.getFirst().getValue() < 4) continue;
            if (sorted.size() > 1 && sorted.get(1).getValue().equals(sorted.getFirst().getValue())) continue;
            accepted.add(Set.copyOf(group));
        }
        return accepted;
    }

    private static BlockState voteWhiteWallMaterial(Map<BlockPos, BlockState> live, Set<BlockPos> group) {
        if (group == null || group.isEmpty()) return null;
        int x0 = group.stream().mapToInt(BlockPos::getX).min().orElseThrow();
        int x1 = group.stream().mapToInt(BlockPos::getX).max().orElseThrow();
        boolean constantX = x0 == x1;
        Map<BlockState, Integer> votes = new HashMap<>();
        for (BlockPos p : group) {
            for (int d = 1; d <= 2; d++) {
                for (BlockPos n : List.of(
                        p.up(d), p.down(d),
                        constantX ? p.north(d) : p.west(d),
                        constantX ? p.south(d) : p.east(d))) {
                    if (group.contains(n)) continue;
                    BlockState state = live.get(n);
                    if (whiteWall(state)) votes.merge(state, 1, Integer::sum);
                }
            }
        }
        return votes.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    // --- shared ---

    private static Set<BlockPos> collectProtected(
            BuildingInstance building,
            BuildingPartIndex index,
            Map<BlockPos, BlockState> live
    ) {
        Set<BlockPos> protectedCells = new LinkedHashSet<>();
        if (index != null) protectedCells.addAll(index.roof().cells());
        if (live == null) return protectedCells;
        for (var e : live.entrySet()) {
            BlockPos p = e.getKey();
            BlockState s = e.getValue();
            if (p == null || s == null) continue;
            if (building != null && !building.contains(p)) {
                // Neighbor cells are never authorized; mark as protected if present in scan.
                protectedCells.add(p.toImmutable());
                continue;
            }
            if (s.getBlock() instanceof PillarBlock
                    || s.getBlock() instanceof StairsBlock
                    || s.getBlock() instanceof SlabBlock
                    || s.getBlock() instanceof DoorBlock) {
                protectedCells.add(p.toImmutable());
            }
        }
        return protectedCells;
    }

    private static Map<BlockPos, String> expectedOf(Map<BlockPos, BlockState> live, Set<BlockPos> cells) {
        Map<BlockPos, String> expected = new LinkedHashMap<>();
        for (BlockPos cell : cells) {
            BlockState state = live.get(cell);
            if (state != null) expected.put(cell, BlockStateStringUtil.fromState(state));
        }
        return expected;
    }

    private static boolean isEditableWallCell(BlockState state) {
        if (state == null || !state.isOpaque()) return false;
        if (state.getBlock() instanceof PillarBlock) return false;
        if (state.getBlock() instanceof StairsBlock) return false;
        if (state.getBlock() instanceof SlabBlock) return false;
        return !(state.getBlock() instanceof DoorBlock);
    }

    private static boolean whiteWall(BlockState s) {
        return s != null && (s.isOf(Blocks.WHITE_TERRACOTTA) || s.isOf(Blocks.WHITE_CONCRETE)
                || s.isOf(Blocks.QUARTZ_BLOCK) || s.isOf(Blocks.SMOOTH_QUARTZ) || s.isOf(Blocks.CALCITE));
    }

    private static boolean ceiling(Map<BlockPos, BlockState> live, BlockPos p) {
        for (int y = 2; y <= 16; y++) if (solid(live, p.up(y))) return true;
        return false;
    }

    private static boolean air(Map<BlockPos, BlockState> live, BlockPos p) {
        BlockState state = live.get(p);
        return state == null || state.isAir();
    }

    private static boolean solid(Map<BlockPos, BlockState> live, BlockPos p) {
        BlockState state = live.get(p);
        return state != null && state.isOpaque();
    }

    private static String inferFacing(Set<BlockPos> cells) {
        int x0 = cells.stream().mapToInt(BlockPos::getX).min().orElse(0);
        int x1 = cells.stream().mapToInt(BlockPos::getX).max().orElse(0);
        if (x0 == x1) return Direction.EAST.asString().toUpperCase(Locale.ROOT);
        return Direction.SOUTH.asString().toUpperCase(Locale.ROOT);
    }

    private static String canonicalKey(Set<BlockPos> cells) {
        BlockPos min = cells.stream()
                .min(Comparator.comparingInt(BlockPos::getX)
                        .thenComparingInt(BlockPos::getY)
                        .thenComparingInt(BlockPos::getZ))
                .orElse(BlockPos.ORIGIN);
        return min.getX() + "_" + min.getY() + "_" + min.getZ();
    }
}
