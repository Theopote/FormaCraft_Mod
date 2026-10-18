package com.formacraft.server.compiler;

import com.formacraft.common.compiler.postprocess.PostProcessPatchDelta;
import com.formacraft.common.compiler.postprocess.PostProcessWritePolicy;
import com.formacraft.common.compiler.postprocess.PostProcessWriteScope;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Incremental PLACE/REMOVE occupancy during compile + post-process commit.
 * Overwrite/carve authority is evaluated per current writer only (never inherited).
 * Post-process writers use {@link PostProcessWritePolicy} in synthetic params — never a blanket POSTPROCESS grant.
 */
public final class PatchOccupancyIndex {
    static final Set<String> OPENING_WRITERS = Set.of(
            "ENTRANCE", "FACADE_WINDOWS", "DOOR", "WINDOW", "FACADE_OPENING", "FACADE"
    );
    static final Set<String> CARVE_WRITERS = Set.of(
            "ENTRANCE", "FACADE_WINDOWS", "DOOR", "WINDOW", "FACADE_OPENING", "FACADE",
            "STAIR", "STAIRS"
    );

    /** No sticky overwrite flag — authority is not inherited across writers. */
    public record PlaceRecord(String writerType, String targetBlock, Component owner) {}

    public record Conflict(BlockPos position, String previousWriter, String previousBlock,
                           String writer, String newBlock, String reason) {}

    private final Map<BlockPos, PlaceRecord> places = new HashMap<>();
    private final Set<BlockPos> removes = new HashSet<>();

    /** Read-only: does not mutate formal state. */
    public Optional<Conflict> validate(Component writer, List<BlockPatch> patches) {
        return validate(writer, patches, Set.of());
    }

    public Optional<Conflict> validate(Component writer, List<BlockPatch> patches, Set<BlockPos> connectorAccess) {
        if (patches == null || patches.isEmpty()) return Optional.empty();
        Map<BlockPos, PlaceRecord> simPlaces = new HashMap<>(places);
        Set<BlockPos> simRemoves = new HashSet<>(removes);
        return apply(writer, patches, simPlaces, simRemoves, connectorAccess);
    }

    /** Preserve existing geometry when an optional attachment cannot join it. */
    public int trimIncompatibleAttachment(Component writer, List<BlockPatch> patches, Set<BlockPos> connectorAccess) {
        if (writer == null || !Set.of("DECOR_DETAIL", "PATH", "ROAD", "PAVING", "PLAZA", "COURTYARD", "FOUNDATION", "BALCONY", "TERRACE")
                .contains(writer.componentType().toUpperCase(Locale.ROOT))) return 0;
        Map<BlockPos, PlaceRecord> simulation = new HashMap<>(places);
        Set<BlockPos> removed = new HashSet<>(removes);
        int before = patches.size();
        patches.removeIf(p -> apply(writer, List.of(p), simulation, removed, connectorAccess).isPresent());
        return before - patches.size();
    }

    /** Mutates formal state; call only after {@link #validate} succeeded. */
    public int trimCompoundMassJoin(Component writer, List<BlockPatch> patches) {
        if (!com.formacraft.common.generation.component.util.MassComponentKinds.isCirculationMass(writer)
                || writer.slotId() == null || patches.isEmpty()) return 0;
        long occupied = patches.stream().filter(p -> BlockPatch.PLACE.equals(p.action())).count();
        long overlap = patches.stream().filter(p -> {
            var prev = places.get(new BlockPos(p.dx(), p.dy(), p.dz()));
            return prev != null && prev.owner() != null
                    && com.formacraft.common.generation.component.util.MassComponentKinds.isCirculationMass(prev.owner());
        }).count();
        // A small shared edge can be joined; a mostly superimposed building is a layout error.
        boolean connector = writer.params() != null && Boolean.TRUE.equals(writer.params().get("compiler_connector_body"));
        if (occupied == 0 || !connector && overlap * 2 >= occupied) return 0;
        int before = patches.size();
        patches.removeIf(p -> {
            var prev = places.get(new BlockPos(p.dx(), p.dy(), p.dz()));
            return prev != null && prev.owner() != null
                    && (!java.util.Objects.equals(writer.slotId(), prev.owner().slotId())
                        || isSculpturalVolume(writer))
                    && com.formacraft.common.generation.component.util.MassComponentKinds.isCirculationMass(prev.owner())
                    && (!BlockPatch.PLACE.equals(p.action()) || !java.util.Objects.equals(canonical(p.targetBlock()), canonical(prev.targetBlock())));
        });
        return before - patches.size();
    }

