package com.formacraft.server.compiler;

import com.formacraft.common.compiler.postprocess.PostProcessPatchDelta;
import com.formacraft.common.compiler.postprocess.PostProcessWritePolicy;
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
    public record PlaceRecord(String writerType, String targetBlock) {}

    public record Conflict(BlockPos position, String previousWriter, String previousBlock,
                           String writer, String newBlock, String reason) {}

    private final Map<BlockPos, PlaceRecord> places = new HashMap<>();
    private final Set<BlockPos> removes = new HashSet<>();

    /** Read-only: does not mutate formal state. */
    public Optional<Conflict> validate(Component writer, List<BlockPatch> patches) {
        if (patches == null || patches.isEmpty()) return Optional.empty();
        Map<BlockPos, PlaceRecord> simPlaces = new HashMap<>(places);
        Set<BlockPos> simRemoves = new HashSet<>(removes);
        return apply(writer, patches, simPlaces, simRemoves);
    }

    /** Mutates formal state; call only after {@link #validate} succeeded. */
    public void commit(Component writer, List<BlockPatch> patches) {
        if (patches == null || patches.isEmpty()) return;
        apply(writer, patches, places, removes);
    }

    /** Convenience: validate then commit if ok. */
    public Optional<Conflict> accept(Component writer, List<BlockPatch> patches) {
        Optional<Conflict> conflict = validate(writer, patches);
        if (conflict.isPresent()) return conflict;
        commit(writer, patches);
        return Optional.empty();
    }

    /**
     * Validate a post-process delta under processor-scoped write policy.
     * Does not mutate formal state.
     */
    public Optional<Conflict> validatePostProcess(
            String processorId,
            PostProcessWritePolicy policy,
            PostProcessPatchDelta delta
    ) {
        if (delta == null || delta.isEmpty()) return Optional.empty();
        Component synthetic = syntheticWriter(processorId, policy);
        return validate(synthetic, delta.toPatchBatch());
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
            PostProcessPatchDelta delta
    ) {
        Optional<Conflict> conflict = validatePostProcess(processorId, policy, delta);
        if (conflict.isPresent()) return conflict;
        commitPostProcess(processorId, policy, delta);
        return Optional.empty();
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
            Set<BlockPos> removeSet
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
                if (existing != null && !carveOk) {
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
                    placeMap.put(pos, new PlaceRecord(type, p.targetBlock()));
                    removeSet.remove(pos);
                    continue;
                }
                if (!overwriteOk) {
                    return Optional.of(new Conflict(pos, prev.writerType(), prev.targetBlock(),
                            type, p.targetBlock(), "unauthorized_place_overwrite"));
                }
            }
            placeMap.put(pos, new PlaceRecord(type, p.targetBlock()));
            removeSet.remove(pos);
        }
        return Optional.empty();
    }

    static String canonical(String target) {
        return com.formacraft.common.palette.dynamic.ExplicitMaterialPolicy.canonical(target);
    }
}
