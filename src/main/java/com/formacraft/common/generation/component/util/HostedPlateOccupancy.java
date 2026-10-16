package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Vec3i;

import java.util.Locale;
import java.util.Map;

/**
 * Occupancy rules for {@code extrude_mode=plate} slabs relative to a host mass.
 * Interior plates must respect {@link ResolvedFootprint}; terrace/bridge may exceed it.
 */
public final class HostedPlateOccupancy {
    private HostedPlateOccupancy() {}

    public enum PlateRole {
        INTERIOR,
        TERRACE,
        BRIDGE
    }

    public static PlateRole resolveRole(Map<String, Object> params) {
        if (params == null) {
            return PlateRole.INTERIOR;
        }
        Object raw = params.get("plate_role");
        if (raw == null) {
            raw = params.get("plateRole");
        }
        if (raw == null) {
            return PlateRole.INTERIOR;
        }
        String v = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "terrace", "balcony", "deck", "cantilever" -> PlateRole.TERRACE;
            case "bridge", "link", "connector" -> PlateRole.BRIDGE;
            default -> PlateRole.INTERIOR;
        };
    }

    public static boolean skipsHostFootprintClip(PlateRole role) {
        return role == PlateRole.TERRACE || role == PlateRole.BRIDGE;
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
