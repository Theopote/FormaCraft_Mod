package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Incremental PLACE/REMOVE occupancy during {@link ComponentPlanCompiler#compileComponents}.
 * Detects unauthorized PLACE→PLACE material overwrites with writer context.
 */
public final class PatchOccupancyIndex {
    private static final Set<String> OPENING_WRITERS = Set.of(
            "ENTRANCE", "FACADE_WINDOWS", "DOOR", "WINDOW", "FACADE_OPENING", "FACADE"
    );

    public record PlaceRecord(String writerType, String targetBlock, boolean allowOverwrite) {}

    public record Conflict(BlockPos position, String previousWriter, String previousBlock,
                           String writer, String newBlock, String reason) {}

    private final Map<Long, PlaceRecord> places = new HashMap<>();
    private final Map<Long, Boolean> removes = new HashMap<>();

    public Optional<Conflict> accept(Component writer, List<BlockPatch> patches) {
        if (patches == null || patches.isEmpty()) return Optional.empty();
        String type = writer != null && writer.componentType() != null
                ? writer.componentType().trim().toUpperCase(Locale.ROOT) : "";
        boolean allowOverwrite = writer != null && writer.params() != null
                && Boolean.TRUE.equals(writer.params().get("allow_patch_overwrite"));
        boolean openingWriter = OPENING_WRITERS.contains(type) || allowOverwrite;

        for (BlockPatch p : patches) {
            if (p == null) continue;
            long key = pack(p.dx(), p.dy(), p.dz());
            BlockPos pos = new BlockPos(p.dx(), p.dy(), p.dz());
            if (BlockPatch.REMOVE.equals(p.action())) {
                removes.put(key, Boolean.TRUE);
                places.remove(key); // carve clears place occupancy
                continue;
            }
            if (!BlockPatch.PLACE.equals(p.action())) continue;

            String canonical = canonical(p.targetBlock());
            PlaceRecord prev = places.get(key);
            if (prev != null) {
                String prevCanon = canonical(prev.targetBlock());
                if (prevCanon != null && prevCanon.equals(canonical)) {
                    places.put(key, new PlaceRecord(type, p.targetBlock(), allowOverwrite || prev.allowOverwrite()));
                    continue;
                }
                if (!openingWriter && !prev.allowOverwrite()) {
                    return Optional.of(new Conflict(pos, prev.writerType(), prev.targetBlock(),
                            type, p.targetBlock(), "unauthorized_place_overwrite"));
                }
            }
            places.put(key, new PlaceRecord(type, p.targetBlock(), allowOverwrite));
            removes.remove(key);
        }
        return Optional.empty();
    }

    private static String canonical(String target) {
        return com.formacraft.common.palette.dynamic.ExplicitMaterialPolicy.canonical(target);
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF)) | (((long) (y & 0x3FF)) << 21) | (((long) (z & 0x1FFFFF)) << 31);
    }
}
