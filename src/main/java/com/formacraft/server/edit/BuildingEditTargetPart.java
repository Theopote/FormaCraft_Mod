package com.formacraft.server.edit;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Frozen semantic identity for a window-edit ticket.
 * Coordinates alone are not enough: Confirm must re-check that the same wall/window
 * still owns those cells under the same part id.
 */
public record BuildingEditTargetPart(
        String targetPartId,
        String hostWallId,
        int floorIndex,
        String facing,
        String identityDigest
) {
    public BuildingEditTargetPart {
        targetPartId = targetPartId == null ? "" : targetPartId;
        hostWallId = hostWallId == null ? "" : hostWallId;
        floorIndex = Math.max(0, floorIndex);
        facing = facing == null ? "" : facing.toUpperCase(Locale.ROOT);
        identityDigest = identityDigest == null ? "" : identityDigest;
    }

    public static BuildingEditTargetPart none() {
        return new BuildingEditTargetPart("", "", 0, "", "");
    }

    public boolean isPresent() {
        return !targetPartId.isBlank() && !identityDigest.isBlank();
    }

    public static BuildingEditTargetPart wall(
            String wallId,
            int floorIndex,
            String facing,
            Set<BlockPos> cells,
            Map<BlockPos, String> expectedStates
    ) {
        String id = wallId == null || wallId.isBlank() ? "wall" : wallId;
        return new BuildingEditTargetPart(
                id, "", floorIndex, facing, digest(id, "", floorIndex, facing, cells, expectedStates));
    }

    public static BuildingEditTargetPart window(
            String windowId,
            String hostWallId,
            int floorIndex,
            String facing,
            Set<BlockPos> cells,
            Map<BlockPos, String> expectedStates
    ) {
        String id = windowId == null || windowId.isBlank() ? "window" : windowId;
        String host = hostWallId == null ? "" : hostWallId;
        return new BuildingEditTargetPart(
                id, host, floorIndex, facing, digest(id, host, floorIndex, facing, cells, expectedStates));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("targetPartId", targetPartId);
        map.put("hostWallId", hostWallId);
        map.put("floorIndex", floorIndex);
        map.put("facing", facing);
        map.put("identityDigest", identityDigest);
        return map;
    }

    public static BuildingEditTargetPart fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return none();
        try {
            return new BuildingEditTargetPart(
                    String.valueOf(map.getOrDefault("targetPartId", "")),
                    String.valueOf(map.getOrDefault("hostWallId", "")),
                    map.get("floorIndex") instanceof Number n ? n.intValue() : 0,
                    String.valueOf(map.getOrDefault("facing", "")),
                    String.valueOf(map.getOrDefault("identityDigest", "")));
        } catch (Exception ignored) {
            return none();
        }
    }

    /**
     * Stable digest over part identity + cell set + expected states.
     * Same coordinates under a different partId produce a different digest.
     */
    public static String digest(
            String partId,
            String hostWallId,
            int floorIndex,
            String facing,
            Set<BlockPos> cells,
            Map<BlockPos, String> expectedStates
    ) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(partId == null ? "" : partId).append('|')
                .append(hostWallId == null ? "" : hostWallId).append('|')
                .append(Math.max(0, floorIndex)).append('|')
                .append(facing == null ? "" : facing.toUpperCase(Locale.ROOT)).append('|');
        List<BlockPos> ordered = new ArrayList<>(cells == null ? List.of() : cells);
        ordered.sort(Comparator.comparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getZ));
        for (BlockPos cell : ordered) {
            if (cell == null) continue;
            sb.append(cell.getX()).append(',')
                    .append(cell.getY()).append(',')
                    .append(cell.getZ()).append('=');
            String state = expectedStates == null ? null : expectedStates.get(cell);
            sb.append(state == null ? "" : state).append(';');
        }
        return Integer.toHexString(Objects.hash(sb.toString()));
    }

    /** Recompute digest with the same identity metadata but a new cell/state snapshot. */
    public String digestWith(Set<BlockPos> cells, Map<BlockPos, String> expectedStates) {
        return digest(targetPartId, hostWallId, floorIndex, facing, cells, expectedStates);
    }
}