    private static boolean isSculpturalVolume(Component c) {
        if (c == null || c.params() == null || !"MASS_SECONDARY".equalsIgnoreCase(c.componentType())) return false;
        if (c.params().containsKey("floor_count")) return false;
        return "sphere".equals(c.params().get("volume_geometry"))
                || "cylinder".equals(c.params().get("shape"));
    }

    /** Mutates formal state; call only after {@link #validate} succeeded. */
    public void commit(Component writer, List<BlockPatch> patches) {
        commit(writer, patches, Set.of());
    }

    public void commit(Component writer, List<BlockPatch> patches, Set<BlockPos> connectorAccess) {
        if (patches == null || patches.isEmpty()) return;
        apply(writer, patches, places, removes, connectorAccess);
    }

    /** Convenience: validate then commit if ok. */
    public Optional<Conflict> accept(Component writer, List<BlockPatch> patches) {
        Optional<Conflict> conflict = validate(writer, patches);
        if (conflict.isPresent()) return conflict;
        commit(writer, patches);
        return Optional.empty();
    }

    /**
     * Validate a post-process delta under write policy + spatial {@link PostProcessWriteScope}.
     * Does not mutate formal state.
     */
    public Optional<Conflict> validatePostProcess(
            String processorId,
            PostProcessWritePolicy policy,
            PostProcessPatchDelta delta,
            PostProcessWriteScope scope
    ) {
        if (delta == null || delta.isEmpty()) return Optional.empty();
        Optional<Conflict> scopeConflict = validateScope(processorId, delta, scope);
        if (scopeConflict.isPresent()) return scopeConflict;
        Component synthetic = syntheticWriter(processorId, policy);
        return validate(synthetic, delta.toPatchBatch());
    }

    /** Backward-compatible: unbounded spatial scope (tests that only assert policy). */
    public Optional<Conflict> validatePostProcess(
            String processorId,
            PostProcessWritePolicy policy,
            PostProcessPatchDelta delta
    ) {
        return validatePostProcess(processorId, policy, delta, unboundedScope());
    }

    /**
     * Commit a post-process delta. Call only after {@link #validatePostProcess} succeeded.
     */
    public void commitPostProcess(
            String processorId,
            PostProcessWritePolicy policy,
            PostProcessPatchDelta delta
    ) {
        if (delta == null || delta.isEmpty()) return;
        Component synthetic = syntheticWriter(processorId, policy);
        commit(synthetic, delta.toPatchBatch());
    }

    /** Validate then commit a post-process delta if ok. */
    public Optional<Conflict> acceptPostProcess(
            String processorId,
            PostProcessWritePolicy policy,
            PostProcessPatchDelta delta,
            PostProcessWriteScope scope
    ) {
        Optional<Conflict> conflict = validatePostProcess(processorId, policy, delta, scope);
        if (conflict.isPresent()) return conflict;
        commitPostProcess(processorId, policy, delta);
        return Optional.empty();
    }

    public Optional<Conflict> acceptPostProcess(
            String processorId,
            PostProcessWritePolicy policy,
            PostProcessPatchDelta delta
    ) {
        return acceptPostProcess(processorId, policy, delta, unboundedScope());
    }

    private static Optional<Conflict> validateScope(
            String processorId,
            PostProcessPatchDelta delta,
            PostProcessWriteScope scope
    ) {
        if (scope == null) return Optional.empty();
        String writer = processorId == null || processorId.isBlank()
                ? "POSTPROCESS" : "POSTPROCESS:" + processorId.trim();
        for (BlockPos pos : delta.removedPlaces().keySet()) {
            if (!scope.allowsCarve(pos)) {
                return Optional.of(new Conflict(pos, null, null, writer, "minecraft:air", "out_of_scope_remove"));
            }
        }
        for (BlockPos pos : delta.addedPlaces().keySet()) {
            if (!scope.allowsPlace(pos)) {
                return Optional.of(new Conflict(pos, null, null, writer,
                        delta.addedPlaces().get(pos), "out_of_scope_place"));
            }
        }
        for (BlockPos pos : delta.replacedPlaces().keySet()) {
            if (!scope.allowsPlace(pos)) {
                return Optional.of(new Conflict(pos, null, null, writer,
                        delta.replacedPlaces().get(pos), "out_of_scope_place"));
            }
        }
        return Optional.empty();
    }

    private static PostProcessWriteScope unboundedScope() {
        return PostProcessWriteScope.unbounded();
    }

    private static Component syntheticWriter(String processorId, PostProcessWritePolicy policy) {
        String type = (processorId == null || processorId.isBlank())
                ? "POSTPROCESS"
                : "POSTPROCESS:" + processorId.trim();
        PostProcessWritePolicy p = policy == null ? PostProcessWritePolicy.PLACE_ONLY : policy;
        return new Component(type, null, null, null, List.of(), Map.of(
                "write_policy", p.name(),
                "postprocess_policy", p.name()
        ));
    }

