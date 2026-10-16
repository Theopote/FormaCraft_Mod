package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    public static Optional<String> targetHostId(Map<String, Object> params) {
        if (params == null) {
            return Optional.empty();
        }
        for (String key : List.of("target_host_id", "targetHostId")) {
            Object raw = params.get(key);
            if (raw == null) {
                continue;
            }
            String v = String.valueOf(raw).trim();
            if (!v.isEmpty()) {
                return Optional.of(v);
            }
        }
        return Optional.empty();
    }

    public static Optional<Integer> connectionLevel(Map<String, Object> params) {
        if (params == null) {
            return Optional.empty();
        }
        Integer level = ComponentParamParsers.intOrNull(params, "connection_level", "connectionLevel");
        if (level == null || level < 0) {
            return Optional.empty();
        }
        return Optional.of(level);
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

    /** Half-open XZ bounds touch or overlap when plate is expanded by one cell. */
    public static boolean touchesEndpoint(
            ComponentFootprintUtil.Bounds plate,
            ComponentFootprintUtil.Bounds mass
    ) {
        if (plate == null || mass == null) {
            return false;
        }
        ComponentFootprintUtil.Bounds expanded = plate.expandHorizontal(1);
        return expanded.minX() < mass.maxX() && expanded.maxX() > mass.minX()
                && expanded.minZ() < mass.maxZ() && expanded.maxZ() > mass.minZ();
    }

    /**
     * Carve a short portal (up to 3 wide, 2 tall, 1 deep) where the plate meets {@code mass}.
     */
    public static List<BlockPatch> bridgePortalRemovals(Component plate, Component mass) {
        List<BlockPatch> out = new ArrayList<>();
        ComponentFootprintUtil.Bounds pb = ComponentFootprintUtil.bounds(plate);
        ComponentFootprintUtil.Bounds mb = ComponentFootprintUtil.bounds(mass);
        if (pb == null || mb == null || plate.relativePosition() == null) {
            return out;
        }
        int plateY = plate.relativePosition().y();
        // Clear height above the deck only — never REMOVE the plate Y deck cells.
        int clearY0 = plateY + 1;
        int clearY1 = plateY + 2;

        // Prefer the shared face: plate primarily west of mass → carve mass west face inward (+X).
        boolean plateWestOfMass = pb.maxX() <= mb.minX() + 1 || (pb.maxX() <= mb.minX() + 2 && pb.minX() < mb.minX());
        boolean plateEastOfMass = pb.minX() >= mb.maxX() - 1 || (pb.minX() >= mb.maxX() - 2 && pb.maxX() > mb.maxX());
        boolean plateNorthOfMass = pb.maxZ() <= mb.minZ() + 1 || (pb.maxZ() <= mb.minZ() + 2 && pb.minZ() < mb.minZ());
        boolean plateSouthOfMass = pb.minZ() >= mb.maxZ() - 1 || (pb.minZ() >= mb.maxZ() - 2 && pb.maxZ() > mb.maxZ());

        if (plateWestOfMass || (!plateEastOfMass && !plateNorthOfMass && !plateSouthOfMass
                && pb.maxX() > mb.minX() && pb.minX() < mb.minX() + 2)) {
            int faceX = mb.minX();
            int mid = (Math.max(pb.minZ(), mb.minZ()) + Math.min(pb.maxZ(), mb.maxZ())) / 2;
            int zStart = Math.max(mb.minZ(), mid - 1);
            int zEnd = Math.min(mb.maxZ() - 1, zStart + 2);
            for (int z = zStart; z <= zEnd; z++) {
                for (int clearY : new int[]{clearY0, clearY1}) {
                    out.add(new BlockPatch(BlockPatch.REMOVE, faceX, clearY, z, "minecraft:air"));
                    if (faceX + 1 < mb.maxX()) {
                        out.add(new BlockPatch(BlockPatch.REMOVE, faceX + 1, clearY, z, "minecraft:air"));
                    }
                }
            }
            return out;
        }
        if (plateEastOfMass) {
            int faceX = mb.maxX() - 1;
            int mid = (Math.max(pb.minZ(), mb.minZ()) + Math.min(pb.maxZ(), mb.maxZ())) / 2;
            int zStart = Math.max(mb.minZ(), mid - 1);
            int zEnd = Math.min(mb.maxZ() - 1, zStart + 2);
            for (int z = zStart; z <= zEnd; z++) {
                for (int clearY : new int[]{clearY0, clearY1}) {
                    out.add(new BlockPatch(BlockPatch.REMOVE, faceX, clearY, z, "minecraft:air"));
                    if (faceX - 1 >= mb.minX()) {
                        out.add(new BlockPatch(BlockPatch.REMOVE, faceX - 1, clearY, z, "minecraft:air"));
                    }
                }
            }
            return out;
        }
        if (plateNorthOfMass) {
            int faceZ = mb.minZ();
            int mid = (Math.max(pb.minX(), mb.minX()) + Math.min(pb.maxX(), mb.maxX())) / 2;
            int xStart = Math.max(mb.minX(), mid - 1);
            int xEnd = Math.min(mb.maxX() - 1, xStart + 2);
            for (int x = xStart; x <= xEnd; x++) {
                for (int clearY : new int[]{clearY0, clearY1}) {
                    out.add(new BlockPatch(BlockPatch.REMOVE, x, clearY, faceZ, "minecraft:air"));
                    if (faceZ + 1 < mb.maxZ()) {
                        out.add(new BlockPatch(BlockPatch.REMOVE, x, clearY, faceZ + 1, "minecraft:air"));
                    }
                }
            }
            return out;
        }
        if (plateSouthOfMass) {
            int faceZ = mb.maxZ() - 1;
            int mid = (Math.max(pb.minX(), mb.minX()) + Math.min(pb.maxX(), mb.maxX())) / 2;
            int xStart = Math.max(mb.minX(), mid - 1);
            int xEnd = Math.min(mb.maxX() - 1, xStart + 2);
            for (int x = xStart; x <= xEnd; x++) {
                for (int clearY : new int[]{clearY0, clearY1}) {
                    out.add(new BlockPatch(BlockPatch.REMOVE, x, clearY, faceZ, "minecraft:air"));
                    if (faceZ - 1 >= mb.minZ()) {
                        out.add(new BlockPatch(BlockPatch.REMOVE, x, clearY, faceZ - 1, "minecraft:air"));
                    }
                }
            }
            return out;
        }
        // Overlap / flush: carve along the overlapped band on the nearer mass face.
        int overlapMinZ = Math.max(pb.minZ(), mb.minZ());
        int overlapMaxZ = Math.min(pb.maxZ(), mb.maxZ());
        int overlapMinX = Math.max(pb.minX(), mb.minX());
        int overlapMaxX = Math.min(pb.maxX(), mb.maxX());
        if (overlapMaxX > overlapMinX && overlapMaxZ > overlapMinZ) {
            int midZ = (overlapMinZ + overlapMaxZ) / 2;
            int zStart = Math.max(mb.minZ(), midZ - 1);
            int zEnd = Math.min(mb.maxZ() - 1, zStart + 2);
            int faceX = pb.minX() < mb.minX() + (mb.width() / 2) ? mb.minX() : mb.maxX() - 1;
            int inward = faceX == mb.minX() ? 1 : -1;
            for (int z = zStart; z <= zEnd; z++) {
                for (int clearY : new int[]{clearY0, clearY1}) {
                    out.add(new BlockPatch(BlockPatch.REMOVE, faceX, clearY, z, "minecraft:air"));
                    out.add(new BlockPatch(BlockPatch.REMOVE, faceX + inward, clearY, z, "minecraft:air"));
                }
            }
        }
        return out;
    }

    public static boolean isBridgePlate(Map<String, Object> params) {
        return resolveRole(params) == PlateRole.BRIDGE;
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
