package com.formacraft.common.compiler.postprocess;

import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import java.util.*;
import static com.formacraft.common.generation.component.util.GeneratedSurfaceCapture.occupied;

/** Protects already generated exterior cells; never invents missing shell geometry. */
final class ExteriorIntegrityGuard {
    record Result(List<BlockPatch> patches, int restored) {}

    static Result preserve(List<BlockPatch> before, List<BlockPatch> after, PostProcessContext context) {
        if (context.buildingVolumes().isEmpty() && context.generatedSurfaces().isEmpty()) return new Result(after, 0);
        var original = index(before);
        var current = index(after);
        var repairs = new LinkedHashMap<BlockPos, BlockPatch>();
        for (var entry : original.entrySet()) {
            BlockPos p = entry.getKey();
            if (!occupied(entry.getValue()) || context.protectedClearance().contains(p)
                    || !(context.generatedSurfaces().contains(p) || exterior(p, context))) continue;
            if (!occupied(current.get(p))) repairs.put(p, entry.getValue());
        }
        if (repairs.isEmpty()) return new Result(after, 0);
        var result = new ArrayList<BlockPatch>();
        for (var patch : after) {
            if (patch != null && !repairs.containsKey(position(patch))) result.add(patch);
        }
        result.addAll(repairs.values());
        return new Result(result, repairs.size());
    }

    private static Map<BlockPos, BlockPatch> index(List<BlockPatch> patches) {
        var result = new LinkedHashMap<BlockPos, BlockPatch>();
        for (var patch : patches) if (patch != null) result.put(position(patch), patch);
        return result;
    }
    private static BlockPos position(BlockPatch p) { return new BlockPos(p.dx(), p.dy(), p.dz()); }
    private static boolean contains(BlockPos p, PostProcessContext context) {
        return context.buildingVolumes().stream().anyMatch(v -> v.contains(p.getX(), p.getY(), p.getZ()));
    }
    private static boolean exterior(BlockPos p, PostProcessContext context) {
        if (!contains(p, context)) return false;
        // Union at this exact height: shared annex walls are not exterior surfaces.
        return !contains(p.east(), context) || !contains(p.west(), context)
                || !contains(p.north(), context) || !contains(p.south(), context)
                || !contains(p.up(), context);
    }
}
