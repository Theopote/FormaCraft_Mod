package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.Locale;
import java.util.Set;

/**
 * Unified spatial-domain probe over {@link BuildingGeometryContext} / CSG / footprint
 * (Phase 02.3.4 Task B). Emit, junction, void and postprocess share this — do not invent
 * independent rectangle domains when a shaped/CSG domain exists.
 * <p>
 * Classification: GEOMETRY / HARD_CONSTRAINT — not design authority.
 */
public final class SpatialDomainQuery {
    private SpatialDomainQuery() {}

    public enum CellRole {
        UNKNOWN,
        SOLID,
        WALL_SHELL,
        FLOOR,
        INTERIOR_AIR,
        CARVE_VOID,
        PROTECTED_VOID,
        RESERVED,
        EXTERIOR
    }

    /**
     * Prefer cached domain; else CSG compose; else footprint extrude. Never silent AABB solid.
     */
    public static MassBooleanComposition.CsgResult resolveDomain(Component mass) {
        if (mass == null) return null;
        BuildingGeometryContext.Entry entry = BuildingGeometryContext.getOrResolve(mass);
        if (entry != null && entry.domain() != null) {
            return entry.domain();
        }
        if (MassBooleanComposition.needsCsg(mass)) {
            try {
                return MassBooleanComposition.composeFromComponent(mass);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
        int w = mass.dimensions() == null ? 0 : Math.max(1, mass.dimensions().width());
        int d = mass.dimensions() == null ? 0 : Math.max(1, mass.dimensions().depth());
        int h = mass.dimensions() == null ? 1 : Math.max(1, mass.dimensions().height());
        if (w <= 0 || d <= 0) return null;
        ResolvedFootprint fp;
        try {
            fp = ResolvedFootprint.resolve(mass, w, d);
        } catch (IllegalArgumentException ex) {
            // Unsupported plan_type / shape — emit path owns capability_gap; probes stay null.
            return null;
        }
        if (fp == null) return null;
        String id = mass.params() == null || mass.params().get("component_id") == null
                ? "" : String.valueOf(mass.params().get("component_id"));
        try {
            return MassBooleanComposition.domainFromFootprint(fp, h, id);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public static Vec3i originPlan(Component mass, Vec3i slotAnchor) {
        Vec3i slot = slotAnchor == null ? new Vec3i(0, 0, 0) : slotAnchor;
        Vec3i min = ComponentFootprintUtil.resolveMinCornerOrigin(mass);
        if (min == null) {
            Vec3i rel = mass == null || mass.relativePosition() == null
                    ? new Vec3i(0, 0, 0) : mass.relativePosition();
            return new Vec3i(slot.x() + rel.x(), slot.y() + rel.y(), slot.z() + rel.z());
        }
        return new Vec3i(slot.x() + min.x(), slot.y() + min.y(), slot.z() + min.z());
    }

    /** Classify a plan-space cell relative to a mass domain + protected void index. */
    public static CellRole classify(
            Component mass,
            Vec3i slotAnchor,
            int planX, int planY, int planZ,
            Set<Long> protectedPlanKeys
    ) {
        if (ProtectedVoidIndex.isProtected(planX, planY, planZ)
                || (protectedPlanKeys != null
                && MassBooleanComposition.inPackRange3(planX, planY, planZ)
                && protectedPlanKeys.contains(MassBooleanComposition.pack3(planX, planY, planZ)))) {
            return CellRole.PROTECTED_VOID;
        }
        MassBooleanComposition.CsgResult domain = resolveDomain(mass);
        if (domain == null) return CellRole.UNKNOWN;
        Vec3i o = originPlan(mass, slotAnchor);
        int lx = planX - o.x();
        int ly = planY - o.y();
        int lz = planZ - o.z();
        if (!MassBooleanComposition.inPackRange3(lx, ly, lz)) return CellRole.EXTERIOR;
        long key = MassBooleanComposition.pack3(lx, ly, lz);
        for (var v : domain.carveVoids()) {
            if (v.x() == lx && v.y() == ly && v.z() == lz) return CellRole.CARVE_VOID;
        }
        if (!domain.contains(lx, ly, lz)) return CellRole.EXTERIOR;
        // Shell heuristic: face-adjacent to exterior or carve.
        if (isShell(domain, lx, ly, lz)) return CellRole.WALL_SHELL;
        int h = mass.dimensions() == null ? 1 : Math.max(1, mass.dimensions().height());
        ResolvedFloorLayout floors = ResolvedFloorLayout.from(mass, h);
        if (floors != null) {
            for (int fy : floors.floorYs()) {
                if (ly == fy) return CellRole.FLOOR;
            }
        }
        return CellRole.INTERIOR_AIR;
    }

    private static boolean isShell(MassBooleanComposition.CsgResult domain, int x, int y, int z) {
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : dirs) {
            int nx = x + d[0], ny = y + d[1], nz = z + d[2];
            if (!domain.contains(nx, ny, nz)) return true;
        }
        return false;
    }

    public static CellRole parseRole(String raw) {
        if (raw == null || raw.isBlank()) return CellRole.UNKNOWN;
        try {
            return CellRole.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return CellRole.UNKNOWN;
        }
    }
}
