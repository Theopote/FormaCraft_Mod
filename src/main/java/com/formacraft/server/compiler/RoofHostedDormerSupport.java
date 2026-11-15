package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.GeneratedSurfaceCapture;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Per-emission roof occupancy, used only to extend explicitly hosted dormer walls down to their roof. */
final class RoofHostedDormerSupport {
    private record Key(String slot, String id) {}
    private final Map<Key, Map<BlockPos, Integer>> roofs = new HashMap<>();

    void record(Component roof, List<BlockPatch> patches) {
        if (!ComponentPlanTypes.isRoofType(ComponentPlanTypes.normalizeType(roof.componentType()))) return;
        String id = ComponentPlanTypes.getParamString(roof.params(), "component_id");
        if (id == null) return;
        var finalAt = new HashMap<BlockPos, BlockPatch>();
        for (var patch : patches) if (patch != null)
            finalAt.put(new BlockPos(patch.dx(), patch.dy(), patch.dz()), patch);
        var tops = new HashMap<BlockPos, Integer>();
        for (var patch : finalAt.values()) if (GeneratedSurfaceCapture.occupied(patch))
            tops.merge(new BlockPos(patch.dx(), 0, patch.dz()), patch.dy(), Math::max);
        roofs.put(new Key(ComponentPlanTypes.slotKey(roof), id), tops);
    }

    Component extendToRoof(Component dormer, Vec3i slotOffset) {
        if (!ComponentSatelliteAligner.isDormer(dormer)) return dormer;
        String host = ComponentPlanTypes.getParamString(dormer.params(), "host_id", "hostId");
        var roof = roofs.get(new Key(ComponentPlanTypes.slotKey(dormer), host));
        Vec3i origin = ComponentPlanCompiler.resolveMassOrigin(dormer);
        Dimensions dims = dormer.dimensions();
        if (roof == null || origin == null || dims == null) return dormer;
        int ox = slotOffset == null ? 0 : slotOffset.x();
        int oy = slotOffset == null ? 0 : slotOffset.y();
        int oz = slotOffset == null ? 0 : slotOffset.z();
        int lowestSurface = Integer.MAX_VALUE;
        for (int x = 0; x < dims.width(); x++) for (int z = 0; z < dims.depth(); z++) {
            Integer y = roof.get(new BlockPos(origin.x() + ox + x, 0, origin.z() + oz + z));
            // An incomplete footprint cannot justify adding a wall hanging over a roof hole.
            if (y == null) return dormer;
            lowestSurface = Math.min(lowestSurface, y);
        }
        int bottom = lowestSurface + 1 - oy;
        if (bottom >= origin.y()) return dormer;
        int top = origin.y() + dims.height();
        var params = new HashMap<String, Object>();
        if (dormer.params() != null) params.putAll(dormer.params());
        params.put("anchor_mode", "min_corner");
        return new Component(dormer.componentType(), dormer.slotId(), new Vec3i(origin.x(), bottom, origin.z()),
                new Dimensions(dims.width(), dims.depth(), top - bottom), dormer.features(), params);
    }
}
