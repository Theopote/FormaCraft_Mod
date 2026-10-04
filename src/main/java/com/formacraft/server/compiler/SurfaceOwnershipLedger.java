package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.GeneratedSurfaceCapture;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Per-compilation surface provenance; checks only destructive writes with defined permissions. */
final class SurfaceOwnershipLedger {
    record Surface(String owner, String source, GeneratedSurfaceCapture.Role role) {}
    record Conflict(BlockPos position, Surface surface, String writer, String reason) {}
    private final Map<BlockPos, Surface> surfaces = new HashMap<>();
    private final Map<BlockPos, BlockPatch> finalPatches = new HashMap<>();

    Optional<Conflict> check(Component component, List<BlockPatch> patches) {
        String type = component.componentType().toUpperCase(Locale.ROOT);
        boolean decor = type.equals("DECOR_DETAIL");
        boolean opening = Set.of("ENTRANCE", "FACADE_WINDOWS", "FACADE").contains(type);
        if (!decor && !opening) return Optional.empty();
        var writes = index(patches);
        for (var entry : writes.entrySet()) {
            Surface surface = surfaces.get(entry.getKey());
            if (surface == null || GeneratedSurfaceCapture.occupied(entry.getValue())
                    || !GeneratedSurfaceCapture.occupied(finalPatches.get(entry.getKey()))) continue;
            String host = param(component, "host_id");
            String reason = decor ? "decoration cannot delete an existing surface"
                    : surface.role() == GeneratedSurfaceCapture.Role.ROOF ? "opening cannot delete roof"
                    : host != null && !host.equals(surface.owner()) ? "opening host_id does not own surface" : null;
            if (reason != null) return Optional.of(new Conflict(entry.getKey(), surface, identity(component), reason));
        }
        return Optional.empty();
    }

    void accept(Component component, List<BlockPatch> patches, List<GeneratedSurfaceCapture.Cell> cells, BlockPos offset) {
        finalPatches.putAll(index(patches));
        String owner = param(component, "host_id");
        if (owner == null) owner = identity(component);
        for (var cell : cells) {
            BlockPos position = cell.shift(offset).position();
            if (cell.requiresBlock()) surfaces.put(position, new Surface(owner, identity(component), cell.role()));
        }
    }
    private static Map<BlockPos, BlockPatch> index(List<BlockPatch> patches) {
        var result = new LinkedHashMap<BlockPos, BlockPatch>();
        for (var patch : patches) if (patch != null)
            result.put(new BlockPos(patch.dx(), patch.dy(), patch.dz()), patch);
        return result;
    }
    private static String param(Component c, String key) {
        Object value = c.params() == null ? null : c.params().get(key);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }
    private static String identity(Component c) {
        String id = param(c, "component_id");
        return id != null ? id : c.componentType() + "@" + c.slotId() + ":" + c.relativePosition();
    }
}
