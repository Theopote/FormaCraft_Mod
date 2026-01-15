package com.formacraft.client.tools;

import com.formacraft.common.geom.Geom2D;
import com.formacraft.common.layout.LayoutConstraints;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * ToolLayoutConstraints（工具布局约束）
 * <p>
 * 核心功能：把 Snapshot 变成 LayoutConstraints（真正裁切/禁区过滤）
 * <p>
 * 功能：
 * - 选区 AABB 约束
 * - 轮廓多边形约束
 * - 禁区多边形约束
 * - 语义标注自动 tag
 */
public class ToolLayoutConstraints implements LayoutConstraints {

    private final ToolSnapshot snap;

    public ToolLayoutConstraints(ToolSnapshot snap) {
        this.snap = snap;
    }

    @Override
    public boolean allowAnchor(BlockPos anchor) {
        if (snap == null) {
            return true;
        }

        int x = anchor.getX();
        int z = anchor.getZ();

        // 1) selection AABB（如果存在选区，则必须在选区内）
        if (snap.hasSelection && snap.selMin != null && snap.selMax != null) {
            int minX = Math.min(snap.selMin.getX(), snap.selMax.getX());
            int maxX = Math.max(snap.selMin.getX(), snap.selMax.getX());
            int minZ = Math.min(snap.selMin.getZ(), snap.selMax.getZ());
            int maxZ = Math.max(snap.selMin.getZ(), snap.selMax.getZ());
            if (x < minX || x > maxX || z < minZ || z > maxZ) {
                return false;
            }
        }

        // 2) outline polygon（如果存在轮廓，则必须在轮廓内）
        if (snap.hasOutline && snap.outlinePolygon != null && snap.outlinePolygon.size() >= 3) {
            if (!Geom2D.pointInPolygon(x + 0.5, z + 0.5, snap.outlinePolygon)) {
                return false;
            }
        }

        // 3) forbidden zones（如果在任何禁区内，则拒绝）
        if (snap.forbiddenPolygons != null && !snap.forbiddenPolygons.isEmpty()) {
            for (List<Geom2D.Vec2> poly : snap.forbiddenPolygons) {
                if (poly == null || poly.size() < 3) {
                    continue;
                }
                if (Geom2D.pointInPolygon(x + 0.5, z + 0.5, poly)) {
                    return false;
                }
            }
        }

        return true;
    }

    @Override
    public boolean allowFootprint(BlockPos anchor, int footprintW, int footprintD, int clearance) {
        // 用 footprint 采样，让 "占地范围" 也被 outline/selection/forbidden 约束
        return Geom2D.sampleFootprintAllAllowed(anchor, footprintW, footprintD, clearance,
                (x, z) -> allowAnchor(new BlockPos(x, anchor.getY(), z)));
    }

    /**
     * 给站点打 tag：落在哪个语义区就用那个 label
     * 
     * @param anchor 站点锚点
     * @param defaultTag 默认标签
     * @return 解析后的标签
     */
    public String resolveSemanticTag(BlockPos anchor, String defaultTag) {
        if (snap == null || snap.semanticRegions == null) {
            return defaultTag;
        }
        
        int x = anchor.getX();
        int z = anchor.getZ();
        
        for (ToolSnapshot.SemanticRegion r : snap.semanticRegions) {
            if (r == null || r.polygon() == null || r.polygon().size() < 3) {
                continue;
            }
            if (Geom2D.pointInPolygon(x + 0.5, z + 0.5, r.polygon())) {
                return r.label();
            }
        }
        
        return defaultTag;
    }
}

