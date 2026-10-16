package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Vec3i;

import java.util.Map;

/**
 * Occupancy rules for {@code extrude_mode=plate} slabs relative to a host mass.
 * Interior plates must respect {@link ResolvedFootprint}; terrace/bridge may exceed it.
 * Role tokens are owned by {@link PlateRoleCatalog} — unknown explicit roles must not become INTERIOR.
 */
public final class HostedPlateOccupancy {
    private HostedPlateOccupancy() {}

    public enum PlateRole {
        INTERIOR,
        TERRACE,
        BRIDGE
    }

    public static PlateRole resolveRole(Map<String, Object> params) {
        var explicit = PlateRoleCatalog.extractExplicit(params);
        if (explicit.isEmpty()) {
            return PlateRole.INTERIOR;
        }
        return PlateRoleCatalog.parseSupported(explicit.get())
                .orElseThrow(() -> new IllegalArgumentException(
                        "E_PLATE_ROLE_UNSUPPORTED: Unsupported plate_role: " + explicit.get()));
    }

    public static boolean skipsHostFootprintClip(PlateRole role) {
        return role == PlateRole.TERRACE || role == PlateRole.BRIDGE;
    }

    /**
     * Whether prepare may AABB-inset the plate by host wall thickness.
     * Terrace/bridge keep authored footprint; interior still clears exterior walls.
     */
    public static boolean appliesHostWallInset(PlateRole role) {
        return role == PlateRole.INTERIOR;
    }

    /**
     * Role-specific vertical envelope relative to host min-corner origin and height.
     * Bridge does not use a single-host height envelope (endpoints validated geometrically).
     *
     * @return empty when this role does not enforce a host-height envelope
     */
    public static OptionalIntRange levelEnvelope(PlateRole role, int hostOriginY, int hostHeight) {
        int minY = hostOriginY;
        return switch (role == null ? PlateRole.INTERIOR : role) {
            case INTERIOR -> new OptionalIntRange(minY, hostOriginY + hostHeight);
            case TERRACE -> new OptionalIntRange(minY, hostOriginY + hostHeight + 1);
            case BRIDGE -> null;
        };
    }

    /** Inclusive Y range when role enforces an envelope; {@code null} means no host-height gate. */
    public record OptionalIntRange(int minInclusive, int maxInclusive) {
        public boolean contains(int y) {
            return y >= minInclusive && y <= maxInclusive;
        }
    }

    /**
     * Whether an interior hosted plate may place a block at world (wx,wy,wz).
     * Local coords are relative to the host min-corner origin.
     */
    public static boolean allowsHostedInteriorCell(
            ResolvedBuildingGeometry hostGeom,
            Vec3i hostOrigin,
            int worldX,
            int worldY,
            int worldZ
    ) {
        if (hostGeom == null || hostOrigin == null || hostGeom.footprint() == null) {
            return true;
        }
        int lx = worldX - hostOrigin.x();
        int lz = worldZ - hostOrigin.z();
        int ly = worldY - hostOrigin.y();
        if (!hostGeom.footprint().contains(lx, lz)) {
            return false;
        }
        var layers = hostGeom.layers();
        if (layers == null || layers.isEmpty()) {
            return true;
        }
        int layerY = ly;
        if (layerY < 0) {
            layerY = 0;
        } else if (layerY >= layers.size()) {
            layerY = layers.size() - 1;
        }
        return ResolvedFacadeLayers.contains(layers.get(layerY), lx, lz);
    }
}
