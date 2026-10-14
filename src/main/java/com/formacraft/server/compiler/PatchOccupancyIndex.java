package com.formacraft.server.compiler;

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
 * Incremental PLACE/REMOVE occupancy during {@link ComponentPlanCompiler#compileComponents}.
 * Keys are exact {@link BlockPos} (no bit-packing). Use {@link #validate} then {@link #commit}.
 */
public final class PatchOccupancyIndex {
    static final Set<String> OPENING_WRITERS = Set.of(
            "ENTRANCE", "FACADE_WINDOWS", "DOOR", "WINDOW", "FACADE_OPENING", "FACADE"
    );

    public record PlaceRecord(String writerType, String targetBlock, boolean allowOverwrite) {
        boolean authorizedOverwrite() {
            return allowOverwrite || (writerType != null && OPENING_WRITERS.contains(writerType));
        }
    }

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

    public PlaceRecord placeAt(BlockPos pos) {
        return pos == null ? null : places.get(pos);
    }

    public boolean isEmpty() {
        return places.isEmpty() && removes.isEmpty();
    }

    /** Applies patches to the given maps (simulation or formal). */
    private static Optional<Conflict> apply(
            Component writer,
            List<BlockPatch> patches,
            Map<BlockPos, PlaceRecord> placeMap,
            Set<BlockPos> removeSet
    ) {
        String type = writer != null && writer.componentType() != null
                ? writer.componentType().trim().toUpperCase(Locale.ROOT) : "";
        boolean allowOverwrite = writer != null && writer.params() != null
                && Boolean.TRUE.equals(writer.params().get("allow_patch_overwrite"));
        boolean openingWriter = OPENING_WRITERS.contains(type) || allowOverwrite;

        for (BlockPatch p : patches) {
            if (p == null) continue;
            BlockPos pos = new BlockPos(p.dx(), p.dy(), p.dz());
            if (BlockPatch.REMOVE.equals(p.action())) {
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
                    placeMap.put(pos, new PlaceRecord(type, p.targetBlock(),
                            allowOverwrite || prev.allowOverwrite()));
                    removeSet.remove(pos);
                    continue;
                }
                if (!openingWriter && !prev.allowOverwrite()) {
                    return Optional.of(new Conflict(pos, prev.writerType(), prev.targetBlock(),
                            type, p.targetBlock(), "unauthorized_place_overwrite"));
                }
            }
            placeMap.put(pos, new PlaceRecord(type, p.targetBlock(), allowOverwrite || openingWriter));
            removeSet.remove(pos);
        }
        return Optional.empty();
    }

    static String canonical(String target) {
        return com.formacraft.common.palette.dynamic.ExplicitMaterialPolicy.canonical(target);
    }
}
