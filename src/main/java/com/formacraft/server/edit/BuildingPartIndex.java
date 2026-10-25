package com.formacraft.server.edit;

import com.formacraft.common.build.GeometrySource;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.component.transform.BlockStateStringUtil;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.memory.ProjectMemory;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Minimal part index for Phase 1: roof cells only.
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

    private final RoofPart roof;

    public BuildingPartIndex(RoofPart roof) {
        this.roof = roof == null ? new RoofPart("roof", Set.of(), Map.of()) : roof;
    }

    public RoofPart roof() {
        return roof;
    }

    public static BuildingPartIndex fromMemory(ProjectMemory memory) {
        List<int[]> raw = BuildingMemoryKeys.roofCellsRaw(memory);
        Map<String, String> states = BuildingMemoryKeys.roofStatesRaw(memory);
        Set<BlockPos> cells = new LinkedHashSet<>();
        Map<BlockPos, String> recorded = new LinkedHashMap<>();
        for (int[] c : raw) {
            BlockPos pos = new BlockPos(c[0], c[1], c[2]);
            cells.add(pos);
            String state = states.get(BuildingMemoryKeys.cellKey(pos));
            if (state != null && !state.isBlank()) {
                recorded.put(pos, state);
            }
        }
        return new BuildingPartIndex(new RoofPart("roof", cells, recorded));
    }

    /**
     * Build roof cells from relative patches that carry roof provenance
     * (component type ROOF / ROOF_STRUCTURE or producer/path containing roof).
     */
    public static BuildingPartIndex fromRoofPatches(BlockPos origin, List<BlockPatch> patches) {
        Objects.requireNonNull(origin, "origin");
        Set<BlockPos> cells = new LinkedHashSet<>();
        Map<BlockPos, String> states = new LinkedHashMap<>();
        if (patches != null) {
            for (BlockPatch patch : patches) {
                if (patch == null || !isRoofProvenance(patch.source(), null)) continue;
                if (BlockPatch.REMOVE.equals(patch.action())) continue;
                BlockPos world = origin.add(patch.dx(), patch.dy(), patch.dz());
                cells.add(world.toImmutable());
                if (patch.targetBlock() != null && !patch.targetBlock().isBlank()) {
                    states.put(world.toImmutable(), patch.targetBlock());
                }
            }
        }
        return new BuildingPartIndex(new RoofPart("roof", cells, states));
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
    }
}
