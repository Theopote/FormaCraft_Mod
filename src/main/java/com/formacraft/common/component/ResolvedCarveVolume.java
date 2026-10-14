package com.formacraft.common.component;

import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Validated carve cells in the same coordinate system as PLACE patches (relative to slot).
 */
public record ResolvedCarveVolume(
        Status status,
        Set<BlockPos> cells,
        String hostComponentId,
        String socketId,
        String message
) {
    public enum Status { SUCCESS, FAILED }

    public ResolvedCarveVolume {
        cells = cells == null ? Set.of() : Set.copyOf(cells);
        hostComponentId = hostComponentId == null ? "" : hostComponentId;
        socketId = socketId == null ? "" : socketId;
        message = message == null ? "" : message;
        if (status == null) status = Status.FAILED;
    }

    public boolean ok() {
        return status == Status.SUCCESS;
    }

    public static ResolvedCarveVolume success(String hostId, String socketId, Set<BlockPos> cells) {
        return new ResolvedCarveVolume(Status.SUCCESS, cells, hostId, socketId, "");
    }

    public static ResolvedCarveVolume failure(String hostId, String socketId, String message) {
        return new ResolvedCarveVolume(Status.FAILED, Set.of(), hostId, socketId, message);
    }

    /** No carve requested — treated as a successful empty volume. */
    public static ResolvedCarveVolume none(String hostId) {
        return new ResolvedCarveVolume(Status.SUCCESS, Set.of(), hostId, "", "carve_not_requested");
    }

    public List<BlockPatch> toRemovePatches() {
        List<BlockPatch> out = new ArrayList<>(cells.size());
        for (BlockPos p : cells) {
            out.add(new BlockPatch(BlockPatch.REMOVE, p.getX(), p.getY(), p.getZ(), "minecraft:air"));
        }
        return out;
    }

    public static Set<BlockPos> cellsFromRemoves(List<BlockPatch> removes) {
        Set<BlockPos> cells = new LinkedHashSet<>();
        if (removes == null) return cells;
        for (BlockPatch p : removes) {
            if (p == null) continue;
            cells.add(new BlockPos(p.dx(), p.dy(), p.dz()));
        }
        return cells;
    }
}
