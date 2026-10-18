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
        BRIDGE,
        WATER
    }

    public static PlateRole resolveRole(Map<String, Object> params) {
        var explicit = PlateRoleCatalog.extractExplicit(params);
        return explicit.map(s -> PlateRoleCatalog.parseSupported(s)
                .orElseThrow(() -> new IllegalArgumentException(
                        "E_PLATE_ROLE_UNSUPPORTED: Unsupported plate_role: " + s))).orElse(PlateRole.INTERIOR);
    }

    public static boolean skipsHostFootprintClip(PlateRole role) {
        return role == PlateRole.TERRACE || role == PlateRole.BRIDGE || role == PlateRole.WATER;
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

    /** Keep the authored deck when a legacy height is impossible as a storey index. */
    public static int connectionY(Component plate, Component host, int floorHeight) {
        int originY = ComponentFootprintUtil.resolveMinCornerOrigin(host).y();
        int level = connectionLevel(plate.params()).orElse(0);
        int storeyY = originY + level * floorHeight;
        if (plate.relativePosition() != null && !endpointLevelMatches(storeyY, host)
                && endpointLevelMatches(plate.relativePosition().y(), host)) {
            return plate.relativePosition().y();
        }
        return storeyY;
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
            case BRIDGE, WATER -> null;
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
     * Plate deck Y must sit within the mass vertical envelope
     * {@code [minY, minY + height]} (inclusive), not only share an XZ projection.
     */
    public static boolean endpointLevelMatches(int plateY, Component mass) {
        if (mass == null || mass.dimensions() == null) {
            return false;
        }
        Vec3i origin = ComponentFootprintUtil.resolveMinCornerOrigin(mass);
        if (origin == null) {
            return false;
        }
        int minY = origin.y();
        int maxY = origin.y() + Math.max(1, mass.dimensions().height());
        return plateY >= minY && plateY <= maxY;
    }

    public static boolean touchesEndpoint3d(
            ComponentFootprintUtil.Bounds plate,
            ComponentFootprintUtil.Bounds mass,
            int plateY,
            Component massComponent
    ) {
        return touchesEndpoint(plate, mass) && endpointLevelMatches(plateY, massComponent);
    }

    /**
     * Carve a portal where the plate meets {@code mass}: up to 3 wide, clear height plateY+1/+2,
     * depth = mass {@code wall_thickness} inward along the contact normal.
     */
    public static List<BlockPatch> bridgePortalRemovals(Component plate, Component mass) {
        List<BlockPatch> out = new ArrayList<>();
        ComponentFootprintUtil.Bounds pb = ComponentFootprintUtil.bounds(plate);
        ComponentFootprintUtil.Bounds mb = ComponentFootprintUtil.bounds(mass);
        if (pb == null || mb == null || plate.relativePosition() == null) {
            return out;
        }
        int plateY = plate.relativePosition().y();
        int clearY0 = plateY + 1;
        int clearY1 = plateY + 2;
        int depth = Math.max(1, ComponentParamParsers.intParam(
                mass.params(), 1, "wall_thickness", "wallThickness"));

        boolean plateWestOfMass = pb.maxX() <= mb.minX() + 1 || (pb.maxX() <= mb.minX() + 2 && pb.minX() < mb.minX());
        boolean plateEastOfMass = pb.minX() >= mb.maxX() - 1 || (pb.minX() >= mb.maxX() - 2 && pb.maxX() > mb.maxX());
        boolean plateNorthOfMass = pb.maxZ() <= mb.minZ() + 1 || (pb.maxZ() <= mb.minZ() + 2 && pb.minZ() < mb.minZ());
        boolean plateSouthOfMass = pb.minZ() >= mb.maxZ() - 1 || (pb.minZ() >= mb.maxZ() - 2 && pb.maxZ() > mb.maxZ());
        // A deck may extend several cells into its receiving floor. Follow the
        // face it actually crosses instead of falling back to an unrelated X wall.
        if (!plateWestOfMass && !plateEastOfMass && !plateNorthOfMass && !plateSouthOfMass) {
            if (pb.depth() > pb.width()) {
                plateNorthOfMass = pb.minZ() < mb.minZ() && pb.maxZ() > mb.minZ();
                plateSouthOfMass = pb.maxZ() > mb.maxZ() && pb.minZ() < mb.maxZ();
            } else if (pb.width() > pb.depth()) {
                plateWestOfMass = pb.minX() < mb.minX() && pb.maxX() > mb.minX();
                plateEastOfMass = pb.maxX() > mb.maxX() && pb.minX() < mb.maxX();
            }
        }

        if (plateWestOfMass || (!plateEastOfMass && !plateNorthOfMass && !plateSouthOfMass
                && pb.maxX() > mb.minX() && pb.minX() < mb.minX() + 2)) {
            int mid = (Math.max(pb.minZ(), mb.minZ()) + Math.min(pb.maxZ(), mb.maxZ())) / 2;
            int zStart = Math.max(mb.minZ(), mid - 1);
            int zEnd = Math.min(mb.maxZ() - 1, zStart + 2);
            carveDepth(out, mb.minX(), 1, 0, depth, mb, mass, zStart, zEnd, true, clearY0, clearY1);
            return out;
        }
        if (plateEastOfMass) {
            int mid = (Math.max(pb.minZ(), mb.minZ()) + Math.min(pb.maxZ(), mb.maxZ())) / 2;
            int zStart = Math.max(mb.minZ(), mid - 1);
            int zEnd = Math.min(mb.maxZ() - 1, zStart + 2);
            carveDepth(out, mb.maxX() - 1, -1, 0, depth, mb, mass, zStart, zEnd, true, clearY0, clearY1);
            return out;
        }
        if (plateNorthOfMass) {
            int mid = (Math.max(pb.minX(), mb.minX()) + Math.min(pb.maxX(), mb.maxX())) / 2;
            int xStart = Math.max(mb.minX(), mid - 1);
            int xEnd = Math.min(mb.maxX() - 1, xStart + 2);
            carveDepth(out, mb.minZ(), 0, 1, depth, mb, mass, xStart, xEnd, false, clearY0, clearY1);
            return out;
        }
        if (plateSouthOfMass) {
            int mid = (Math.max(pb.minX(), mb.minX()) + Math.min(pb.maxX(), mb.maxX())) / 2;
            int xStart = Math.max(mb.minX(), mid - 1);
            int xEnd = Math.min(mb.maxX() - 1, xStart + 2);
            carveDepth(out, mb.maxZ() - 1, 0, -1, depth, mb, mass, xStart, xEnd, false, clearY0, clearY1);
            return out;
        }
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
            carveDepth(out, faceX, inward, 0, depth, mb, mass, zStart, zEnd, true, clearY0, clearY1);
        }
        return out;
    }

    private static void carveDepth(
            List<BlockPatch> out,
            int faceCoord,
            int dirX,
            int dirZ,
            int depth,
            ComponentFootprintUtil.Bounds mb,
            Component mass,
            int spanStart,
            int spanEnd,
            boolean spanIsZ,
            int clearY0,
            int clearY1
    ) {
        var geometry = BuildingGeometryContext.getOrResolve(mass);
        for (int span = spanStart; span <= spanEnd; span++) {
            int boundary = 0;
            // Curved hosts do not have a wall at every cell of their AABB face.
            // Find the actual footprint edge on this portal row before carving inward.
            if (geometry != null && geometry.geometry().footprint() != null) {
                var footprint = geometry.geometry().footprint();
                var layers = geometry.geometry().layers();
                int localY = clearY0 - ComponentFootprintUtil.resolveMinCornerOrigin(mass).y();
                while (boundary < Math.max(mb.width(), mb.depth())) {
                    int x = spanIsZ ? faceCoord + dirX * boundary : span;
                    int z = spanIsZ ? span : faceCoord + dirZ * boundary;
                    int lx = x - mb.minX(), lz = z - mb.minZ();
                    boolean atLayer = "TOWER".equalsIgnoreCase(mass.componentType()) || layers.isEmpty()
                            || localY < 0 || localY >= layers.size()
                            || ResolvedFacadeLayers.contains(layers.get(localY), lx, lz);
                    if (footprint.contains(lx, lz) && atLayer) break;
                    boundary++;
                }
            }
            for (int d = 0; d < depth; d++) {
                int x = spanIsZ ? faceCoord + dirX * (boundary + d) : span;
                int z = spanIsZ ? span : faceCoord + dirZ * (boundary + d);
                if (x < mb.minX() || x >= mb.maxX() || z < mb.minZ() || z >= mb.maxZ()) {
                    continue;
                }
                out.add(new BlockPatch(BlockPatch.REMOVE, x, clearY0, z, "minecraft:air"));
                out.add(new BlockPatch(BlockPatch.REMOVE, x, clearY1, z, "minecraft:air"));
            }
        }
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
