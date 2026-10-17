package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.util.ComponentFootprintMask;
import com.formacraft.common.generation.component.util.GeneratedSurfaceCapture;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Checks actual occupied slope cells, including irregular footprints and north-light roofs. */
final class RoofSurfaceContinuityAudit {
    static Optional<CapabilityGap> inspect(SemanticComponent semantic, List<BlockPatch> patches,
                                           BlockPos offset, Set<BlockPos> occluded) {
        var c = semantic.source();
        if (c == null || c.params() == null || c.dimensions() == null || c.relativePosition() == null) return Optional.empty();
        String type = String.valueOf(c.params().getOrDefault("roof_type", "")).toLowerCase(Locale.ROOT);
        if (!Set.of("gable", "double_gable", "shed", "sawtooth").contains(type)
                || Boolean.TRUE.equals(c.params().get("double_eave"))) return Optional.empty();
        int width = c.dimensions().width(), depth = c.dimensions().depth();
        boolean coverage = "shed".equals(type) || "sawtooth".equals(type);
        var mask = ComponentFootprintMask.from(semantic,c.params(),width,depth);
        int base = c.relativePosition().y()+offset.getY();
        var blocks = new HashMap<BlockPos,BlockPatch>();
        for (var p : patches) if (p != null) blocks.put(new BlockPos(p.dx(),p.dy(),p.dz()),p);
        var columns = new HashMap<BlockPos,Set<Integer>>();
        for (var e : blocks.entrySet()) if (e.getKey().getY() >= base && GeneratedSurfaceCapture.occupied(e.getValue())) {
            var pos = e.getKey();
            columns.computeIfAbsent(new BlockPos(pos.getX(),0,pos.getZ()), k -> new HashSet<>()).add(pos.getY());
        }
        for (int x=0;x<width;x++) for (int z=0;z<depth;z++) {
            if (!mask.contains(x,z)) continue;
            var pos = new BlockPos(c.relativePosition().x()+offset.getX()+x,0,c.relativePosition().z()+offset.getZ()+z);
            if (occluded.contains(pos)) continue;
            var ys = columns.get(pos);
            if (ys == null || ys.isEmpty()) {
                if (coverage) return gap(c.params().get("component_id"),"missing_column",pos);
                continue; // Gable core coverage and joined sections have their own shape audit.
            }
            for (int[] delta : new int[][]{{1,0},{0,1}}) {
                if (!mask.contains(x+delta[0],z+delta[1])) continue;
                var next = pos.add(delta[0],0,delta[1]);
                if (occluded.contains(next)) continue;
                var other = columns.get(next);
                if (other == null || other.isEmpty()) {
                    if (coverage) return gap(c.params().get("component_id"),"missing_column",next);
                    continue;
                }
                // A one-block staircase silhouette is closed at its shared edge.
                // Larger height jumps need an actual vertical joining surface.
                if (Collections.disjoint(ys,other)
                        && Math.abs(Collections.max(ys)-Collections.max(other))>1)
                    return gap(c.params().get("component_id"),"disconnected_slope_rows",pos);
            }
        }
        return Optional.empty();
    }

    private static Optional<CapabilityGap> gap(Object id,String reason,BlockPos pos) {
        return Optional.of(new CapabilityGap("E_ROOF_SURFACE_DISCONNECTED",
                "屋顶坡面不连续：id="+id+" reason="+reason+" column="+pos.toShortString(),
                "components[]",List.of("补齐坡面竖向接缝，保持屋顶列之间实体连接")));
    }
}
