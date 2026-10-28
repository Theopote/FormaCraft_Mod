package com.formacraft.server.edit;

import com.formacraft.common.build.GeometrySource;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.component.transform.BlockStateStringUtil;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.memory.ProjectMemory;
import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * Part index for building-aware edits: roof (Phase 1) plus minimal wall/window (Phase 2).
 * Authority comes from generator/compiler provenance or persisted memory — never world absolute Y.
 */
public final class BuildingPartIndex {
    public record RoofPart(String partId, Set<BlockPos> cells, Map<BlockPos, String> recordedStates) {
        public RoofPart {
            partId = partId == null || partId.isBlank() ? "roof" : partId;
            cells = cells == null ? Set.of() : Set.copyOf(cells);
            recordedStates = recordedStates == null ? Map.of() : Map.copyOf(recordedStates);
        }

        public boolean isEmpty() {
            return cells.isEmpty();
        }
    }

    /**
     * One façade face with stable identity. floorIndex is relative to building origin
     * (storey band), never world absolute Y.
     */
    public record WallFace(
            String wallId,
            int floorIndex,
            String facing,
            Set<BlockPos> cells,
            Map<BlockPos, String> expectedStates
    ) {
        public WallFace {
            wallId = wallId == null || wallId.isBlank() ? "wall" : wallId;
            floorIndex = Math.max(0, floorIndex);
            facing = facing == null ? "" : facing;
            cells = cells == null ? Set.of() : Set.copyOf(cells);
            expectedStates = expectedStates == null ? Map.of() : Map.copyOf(expectedStates);
        }
    }

    /** One window opening with host wall identity. */
    public record WindowOpening(
            String windowId,
            String hostWallId,
            int floorIndex,
            String facing,
            Set<BlockPos> cells,
            Map<BlockPos, String> expectedStates
    ) {
        public WindowOpening {
            windowId = windowId == null || windowId.isBlank() ? "window" : windowId;
            hostWallId = hostWallId == null ? "" : hostWallId;
            floorIndex = Math.max(0, floorIndex);
            facing = facing == null ? "" : facing;
            cells = cells == null ? Set.of() : Set.copyOf(cells);
            expectedStates = expectedStates == null ? Map.of() : Map.copyOf(expectedStates);
        }
    }

    /** Minimal wall shell cells touched by façade edits, plus optional face identities. */
    public record WallPart(
            String partId,
            Set<BlockPos> cells,
            Map<BlockPos, String> recordedStates,
            List<WallFace> faces
    ) {
        public WallPart(String partId, Set<BlockPos> cells, Map<BlockPos, String> recordedStates) {
            this(partId, cells, recordedStates, List.of());
        }

        public WallPart {
            partId = partId == null || partId.isBlank() ? "wall" : partId;
            cells = cells == null ? Set.of() : Set.copyOf(cells);
            recordedStates = recordedStates == null ? Map.of() : Map.copyOf(recordedStates);
            faces = faces == null ? List.of() : List.copyOf(faces);
        }

        public boolean isEmpty() {
            return cells.isEmpty();
        }
    }

    /** Openings / glass cells produced by INSERT_WINDOW or sealed by SEAL_WINDOW. */
    public record WindowPart(
            String partId,
            Set<BlockPos> cells,
            Map<BlockPos, String> recordedStates,
            List<WindowOpening> openings
    ) {
        public WindowPart(String partId, Set<BlockPos> cells, Map<BlockPos, String> recordedStates) {
            this(partId, cells, recordedStates, List.of());
        }

        public WindowPart {
            partId = partId == null || partId.isBlank() ? "window" : partId;
            cells = cells == null ? Set.of() : Set.copyOf(cells);
            recordedStates = recordedStates == null ? Map.of() : Map.copyOf(recordedStates);
            openings = openings == null ? List.of() : List.copyOf(openings);
        }

        public boolean isEmpty() {
            return cells.isEmpty();
        }
    }

    private final RoofPart roof;
    private final WallPart walls;
    private final WindowPart windows;

    public BuildingPartIndex(RoofPart roof) {
        this(roof, null, null);
    }

