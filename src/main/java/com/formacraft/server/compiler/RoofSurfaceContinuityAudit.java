package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.util.ComponentFootprintMask;
import com.formacraft.common.generation.component.util.GeneratedSurfaceCapture;
import com.formacraft.common.generation.component.util.RoofTypeCatalog;
import com.formacraft.common.generation.component.util.RoofTypeKind;
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
        // Use RoofTypeCatalog so roof_type / roofType aliases share one audit path.
        String type = RoofTypeCatalog.extractExplicitType(c, c.params())
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .orElse("");
        var kind = RoofTypeCatalog.parseExplicit(type).orElse(null);
        if (kind == null || kind == RoofTypeKind.FLAT || !isContinuityAudited(kind)) return Optional.empty();
        // Double-eave mid shelves intentionally break a single continuous slope column graph.
        if (isDoubleEave(c.params()) && !kind.isProfiledGable() && kind != RoofTypeKind.BELLCAST_HIP) {
            return Optional.empty();
        }
        int width = c.dimensions().width(), depth = c.dimensions().depth();
        boolean coverage = requiresFullColumnCoverage(kind);
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
                // Adjacent columns are continuous when any occupied Y differs by at most 1
                // (shared edge or one-block stair step). Comparing only column maxima falsely
                // rejects multi-layer slopes where lower rows still join the neighbor.
                if (!slopeRowsTouch(ys, other))
                    return gap(c.params().get("component_id"),"disconnected_slope_rows",pos);
            }
        }
        return Optional.empty();
    }

    /** True when any occupied Y in {@code a} is within 1 of any Y in {@code b}. */
    private static boolean slopeRowsTouch(Set<Integer> a, Set<Integer> b) {
        for (int ya : a) {
            for (int yb : b) {
                if (Math.abs(ya - yb) <= 1) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Types that enter this final surface continuity pass. */
    private static boolean isContinuityAudited(RoofTypeKind kind) {
        return kind == RoofTypeKind.GABLE
                || kind == RoofTypeKind.DOUBLE_GABLE
                || kind == RoofTypeKind.SHED
                || kind == RoofTypeKind.SAWTOOTH
                || kind.isProfiledGable()
                || kind == RoofTypeKind.BELLCAST_HIP
                || kind == RoofTypeKind.HIP
                || kind == RoofTypeKind.MANSARD
                || kind == RoofTypeKind.GAMBREL
                || kind.isDome()
                || kind == RoofTypeKind.ONION_DOME;
    }

    /**
     * Full footprint column occupancy. Domes stay continuity-only so intentional oculus /
     * drum openings are not forced closed by this pass.
     */
    private static boolean requiresFullColumnCoverage(RoofTypeKind kind) {
        return kind == RoofTypeKind.SHED
                || kind == RoofTypeKind.SAWTOOTH
                || kind.isProfiledGable()
                || kind == RoofTypeKind.BELLCAST_HIP
                || kind == RoofTypeKind.HIP
                || kind == RoofTypeKind.MANSARD
                || kind == RoofTypeKind.GAMBREL;
    }

    private static boolean isDoubleEave(Map<String, Object> params) {
        if (params == null) return false;
        return Boolean.TRUE.equals(params.get("double_eave"))
                || Boolean.TRUE.equals(params.get("doubleEave"));
    }

    private static Optional<CapabilityGap> gap(Object id,String reason,BlockPos pos) {
        return Optional.of(new CapabilityGap("E_ROOF_SURFACE_DISCONNECTED",
                "屋顶坡面不连续：id="+id+" reason="+reason+" column="+pos.toShortString(),
                "components[]",List.of("补齐坡面竖向接缝，保持屋顶列之间实体连接")));
    }
}
