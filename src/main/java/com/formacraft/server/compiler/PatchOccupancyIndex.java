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
            "STAIR", "STAIRS", "STAIR_SYSTEM", "STAIRCASE"
    );
    /** Ground circulation that a GATE may replace at the threshold. */
    private static final Set<String> GATE_PATH_SURFACES = Set.of(
            "PATH", "ROAD", "PAVING", "PLAZA", "COURTYARD", "COURTYARD_SPACE"
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
        if (writer != null && "COURTYARD_SPACE".equalsIgnoreCase(writer.componentType())
                && (writer.dimensions() == null || writer.dimensions().height() != 1
                    || writer.params() == null || !"plate".equals(writer.params().get("extrude_mode")))) return 0;
        if (writer == null || !Set.of("DECOR_DETAIL", "PATH", "ROAD", "PAVING", "PLAZA", "COURTYARD", "COURTYARD_SPACE", "FOUNDATION", "BALCONY", "TERRACE")
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
            if (!BlockPatch.PLACE.equals(p.action())) return false;
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

    public int trimWallTowerJoins(Component writer, List<BlockPatch> patches) {
        if (writer == null || !Set.of("WALL","WALL_SEGMENT").contains(writer.componentType())) return 0;
        var joins = new HashSet<BlockPos>();
        var buildingIds = new HashSet<Object>();
        for (var record : places.values()) {
            var owner=record.owner();
            if (owner!=null && owner.params()!=null && ("TOWER".equals(owner.componentType()) || com.formacraft.common.generation.component.util.MassComponentKinds.isCirculationMass(owner)
                    || "tower_gate_shell".equals(owner.params().get("geometry_source")))) {
                Object id = owner.params().get("component_id");
                if (id != null) buildingIds.add(id);
            }
        }
        for (var p : patches) {
            var pos = new BlockPos(p.dx(),p.dy(),p.dz());
            var previous = places.get(pos);
            if (previous == null || previous.owner() == null) continue;
            var owner = previous.owner();
            if ("TOWER".equals(owner.componentType()) || com.formacraft.common.generation.component.util.MassComponentKinds.isCirculationMass(owner) || owner.params() != null
                    && ("tower_gate_shell".equals(owner.params().get("geometry_source"))
                        || buildingIds.contains(owner.params().get("host_id")))) joins.add(pos);
        }
        if (joins.size()*2 >= patches.size()) return 0;
        int before = patches.size();
        patches.removeIf(p -> joins.contains(new BlockPos(p.dx(),p.dy(),p.dz())));
        return before-patches.size();
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
                if (existing != null && !carveOk && !isHostedStairJoin(writer, existing, pos)
                        && !isBoundUpperMassSeam(writer, existing, pos)
                        && !isHostedMassSecondaryCarve(writer, existing)
                        && !connectorAccess.contains(pos)) {
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
                boolean gateOnPath = "GATE".equals(type)
                        && prev.writerType() != null
                        && GATE_PATH_SURFACES.contains(prev.writerType());
                if (!overwriteOk && !chimneyFooting && !gateOnPath && !connectorAccess.contains(pos)
                        && !isHostedStructuralJoin(writer, prev) && !isHostedStairJoin(writer, prev, pos)
                        && !isBoundUpperMassSeam(writer, prev, pos)) {
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

    private static boolean isBoundUpperMassSeam(Component writer, PlaceRecord previous, BlockPos pos) {
        if (writer == null || writer.params() == null || previous.owner() == null || previous.owner().params() == null
                || !java.util.Objects.equals(writer.slotId(), previous.owner().slotId())
                || writer.componentType() == null || !writer.componentType().startsWith("MASS")
                || previous.owner().componentType() == null || !previous.owner().componentType().startsWith("MASS")) return false;
        var owner=previous.owner();
        if (!bindsToOwner(writer, String.valueOf(owner.params().get("component_id")),
                String.valueOf(owner.params().get("host_id")))) return false;
        var upper=com.formacraft.common.generation.component.util.ComponentFootprintUtil.bounds(writer);
        var lower=com.formacraft.common.generation.component.util.ComponentFootprintUtil.bounds(owner);
        if (upper == null || lower == null) return false;
        if (pos.getX()<upper.minX() || pos.getX()>=upper.maxX()
                || pos.getZ()<upper.minZ() || pos.getZ()>=upper.maxZ()
                || pos.getY()<upper.minY() || pos.getY()>=upper.maxY()) return false;
        // A bound cap may inset into the upper half of a solid sculptural body, never a habitable shell.
        boolean solidCap = Boolean.FALSE.equals(owner.params().get("hollow"))
                && !owner.params().containsKey("floor_count")
                && Set.of("circle", "sphere").contains(String.valueOf(owner.params().get("shape")))
                && upper.minY() >= lower.minY()+(lower.maxY()-lower.minY())/2;
        return (solidCap || upper.minY() >= lower.maxY()-1
                && pos.getY() >= lower.maxY()-1 && pos.getY() <= lower.maxY()
                )
                && upper.minX() >= lower.minX() && upper.maxX() <= lower.maxX()
                && upper.minZ() >= lower.minZ() && upper.maxZ() <= lower.maxZ();
    }

    /**
     * Bridge piers / secondary supports may carve cells of their host deck or abutment
     * without a blanket allow_carve grant (common LLM bridge composition).
     */
    private static boolean isHostedMassSecondaryCarve(Component writer, PlaceRecord previous) {
        if (writer == null || writer.params() == null || previous.owner() == null
                || previous.owner().params() == null
                || !"MASS_SECONDARY".equalsIgnoreCase(writer.componentType())) {
            return false;
        }
        Object ownerId = previous.owner().params().get("component_id");
        String ownerIdStr = ownerId == null ? null : String.valueOf(ownerId).trim();
        Object ownerHost = previous.owner().params().get("host_id");
        String ownerHostStr = ownerHost == null ? null : String.valueOf(ownerHost).trim();
        if (!bindsToOwner(writer, ownerIdStr, ownerHostStr)) return false;
        return Set.of("MASS_MAIN", "MAIN_MASS", "MASS_SECONDARY", "FOUNDATION", "KEEP")
                .contains(previous.writerType() == null ? "" : previous.writerType());
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
        return Set.of("ROOF", "BALCONY", "TERRACE", "DECOR_DETAIL", "TOWER", "CHIMNEY", "GATE")
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

    private static boolean isHostedStairJoin(Component writer, PlaceRecord previous, BlockPos pos) {
        if (!com.formacraft.server.generation.component.impl.StraightStairComponentGenerator.accepts(writer)
                || writer.params() == null || previous.owner() == null || previous.owner().params() == null) return false;
        boolean exterior = Boolean.TRUE.equals(writer.params().get("exterior"));
        Object target = writer.params().get("target_host_id");
        Object prevHost = previous.owner().params().get("host_id");
        Object prevId = previous.owner().params().get("component_id");
        Object prevBuilding = previous.owner().params().get("building_id");
        if (target instanceof String targetId && !targetId.isBlank()) {
            if (targetId.equals(prevHost) || targetId.equals(prevId)) {
                if (Set.of("TERRACE", "BALCONY", "ROOF", "ROOF_STRUCTURE", "FOUNDATION", "DECOR_DETAIL",
                        "MASS_MAIN", "MAIN_MASS", "TOWER", "MASS_SECONDARY").contains(previous.writerType())) {
                    return !exterior || !isRoofWriter(previous.writerType())
                            || withinExteriorStairEnvelope(writer, pos);
                }
            }
            if (targetId.equals(prevHost)
                    && (Set.of("TERRACE", "BALCONY").contains(previous.writerType())
                        || "MASS_SECONDARY".equals(previous.writerType())
                            && "plate".equals(previous.owner().params().get("extrude_mode")))) return true;
        }
        // Exterior corridor enclosures may open across slots; roofs need host link + envelope.
        if (exterior) {
            if ("DECOR_DETAIL".equals(previous.writerType())
                    && Boolean.TRUE.equals(previous.owner().params().get("corridor_enclosure"))) {
                return true;
            }
            if (isRoofWriter(previous.writerType()) && exteriorRoofLinked(writer, prevHost, prevId, prevBuilding)
                    && withinExteriorStairEnvelope(writer, pos)) {
                return true;
            }
        }
        Object host = writer.params().get("host_id");
        if (!(host instanceof String id) || id.isBlank()) return false;
        if (!java.util.Objects.equals(writer.slotId(), previous.owner().slotId())) return false;
        Object building = writer.params().get("building_id");
        if (building != null && building.equals(prevBuilding)
                && Set.of("ROOF", "FOUNDATION", "MASS_SECONDARY", "TERRACE", "BALCONY", "DECOR_DETAIL").contains(previous.writerType())) {
            return !exterior || !isRoofWriter(previous.writerType()) || withinExteriorStairEnvelope(writer, pos);
        }
        if (Set.of("MASS_MAIN", "MAIN_MASS", "TOWER", "MASS_SECONDARY").contains(previous.writerType())
                && !"plate".equals(previous.owner().params().get("extrude_mode")))
            return id.equals(prevId);
        boolean softJoin = (Set.of("TERRACE", "BALCONY", "FOUNDATION", "DECOR_DETAIL", "ROOF").contains(previous.writerType())
                || ("MASS_SECONDARY".equals(previous.writerType())
                    && "plate".equals(previous.owner().params().get("extrude_mode"))))
                && id.equals(prevHost);
        if (!softJoin) return false;
        return !exterior || !isRoofWriter(previous.writerType()) || withinExteriorStairEnvelope(writer, pos);
    }

    private static boolean isRoofWriter(String writerType) {
        return "ROOF".equals(writerType) || "ROOF_STRUCTURE".equals(writerType);
    }

    private static boolean exteriorRoofLinked(Component writer, Object prevHost, Object prevId, Object prevBuilding) {
        Object host = writer.params().get("host_id");
        Object target = writer.params().get("target_host_id");
        Object building = writer.params().get("building_id");
        if (host != null && (host.equals(prevHost) || host.equals(prevId))) return true;
        if (target != null && (target.equals(prevHost) || target.equals(prevId))) return true;
        return building != null && (building.equals(prevBuilding) || building.equals(prevHost) || building.equals(prevId));
    }

    /** Endpoint neighborhood of an exterior flight: run + landing, width, clearHeight(+1 transition). */
    static boolean withinExteriorStairEnvelope(Component writer, BlockPos pos) {
        if (writer == null || writer.params() == null || pos == null) return false;
        Object fromObj = writer.params().get("from");
        Object toObj = writer.params().get("to");
        if (!(fromObj instanceof Map<?, ?> from) || !(toObj instanceof Map<?, ?> to)) return false;
        var rp = writer.relativePosition();
        int ox = rp == null ? 0 : rp.x(), oy = rp == null ? 0 : rp.y(), oz = rp == null ? 0 : rp.z();
        int fx = number(from.get("x")), fy = number(from.get("y")), fz = number(from.get("z"));
        int tx = number(to.get("x")), ty = number(to.get("y")), tz = number(to.get("z"));
        int dx = Integer.signum(tx - fx), dz = Integer.signum(tz - fz);
        int run = Math.max(Math.abs(tx - fx), Math.abs(tz - fz));
        int rise = ty - fy;
        int width = number(writer.params().getOrDefault("width", 3));
        int landing = number(writer.params().getOrDefault("landing_length", 1));
        int clear = number(writer.params().getOrDefault("clearHeight",
                writer.params().getOrDefault("clear_h", 2))) + 1;
        for (int sample = 0; sample <= run + Math.max(0, landing); sample++) {
            int stepY = fy + (int) Math.round(rise * (Math.min(run, sample) / (double) Math.max(1, run)));
            for (int side = -width / 2; side < width - width / 2; side++) {
                for (int y = -1; y <= clear; y++) {
                    int px = ox + fx + dx * sample + (dz != 0 ? side : 0);
                    int py = oy + stepY + y;
                    int pz = oz + fz + dz * sample + (dx != 0 ? side : 0);
                    if (pos.getX() == px && pos.getY() == py && pos.getZ() == pz) return true;
                }
            }
        }
        return false;
    }

    private static int number(Object value) {
        if (value instanceof Number n) return n.intValue();
        return new java.math.BigDecimal(String.valueOf(value)).intValueExact();
    }
}