    public BuildingPartIndex(RoofPart roof, WallPart walls, WindowPart windows) {
        this.roof = roof == null ? new RoofPart("roof", Set.of(), Map.of()) : roof;
        this.walls = walls == null ? new WallPart("wall", Set.of(), Map.of()) : walls;
        this.windows = windows == null ? new WindowPart("window", Set.of(), Map.of()) : windows;
    }

    public RoofPart roof() {
        return roof;
    }

    public WallPart walls() {
        return walls;
    }

    public WindowPart windows() {
        return windows;
    }

    public static BuildingPartIndex fromMemory(ProjectMemory memory) {
        return new BuildingPartIndex(
                loadRoof(memory),
                loadWall(memory),
                loadWindow(memory));
    }

    private static RoofPart loadRoof(ProjectMemory memory) {
        return new RoofPart("roof", loadCells(BuildingMemoryKeys.roofCellsRaw(memory)),
                loadStates(BuildingMemoryKeys.roofStatesRaw(memory), BuildingMemoryKeys.roofCellsRaw(memory)));
    }

    private static WallPart loadWall(ProjectMemory memory) {
        return new WallPart("wall", loadCells(BuildingMemoryKeys.wallCellsRaw(memory)),
                loadStates(BuildingMemoryKeys.wallStatesRaw(memory), BuildingMemoryKeys.wallCellsRaw(memory)),
                BuildingMemoryKeys.wallFacesRaw(memory));
    }

    private static WindowPart loadWindow(ProjectMemory memory) {
        return new WindowPart("window", loadCells(BuildingMemoryKeys.windowCellsRaw(memory)),
                loadStates(BuildingMemoryKeys.windowStatesRaw(memory), BuildingMemoryKeys.windowCellsRaw(memory)),
                BuildingMemoryKeys.windowOpeningsRaw(memory));
    }

    private static Set<BlockPos> loadCells(List<int[]> raw) {
        Set<BlockPos> cells = new LinkedHashSet<>();
        for (int[] c : raw) {
            cells.add(new BlockPos(c[0], c[1], c[2]));
        }
        return cells;
    }

    private static Map<BlockPos, String> loadStates(Map<String, String> states, List<int[]> raw) {
        Map<BlockPos, String> recorded = new LinkedHashMap<>();
        for (int[] c : raw) {
            BlockPos pos = new BlockPos(c[0], c[1], c[2]);
            String state = states.get(BuildingMemoryKeys.cellKey(pos));
            if (state != null && !state.isBlank()) recorded.put(pos, state);
        }
        return recorded;
    }

    /**
     * Build roof cells from relative patches that carry roof provenance
     * (component type ROOF / ROOF_STRUCTURE or producer/path containing roof).
     * Prefer {@link #fromFinalPatches} when the list may contain later overwrites/removes.
     */
    public static BuildingPartIndex fromRoofPatches(BlockPos origin, List<BlockPatch> patches) {
        return fromFinalPatches(origin, patches);
    }

