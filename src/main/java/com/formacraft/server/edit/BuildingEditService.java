package com.formacraft.server.edit;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.memory.MemoryManager;
import com.formacraft.server.memory.ProjectMemory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 1 orchestration: resolve building → load roof index → compile material replace →
 * validate scope. Preview/Apply still go through existing PatchPreviewService.
 */
public final class BuildingEditService {
    public record PreparedEdit(
            BuildingInstance building,
            BuildingEditScope scope,
            List<BlockPatch> patches,
            BuildingEditValidation validation,
            List<String> refusals
    ) {
        public PreparedEdit {
            patches = patches == null ? List.of() : List.copyOf(patches);
            refusals = refusals == null ? List.of() : List.copyOf(refusals);
        }

        public boolean ok() {
            return refusals.isEmpty()
                    && validation != null
                    && validation.accepted()
                    && !patches.isEmpty()
                    && building != null
                    && scope != null;
        }
    }

    private final MemoryManager memoryManager;
    private final BuildingTargetResolver targetResolver;

    public BuildingEditService(MemoryManager memoryManager) {
        this.memoryManager = Objects.requireNonNull(memoryManager, "memoryManager");
        this.targetResolver = new BuildingTargetResolver(memoryManager);
    }

    public BuildingTargetResolver targets() {
        return targetResolver;
    }

    /**
     * Prepare a roof-material replace. {@code liveWorldStates} should be captured by the server
     * for expected-state conflict detection (preview/confirm).
     */
    public PreparedEdit prepareRoofMaterialReplace(
            BuildingTargetResolver.Query targetQuery,
            String materialIntent,
            Map<BlockPos, String> liveWorldStates,
            Set<BlockPos> extraProtectedCells
    ) {
        BuildingTargetResolver.Result target = targetResolver.resolve(targetQuery);
        if (!target.ok()) {
            return new PreparedEdit(null, null, List.of(), null,
                    List.of(BuildingTargetResolver.refusalMessage(target).orElse("目标建筑解析失败")));
        }
        BuildingInstance building = target.instance();
        // Re-check LLM-reported id if present in query.explicit — already authoritative above.
        ProjectMemory memory = memoryManager.getMemory(building.buildingId().toString());
        if (memory == null) {
            return new PreparedEdit(building, null, List.of(), null,
                    List.of("指定建筑未登记在 Memory 中，拒绝猜测性全局修改。"));
        }

        BuildingPartIndex index = BuildingPartIndex.fromMemory(memory);
        if (index.roof().isEmpty()) {
            return new PreparedEdit(building, null, List.of(), null,
                    List.of("无法安全定位屋顶：该建筑没有已登记的屋顶部位索引，禁止退化为整栋重建。"));
        }

        // Authorization is ChangedCells ⊆ allowedCells (roof only). Extra protected cells
        // cover join/dependency cells that must not change even if somehow listed as allowed.
        Set<BlockPos> protectedCells = new LinkedHashSet<>();
        if (extraProtectedCells != null) protectedCells.addAll(extraProtectedCells);

        // Scope expected states are memory-recorded roof cells; liveWorldStates is only for conflict checks.
        BuildingEditScope scope = BuildingEditScope.replaceRoofMaterial(
                building, index.roof(), index.roof().recordedStates(), protectedCells);

        RoofMaterialReplaceCompiler.CompileResult compiled = RoofMaterialReplaceCompiler.compile(
                building.origin(), index.roof(), materialIntent, liveWorldStates);
        if (!compiled.ok()) {
            return new PreparedEdit(building, scope, List.of(), null, compiled.refusals());
        }

        BuildingEditValidation validation = BuildingEditScopeValidator.validate(
                scope,
                building.origin(),
                compiled.patches(),
                BuildingMemoryKeys.revisionOf(memory),
                liveWorldStates == null ? Map.of() : liveWorldStates);
        if (!validation.accepted()) {
            return new PreparedEdit(building, scope, compiled.patches(), validation, validation.conflicts());
        }
        return new PreparedEdit(building, scope, compiled.patches(), validation, List.of());
    }

    /**
     * After a successful Apply of a Phase-1 roof edit: bump revision and refresh recorded roof states.
     * Must not be called on preview cancel / rollback.
     */
    public boolean commitSuccessfulRoofEdit(
            UUID buildingId,
            Identifier dimension,
            long expectedBaseRevision,
            Map<BlockPos, String> newRoofStates
    ) {
        if (buildingId == null || dimension == null) return false;
        ProjectMemory memory = memoryManager.getMemory(buildingId.toString());
        BuildingInstance instance = BuildingInstance.fromMemory(memory);
        if (instance == null) return false;
        if (!instance.dimension().equals(dimension)) return false;
        if (BuildingMemoryKeys.revisionOf(memory) != expectedBaseRevision) return false;

        BuildingPartIndex index = BuildingPartIndex.fromMemory(memory);
        Map<BlockPos, String> states = new LinkedHashMap<>(index.roof().recordedStates());
        if (newRoofStates != null) {
            for (var e : newRoofStates.entrySet()) {
                if (e.getKey() != null && index.roof().cells().contains(e.getKey()) && e.getValue() != null) {
                    states.put(e.getKey(), e.getValue());
                }
            }
        }
        BuildingPartIndex.ofRoofCells(List.copyOf(index.roof().cells()), states).persistTo(memory);
        BuildingMemoryKeys.bumpRevision(memory);
        memoryManager.updateMemory(memory);
        return true;
    }

    /** Attach a roof part index when a building is first registered (compile provenance). */
    public static void attachRoofIndex(ProjectMemory memory, BuildingPartIndex index) {
        if (memory == null || index == null || index.roof().isEmpty()) return;
        index.persistTo(memory);
        if (!memory.getMetadata().containsKey(BuildingMemoryKeys.REVISION)) {
            BuildingMemoryKeys.setRevision(memory, 0L);
        }
    }

    /**
     * Bind a prepared roof edit to a PreviewTicket. Call after {@code PatchPreviewService.issuePreview}.
     * Memory revision advances only when that ticket Apply returns SUCCESS.
     */
    public static void bindPendingToTicket(UUID ticketId, PreparedEdit prepared) {
        if (ticketId == null || prepared == null || !prepared.ok()) return;
        Map<BlockPos, String> newStates = new LinkedHashMap<>();
        BlockPos origin = prepared.building().origin();
        for (BlockPatch patch : prepared.patches()) {
            if (patch == null || patch.targetBlock() == null) continue;
            newStates.put(origin.add(patch.dx(), patch.dy(), patch.dz()).toImmutable(), patch.targetBlock());
        }
        BuildingEditPendingRegistry.put(ticketId, new BuildingEditPendingRegistry.Pending(
                prepared.building().buildingId(),
                prepared.building().dimension(),
                prepared.scope().baseRevision(),
                newStates));
    }
}
