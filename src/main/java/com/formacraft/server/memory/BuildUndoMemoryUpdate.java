package com.formacraft.server.memory;

import net.minecraft.util.Identifier;
import java.util.*;

/** Absolute progress for one build transaction, safe to retry without accumulating counts. */
public record BuildUndoMemoryUpdate(UUID transactionId, String memoryUuid, Identifier dimension,
                                    int restoredPositions, int totalPositions, boolean complete) {
    public BuildUndoMemoryUpdate {
        Objects.requireNonNull(transactionId); Objects.requireNonNull(memoryUuid); Objects.requireNonNull(dimension);
        if (totalPositions <= 0 || restoredPositions < 0 || restoredPositions > totalPositions
            || complete != (restoredPositions == totalPositions)) throw new IllegalArgumentException("Invalid undo progress");
    }
    public boolean targets(ProjectMemory memory) {
        return memory != null && memoryUuid.equals(memory.getUuid()) && SpatialIndex.inDimension(memory, dimension);
    }
    public Map<String, Object> metadata(ProjectMemory memory) {
        var result = new HashMap<String, Object>(memory.getMetadata() == null ? Map.of() : memory.getMetadata());
        result.put("build_undo_transaction", transactionId.toString());
        result.put("build_undo_state", complete ? "restored" : restoredPositions > 0 ? "partial" : "pending");
        result.put("build_undo_restored_positions", restoredPositions);
        result.put("build_undo_total_positions", totalPositions);
        return result;
    }
}