    /**
     * Roof index from the final effective writes in a patch list (last-write-wins).
     * A cell is roof only if its last non-skipped write has roof provenance and is not REMOVE.
     * Later non-roof writers or REMOVEs exclude the cell — matching landed world membership.
     */
    public static BuildingPartIndex fromFinalPatches(BlockPos origin, List<BlockPatch> patches) {
        java.util.Objects.requireNonNull(origin, "origin");
        Map<BlockPos, BlockPatch> lastWrite = new LinkedHashMap<>();
        if (patches != null) {
            for (BlockPatch patch : patches) {
                if (patch == null) continue;
                BlockPos world = origin.add(patch.dx(), patch.dy(), patch.dz()).toImmutable();
                lastWrite.put(world, patch);
            }
        }
        Set<BlockPos> cells = new LinkedHashSet<>();
        Map<BlockPos, String> states = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockPatch> e : lastWrite.entrySet()) {
            BlockPatch patch = e.getValue();
            if (BlockPatch.REMOVE.equals(patch.action())) continue;
            if (!isRoofProvenance(patch.source(), null)) continue;
            BlockPos world = e.getKey();
            cells.add(world);
            if (patch.targetBlock() != null && !patch.targetBlock().isBlank()) {
                states.put(world, patch.targetBlock());
            }
        }
        return new BuildingPartIndex(new RoofPart("roof", cells, states));
    }

    /**
     * Merge a final-batch roof index into an existing Memory index.
     * Touched cells that are not roof in the batch (overwrite / remove) are dropped from the index.
     * Untouched existing roof cells are preserved.
     */
    public static BuildingPartIndex mergeFinalBatch(
            BuildingPartIndex existing,
            BlockPos origin,
            List<BlockPatch> patches
    ) {
        BuildingPartIndex base = existing == null
                ? new BuildingPartIndex(new RoofPart("roof", Set.of(), Map.of()))
                : existing;
        if (patches == null || patches.isEmpty()) return base;

        Set<BlockPos> touched = new LinkedHashSet<>();
        Map<BlockPos, BlockPatch> lastWrite = new LinkedHashMap<>();
        for (BlockPatch patch : patches) {
            if (patch == null) continue;
            BlockPos world = origin.add(patch.dx(), patch.dy(), patch.dz()).toImmutable();
            touched.add(world);
            lastWrite.put(world, patch);
        }

        Set<BlockPos> cells = new LinkedHashSet<>(base.roof().cells());
        Map<BlockPos, String> states = new LinkedHashMap<>(base.roof().recordedStates());
        BuildingPartIndex batchRoof = fromFinalPatches(origin, patches);

        for (BlockPos pos : touched) {
            BlockPatch last = lastWrite.get(pos);
            if (last == null) continue;
            if (BlockPatch.REMOVE.equals(last.action())) {
                cells.remove(pos);
                states.remove(pos);
                continue;
            }
            if (batchRoof.roof().cells().contains(pos)) {
                cells.add(pos);
                String state = batchRoof.roof().recordedStates().get(pos);
                if (state != null) states.put(pos, state);
                continue;
            }
            // Non-roof final writer: drop former roof membership at this cell.
            cells.remove(pos);
            states.remove(pos);
        }
        return new BuildingPartIndex(new RoofPart("roof", cells, states), base.walls(), base.windows());
    }

    /**
     * After Undo reverse-patches: refresh recorded states for still-indexed roof cells
     * without requiring reverse patches to carry roof provenance.
     */
    public static BuildingPartIndex syncIndexedStatesFromPatches(
            BuildingPartIndex existing,
            BlockPos origin,
            List<BlockPatch> patches
    ) {
        BuildingPartIndex base = existing == null
                ? new BuildingPartIndex(new RoofPart("roof", Set.of(), Map.of()))
                : existing;
        if (patches == null || patches.isEmpty()) return base;

        Set<BlockPos> roofCells = new LinkedHashSet<>(base.roof().cells());
        Map<BlockPos, String> roofStates = new LinkedHashMap<>(base.roof().recordedStates());
        Set<BlockPos> windowCells = new LinkedHashSet<>(base.windows().cells());
        Map<BlockPos, String> windowStates = new LinkedHashMap<>(base.windows().recordedStates());
        Set<BlockPos> wallCells = new LinkedHashSet<>(base.walls().cells());
        Map<BlockPos, String> wallStates = new LinkedHashMap<>(base.walls().recordedStates());
        List<WindowOpening> openings = new ArrayList<>(base.windows().openings());
        List<WallFace> faces = new ArrayList<>(base.walls().faces());

        for (BlockPatch patch : patches) {
            if (patch == null) continue;
            BlockPos world = origin.add(patch.dx(), patch.dy(), patch.dz()).toImmutable();
            if (roofCells.contains(world)) {
                if (BlockPatch.REMOVE.equals(patch.action())) {
                    roofCells.remove(world);
                    roofStates.remove(world);
                } else if (patch.targetBlock() != null && !patch.targetBlock().isBlank()) {
                    roofStates.put(world, patch.targetBlock());
                }
            }
            boolean wasWindow = windowCells.contains(world);
            boolean wasWall = wallCells.contains(world);
            if (!wasWindow && !wasWall) continue;
            if (BlockPatch.REMOVE.equals(patch.action())) {
                windowCells.remove(world);
                windowStates.remove(world);
                wallCells.remove(world);
                wallStates.remove(world);
                openings.removeIf(o -> o != null && o.cells().contains(world));
                continue;
            }
            String block = patch.targetBlock();
            if (block == null || block.isBlank()) continue;
            boolean glassy = block.toLowerCase(Locale.ROOT).contains("glass")
                    || block.toLowerCase(Locale.ROOT).contains("pane");
            if (glassy) {
                windowCells.add(world);
                windowStates.put(world, block);
                wallCells.remove(world);
                wallStates.remove(world);
            } else {
                // Undo of INSERT_WINDOW restores solid wall and drops opening membership.
                wallCells.add(world);
                wallStates.put(world, block);
                windowCells.remove(world);
                windowStates.remove(world);
                openings.removeIf(o -> o != null && o.cells().contains(world));
            }
        }
        return new BuildingPartIndex(
                new RoofPart("roof", roofCells, roofStates),
                new WallPart("wall", wallCells, wallStates, faces),
                new WindowPart("window", windowCells, windowStates, openings));
    }

    /** Extract from planned blocks whose GeometrySource marks roof authorship. */
    public static BuildingPartIndex fromPlannedBlocks(List<PlannedBlock> blocks) {
        Set<BlockPos> cells = new LinkedHashSet<>();
        Map<BlockPos, String> states = new LinkedHashMap<>();
        if (blocks != null) {
            for (PlannedBlock block : blocks) {
                if (block == null || block.getTargetState() == null || block.getTargetState().isAir()) continue;
                if (!isRoofProvenance(block.getSource(), null)) continue;
                BlockPos pos = block.getPos().toImmutable();
                cells.add(pos);
                states.put(pos, BlockStateStringUtil.fromState(block.getTargetState()));
            }
        }
        return new BuildingPartIndex(new RoofPart("roof", cells, states));
    }

    /**
     * Explicit roof cell set supplied by the compiler (component type already known).
     * Prefer this over heuristics when attaching at registration time.
     */
    public static BuildingPartIndex ofRoofCells(List<BlockPos> cells, Map<BlockPos, String> states) {
        Set<BlockPos> set = new LinkedHashSet<>();
        Map<BlockPos, String> recorded = new LinkedHashMap<>();
        if (cells != null) {
            for (BlockPos pos : cells) {
                if (pos == null) continue;
                BlockPos imm = pos.toImmutable();
                set.add(imm);
                if (states != null && states.get(imm) != null) {
                    recorded.put(imm, states.get(imm));
                }
            }
        }
        return new BuildingPartIndex(new RoofPart("roof", set, recorded));
    }

    public static boolean isRoofComponentType(String componentType) {
        if (componentType == null || componentType.isBlank()) return false;
        String u = componentType.trim().toUpperCase(Locale.ROOT);
        return u.equals("ROOF") || u.equals("ROOF_STRUCTURE") || u.equals("ROOF_COVER");
    }

    public static boolean isRoofProvenance(GeometrySource source, String componentType) {
        if (isRoofComponentType(componentType)) return true;
        if (source == null) return false;
        return mentionsRoof(source.componentPath())
                || mentionsRoof(source.producer())
                || mentionsRoof(source.path())
                || mentionsRoof(source.node());
    }

    private static boolean mentionsRoof(String text) {
        if (text == null || text.isBlank()) return false;
        String u = text.toUpperCase(Locale.ROOT);
        return u.contains("ROOF");
    }

    public void persistTo(ProjectMemory memory) {
        if (memory == null) return;
        BuildingMemoryKeys.storeRoofCells(memory, new ArrayList<>(roof.cells()), roof.recordedStates());
        BuildingMemoryKeys.storeWallCells(memory, new ArrayList<>(walls.cells()), walls.recordedStates());
        BuildingMemoryKeys.storeWallFaces(memory, walls.faces());
        BuildingMemoryKeys.storeWindowCells(memory, new ArrayList<>(windows.cells()), windows.recordedStates());
        BuildingMemoryKeys.storeWindowOpenings(memory, windows.openings());
    }

    /** Merge window edit targets into the window index (INSERT adds glass; SEAL may move cells to walls). */
    public BuildingPartIndex withWindowTargets(
            BuildingEditOperation operation,
            Map<BlockPos, String> targetStates
    ) {
        return withWindowTargets(operation, targetStates, BuildingEditTargetPart.none());
    }

    /**
     * Merge targets and refresh WallFace / WindowOpening identity.
     * INSERT records a new opening under the frozen host wall id; SEAL removes matching openings.
     */
    public BuildingPartIndex withWindowTargets(
            BuildingEditOperation operation,
            Map<BlockPos, String> targetStates,
            BuildingEditTargetPart targetPart
    ) {
        if (targetStates == null || targetStates.isEmpty()) return this;
        Set<BlockPos> windowCells = new LinkedHashSet<>(windows.cells());
        Map<BlockPos, String> windowStates = new LinkedHashMap<>(windows.recordedStates());
        Set<BlockPos> wallCells = new LinkedHashSet<>(walls.cells());
        Map<BlockPos, String> wallStates = new LinkedHashMap<>(walls.recordedStates());
        List<WallFace> faces = new ArrayList<>(walls.faces());
        List<WindowOpening> openings = new ArrayList<>(windows.openings());
        for (var e : targetStates.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            BlockPos cell = e.getKey().toImmutable();
            String block = e.getValue();
            boolean glassy = block.toLowerCase(Locale.ROOT).contains("glass")
                    || block.toLowerCase(Locale.ROOT).contains("pane");
            if (operation == BuildingEditOperation.INSERT_WINDOW || glassy) {
                windowCells.add(cell);
                windowStates.put(cell, block);
                wallCells.remove(cell);
                wallStates.remove(cell);
            } else if (operation == BuildingEditOperation.SEAL_WINDOW) {
                windowCells.remove(cell);
                windowStates.remove(cell);
                wallCells.add(cell);
                wallStates.put(cell, block);
            }
        }
        BuildingEditTargetPart part = targetPart == null ? BuildingEditTargetPart.none() : targetPart;
        if (operation == BuildingEditOperation.INSERT_WINDOW && part.isPresent()) {
            String hostWallId = part.targetPartId();
            faces.removeIf(f -> f != null && hostWallId.equals(f.wallId()));
            faces.add(new WallFace(
                    hostWallId, part.floorIndex(), part.facing(),
                    Set.copyOf(targetStates.keySet()), Map.copyOf(targetStates)));
            String windowId = "window:" + part.facing().toLowerCase(Locale.ROOT)
                    + ":f" + part.floorIndex() + ":" + canonicalCellKey(targetStates.keySet());
            openings.removeIf(o -> o != null && overlaps(o.cells(), targetStates.keySet()));
            openings.add(new WindowOpening(
                    windowId, hostWallId, part.floorIndex(), part.facing(),
                    Set.copyOf(targetStates.keySet()), Map.copyOf(targetStates)));
        } else if (operation == BuildingEditOperation.SEAL_WINDOW) {
            if (part.isPresent()) {
                openings.removeIf(o -> o != null && part.targetPartId().equals(o.windowId()));
            } else {
                openings.removeIf(o -> o != null && overlaps(o.cells(), targetStates.keySet()));
            }
        }
        return new BuildingPartIndex(
                roof,
                new WallPart("wall", wallCells, wallStates, faces),
                new WindowPart("window", windowCells, windowStates, openings));
    }

    private static boolean overlaps(Set<BlockPos> a, Set<BlockPos> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        for (BlockPos cell : b) {
            if (cell != null && a.contains(cell)) return true;
        }
        return false;
    }

    private static String canonicalCellKey(Set<BlockPos> cells) {
        BlockPos min = cells.stream()
                .filter(Objects::nonNull)
                .min(Comparator.comparingInt(BlockPos::getX)
                        .thenComparingInt(BlockPos::getY)
                        .thenComparingInt(BlockPos::getZ))
                .orElse(BlockPos.ORIGIN);
        return min.getX() + "_" + min.getY() + "_" + min.getZ();
    }
}