    public PlaceRecord placeAt(BlockPos pos) {
        return pos == null ? null : places.get(pos);
    }

    public boolean isEmpty() {
        return places.isEmpty() && removes.isEmpty();
    }

    static boolean canCarve(Component writer) {
        if (writer == null) return false;
        String type = writer.componentType() == null ? "" : writer.componentType().trim().toUpperCase(Locale.ROOT);
        if (type.startsWith("POSTPROCESS")) {
            return policyOf(writer).allowsCarve();
        }
        if (CARVE_WRITERS.contains(type)) return true;
        Map<String, Object> params = writer.params();
        if (params == null) return false;
        if (com.formacraft.common.generation.component.util.HostedPlateOccupancy.isBridgePlate(params)) {
            return true;
        }
        return Boolean.TRUE.equals(params.get("allow_carve"))
                || Boolean.TRUE.equals(params.get("allow_patch_overwrite"));
    }

    static boolean canOverwritePlace(Component writer) {
        if (writer == null) return false;
        String type = writer.componentType() == null ? "" : writer.componentType().trim().toUpperCase(Locale.ROOT);
        if (type.startsWith("POSTPROCESS")) {
            return policyOf(writer).allowsOverwrite();
        }
        if (OPENING_WRITERS.contains(type)) return true;
        Map<String, Object> params = writer.params();
        return params != null && Boolean.TRUE.equals(params.get("allow_patch_overwrite"));
    }

    private static PostProcessWritePolicy policyOf(Component writer) {
        Map<String, Object> params = writer == null ? null : writer.params();
        if (params == null) return PostProcessWritePolicy.PLACE_ONLY;
        Object raw = params.get("write_policy");
        if (raw == null) raw = params.get("postprocess_policy");
        return PostProcessWritePolicy.fromParam(raw);
    }

    private static Optional<Conflict> apply(
            Component writer,
            List<BlockPatch> patches,
            Map<BlockPos, PlaceRecord> placeMap,
            Set<BlockPos> removeSet,
            Set<BlockPos> connectorAccess
    ) {
        String type = writer != null && writer.componentType() != null
                ? writer.componentType().trim().toUpperCase(Locale.ROOT) : "";
        boolean overwriteOk = canOverwritePlace(writer);
        boolean carveOk = canCarve(writer);

        for (BlockPatch p : patches) {
            if (p == null) continue;
            BlockPos pos = new BlockPos(p.dx(), p.dy(), p.dz());
            if (BlockPatch.REMOVE.equals(p.action())) {
                PlaceRecord existing = placeMap.get(pos);
                if (existing != null && !carveOk && !isHostedStairJoin(writer, existing) && !connectorAccess.contains(pos)) {
                    return Optional.of(new Conflict(pos, existing.writerType(), existing.targetBlock(),
                            type, p.targetBlock(), "unauthorized_remove"));
                }
                removeSet.add(pos);
                placeMap.remove(pos);
                continue;
            }
            if (!BlockPatch.PLACE.equals(p.action())) continue;

            String canon = canonical(p.targetBlock());
            PlaceRecord prev = placeMap.get(pos);
            if (prev != null) {
                String prevCanon = canonical(prev.targetBlock());
                if (prevCanon != null && prevCanon.equals(canon)) {
                    // Same material: replace ownership with current writer only (no privilege inherit).
                    placeMap.put(pos, new PlaceRecord(type, p.targetBlock(), writer));
                    removeSet.remove(pos);
                    continue;
                }
                boolean chimneyFooting = "CHIMNEY".equals(type)
                        && (Set.of("FOUNDATION", "ROOF", "ROOF_STRUCTURE").contains(prev.writerType())
                            || (prev.owner() != null && prev.owner().params() != null
                                && bindsToOwner(writer,
                                    prev.owner().params().get("component_id") == null ? null
                                            : String.valueOf(prev.owner().params().get("component_id")).trim(),
                                    prev.owner().params().get("host_id") == null ? null
                                            : String.valueOf(prev.owner().params().get("host_id")).trim())
                                && Set.of("MASS_MAIN", "MAIN_MASS", "MASS_SECONDARY", "TOWER",
                                        "FOUNDATION", "ROOF", "ROOF_STRUCTURE").contains(prev.writerType())));
                if (!overwriteOk && !chimneyFooting && !connectorAccess.contains(pos)
                        && !isHostedStructuralJoin(writer, prev) && !isHostedStairJoin(writer, prev)) {
                    return Optional.of(new Conflict(pos, prev.writerType(), prev.targetBlock(),
                            type, p.targetBlock(), "unauthorized_place_overwrite"));
                }
            }
            placeMap.put(pos, new PlaceRecord(type, p.targetBlock(), writer));
            removeSet.remove(pos);
        }
        return Optional.empty();
    }

