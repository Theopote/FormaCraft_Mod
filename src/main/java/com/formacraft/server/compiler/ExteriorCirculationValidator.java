package com.formacraft.server.compiler;

import com.formacraft.common.compiler.postprocess.PostProcessContext.BuildingVolume;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.BlockPatchTargetResolver;
import com.formacraft.server.assembly.AssemblyCirculationConstraints.Flight;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Checks circulation writes against the shell already emitted, in plan coordinates. */
final class ExteriorCirculationValidator {
    record Collision(BlockPos position, String role) {}

    static Optional<Collision> findCollision(List<BlockPatch> shell, List<Flight> flights,
                                             List<BuildingVolume> volumes) {
        if (flights.isEmpty() || volumes.isEmpty()) return Optional.empty();
        var relevant = new LinkedHashMap<BlockPos, String>();
        for (var flight : flights) {
            for (var pos : flight.occupied()) relevant.put(pos, "tread/support");
            for (var pos : flight.clearance()) relevant.put(pos, "clearance");
        }
        var finalShell = new HashMap<BlockPos, BlockPatch>();
        for (var patch : shell) {
            if (patch == null) continue;
            var pos = new BlockPos(patch.dx(), patch.dy(), patch.dz());
            if (relevant.containsKey(pos)) finalShell.put(pos, patch);
        }
        for (var entry : relevant.entrySet()) {
            BlockPos p = entry.getKey();
            if (!contains(p, volumes) || (contains(p.east(), volumes) && contains(p.west(), volumes)
                    && contains(p.north(), volumes) && contains(p.south(), volumes))) continue;
            var patch = finalShell.get(p);
            if (patch == null) continue; // Authored doorway or other absent surface.
            var state = BlockPatchTargetResolver.resolve(patch);
            if (state != null && !state.isAir()) return Optional.of(new Collision(p, entry.getValue()));
        }
        return Optional.empty();
    }

    private static boolean contains(BlockPos p, List<BuildingVolume> volumes) {
        return volumes.stream().anyMatch(v -> v.contains(p.getX(), p.getY(), p.getZ()));
    }
}
