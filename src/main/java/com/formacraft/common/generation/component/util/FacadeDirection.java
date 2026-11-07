package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.GlobalConstraints;

import java.util.Locale;

/**
 * Facade direction contract for opening delegation.
 * <ul>
 *   <li>{@code worldFace} — AI / params cardinal (Minecraft world)</li>
 *   <li>{@code localFace} — MASS footprint: z=0 SOUTH, z=max NORTH, x=0 EAST, x=max WEST</li>
 *   <li>{@code hostFacing} — Slot.facing; building "front" aligns to local SOUTH for door defaults</li>
 *   <li>{@code resolvedFace} — {@link #worldToLocal(String, GlobalConstraints.Facing)}</li>
 * </ul>
 */
public final class FacadeDirection {
    private FacadeDirection() {}

    public static final String NORTH = "NORTH";
    public static final String SOUTH = "SOUTH";
    public static final String EAST = "EAST";
    public static final String WEST = "WEST";

    /**
     * Map a world cardinal to the MASS local face label used by {@code MassMainGenerator.isFaceSuppressed}.
     * When host faces SOUTH (default), world == local. Other host facings rotate the mapping.
     */
    public static String worldToLocal(String worldFace, GlobalConstraints.Facing hostFacing) {
        String world = normalize(worldFace);
        if (world == null) return null;
        GlobalConstraints.Facing host = hostFacing == null ? GlobalConstraints.Facing.SOUTH : hostFacing;
        int worldIdx = index(world);
        if (worldIdx < 0) return world;
        int hostIdx = index(host.name());
        // local SOUTH is the building front (= hostFacing in world).
        // worldFace relative to host: rot = worldIdx - hostIdx; local = SOUTH + rot
        int localIdx = Math.floorMod(index(SOUTH) + (worldIdx - hostIdx), 4);
        return name(localIdx);
    }

    public static String localToWorld(String localFace, GlobalConstraints.Facing hostFacing) {
        String local = normalize(localFace);
        if (local == null) return null;
        GlobalConstraints.Facing host = hostFacing == null ? GlobalConstraints.Facing.SOUTH : hostFacing;
        int localIdx = index(local);
        if (localIdx < 0) return local;
        int hostIdx = index(host.name());
        int worldIdx = Math.floorMod(hostIdx + (localIdx - index(SOUTH)), 4);
        return name(worldIdx);
    }

    /**
     * Default front-entrance threshold on a host AABB in plan space.
     * Matches {@code ComponentSatelliteInference.makeEntranceComponent}: local SOUTH (front) is
     * {@code z=0}, NORTH {@code z=max}, EAST {@code x=0}, WEST {@code x=max}.
     *
     * @return {@code int[]{x, z}} midpoint of the front face
     */
    public static int[] frontEntranceThresholdXZ(int hostX, int hostZ, int width, int depth, String buildingFacing) {
        String facing = normalize(buildingFacing);
        if (facing == null) facing = SOUTH;
        int midX = hostX + Math.max(0, width) / 2;
        int midZ = hostZ + Math.max(0, depth) / 2;
        return switch (facing) {
            case NORTH -> new int[]{midX, hostZ + Math.max(1, depth) - 1};
            case EAST -> new int[]{hostX, midZ};
            case WEST -> new int[]{hostX + Math.max(1, width) - 1, midZ};
            default -> new int[]{midX, hostZ}; // SOUTH / front at z=0
        };
    }

    private static String normalize(String face) {
        if (face == null || face.isBlank()) return null;
        return face.trim().toUpperCase(Locale.ROOT);
    }

    /** Order: N=0, E=1, S=2, W=3 (clockwise from north). */
    private static int index(String face) {
        return switch (face) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> -1;
        };
    }

    private static String name(int idx) {
        return switch (Math.floorMod(idx, 4)) {
            case 0 -> NORTH;
            case 1 -> EAST;
            case 2 -> SOUTH;
            default -> WEST;
        };
    }
}