    static String canonical(String target) {
        return com.formacraft.common.palette.dynamic.ExplicitMaterialPolicy.canonical(target);
    }

    private static boolean isHostedStructuralJoin(Component writer, PlaceRecord previous) {
        if (writer == null || writer.params() == null || previous.owner() == null
                || previous.owner().params() == null) return false;
        Object ownerId = previous.owner().params().get("component_id");
        String ownerIdStr = ownerId == null ? null : String.valueOf(ownerId).trim();
        Object ownerHost = previous.owner().params().get("host_id");
        String ownerHostStr = ownerHost == null ? null : String.valueOf(ownerHost).trim();
        if (!bindsToOwner(writer, ownerIdStr, ownerHostStr)) {
            return false;
        }
        boolean bridge = com.formacraft.common.generation.component.util.HostedPlateOccupancy
                .isBridgePlate(writer.params());
        // Bridges may join either endpoint mass even when slot_id differs across dual masses.
        if (!bridge && !java.util.Objects.equals(writer.slotId(), previous.owner().slotId())) {
            return false;
        }
        if ("MASS_SECONDARY".equalsIgnoreCase(writer.componentType()) && writer.params().containsKey("tier_index")) {
            var tier = com.formacraft.common.generation.component.util.ComponentFootprintUtil.bounds(writer);
            var base = com.formacraft.common.generation.component.util.ComponentFootprintUtil.bounds(previous.owner());
            if (tier != null && base != null && tier.minY() >= base.maxY()-1) return true;
        }
        if (!Set.of("MASS_MAIN", "MAIN_MASS", "MASS_WING", "SIDE_WING", "MASS_SECONDARY", "TOWER", "FOUNDATION", "BALCONY", "TERRACE", "DECOR_DETAIL", "ROOF")
                .contains(previous.writerType())) return false;
        // Bound structural/detail parts may join their own building. The surface
        // ledger still protects roofs and openings from decoration; REMOVE uses
        // the separate stair/carve rules.
        return Set.of("ROOF", "BALCONY", "TERRACE", "DECOR_DETAIL", "TOWER", "CHIMNEY")
                .contains(writer.componentType().toUpperCase(Locale.ROOT))
                || ("MASS_SECONDARY".equalsIgnoreCase(writer.componentType())
                    && "plate".equalsIgnoreCase(String.valueOf(writer.params().get("extrude_mode"))));
    }

    private static boolean bindsToOwner(Component writer, String ownerId, String ownerHost) {
        Object host = writer.params().get("host_id");
        if (host instanceof String id && !id.isBlank()
                && (id.equals(ownerId) || id.equals(ownerHost))) {
            return true;
        }
        Object target = writer.params().get("target_host_id");
        if (target == null) {
            target = writer.params().get("targetHostId");
        }
        return target instanceof String tid && !tid.isBlank()
                && (tid.equals(ownerId) || tid.equals(ownerHost));
    }

    private static boolean isHostedStairJoin(Component writer, PlaceRecord previous) {
        if (!com.formacraft.server.generation.component.impl.StraightStairComponentGenerator.accepts(writer)
                || writer.params() == null || previous.owner() == null || previous.owner().params() == null) return false;
        Object host = writer.params().get("host_id");
        if (!(host instanceof String id) || id.isBlank()) return false;
        if (!java.util.Objects.equals(writer.slotId(), previous.owner().slotId())) return false;
        Object building = writer.params().get("building_id");
        if (building != null && building.equals(previous.owner().params().get("building_id"))
                && Set.of("ROOF", "FOUNDATION", "MASS_SECONDARY", "TERRACE", "BALCONY", "DECOR_DETAIL").contains(previous.writerType())) return true;
        if (Set.of("MASS_MAIN", "MAIN_MASS", "TOWER", "MASS_SECONDARY").contains(previous.writerType())
                && !"plate".equals(previous.owner().params().get("extrude_mode")))
            return id.equals(previous.owner().params().get("component_id"));
        return (Set.of("TERRACE", "BALCONY", "FOUNDATION", "DECOR_DETAIL", "ROOF").contains(previous.writerType())
                || ("MASS_SECONDARY".equals(previous.writerType())
                    && "plate".equals(previous.owner().params().get("extrude_mode"))))
                && id.equals(previous.owner().params().get("host_id"));
    }
}
