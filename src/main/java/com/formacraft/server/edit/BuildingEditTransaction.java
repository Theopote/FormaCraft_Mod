package com.formacraft.server.edit;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable building-edit transaction spanning Preview Confirm → world Apply → Memory commit.
 * Survives independently of ProjectMemory so RECOVERY_REQUIRED is not lost when Memory I/O fails.
 */
public final class BuildingEditTransaction {
    public enum Status {
        PREPARED,
        WORLD_APPLIED,
        MEMORY_COMMITTED,
        RECOVERY_REQUIRED,
        ABORTED
    }

    public enum Operation {
        REPLACE_ROOF_MATERIAL,
        INSERT_WINDOW,
        SEAL_WINDOW;

        static Operation from(BuildingEditOperation op) {
            if (op == null) return REPLACE_ROOF_MATERIAL;
            return switch (op) {
                case REPLACE_ROOF_MATERIAL -> REPLACE_ROOF_MATERIAL;
                case INSERT_WINDOW -> INSERT_WINDOW;
                case SEAL_WINDOW -> SEAL_WINDOW;
            };
        }

        BuildingEditOperation toEditOperation() {
            return switch (this) {
                case REPLACE_ROOF_MATERIAL -> BuildingEditOperation.REPLACE_ROOF_MATERIAL;
                case INSERT_WINDOW -> BuildingEditOperation.INSERT_WINDOW;
                case SEAL_WINDOW -> BuildingEditOperation.SEAL_WINDOW;
            };
        }
    }

    private final UUID transactionId;
    private final UUID ticketId;
    private final UUID playerId;
    private final UUID buildingId;
    private final Identifier dimension;
    private final long baseRevision;
    private final Operation operation;
    private final Map<BlockPos, String> expectedWorldStates;
    private final Map<BlockPos, String> targetWorldStates;
    private final BuildingEditTargetPart targetPart;
    private Status status;
    private final long createdAtMillis;
    private long updatedAtMillis;

    public BuildingEditTransaction(
            UUID transactionId,
            UUID ticketId,
            UUID playerId,
            UUID buildingId,
            Identifier dimension,
            long baseRevision,
            Operation operation,
            Map<BlockPos, String> expectedWorldStates,
            Map<BlockPos, String> targetWorldStates,
            Status status,
            long createdAtMillis,
            long updatedAtMillis
    ) {
        this(transactionId, ticketId, playerId, buildingId, dimension, baseRevision, operation,
                expectedWorldStates, targetWorldStates, BuildingEditTargetPart.none(),
                status, createdAtMillis, updatedAtMillis);
    }

    public BuildingEditTransaction(
            UUID transactionId,
            UUID ticketId,
            UUID playerId,
            UUID buildingId,
            Identifier dimension,
            long baseRevision,
            Operation operation,
            Map<BlockPos, String> expectedWorldStates,
            Map<BlockPos, String> targetWorldStates,
            BuildingEditTargetPart targetPart,
            Status status,
            long createdAtMillis,
            long updatedAtMillis
    ) {
        this.transactionId = Objects.requireNonNull(transactionId, "transactionId");
        this.ticketId = Objects.requireNonNull(ticketId, "ticketId");
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.buildingId = Objects.requireNonNull(buildingId, "buildingId");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.baseRevision = Math.max(0L, baseRevision);
        this.operation = operation == null ? Operation.REPLACE_ROOF_MATERIAL : operation;
        this.expectedWorldStates = copyStates(expectedWorldStates);
        this.targetWorldStates = copyStates(targetWorldStates);
        this.targetPart = targetPart == null ? BuildingEditTargetPart.none() : targetPart;
        this.status = status == null ? Status.PREPARED : status;
        this.createdAtMillis = createdAtMillis;
        this.updatedAtMillis = updatedAtMillis;
    }

    private static Map<BlockPos, String> copyStates(Map<BlockPos, String> raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        return Map.copyOf(new LinkedHashMap<>(raw));
    }

    public UUID transactionId() { return transactionId; }
    public UUID ticketId() { return ticketId; }
    public UUID playerId() { return playerId; }
    public UUID buildingId() { return buildingId; }
    public Identifier dimension() { return dimension; }
    public long baseRevision() { return baseRevision; }
    public Operation operation() { return operation; }
    public Map<BlockPos, String> expectedWorldStates() { return expectedWorldStates; }
    public Map<BlockPos, String> targetWorldStates() { return targetWorldStates; }
    public BuildingEditTargetPart targetPart() { return targetPart; }
    public Status status() { return status; }
    public long createdAtMillis() { return createdAtMillis; }
    public long updatedAtMillis() { return updatedAtMillis; }

    public boolean isOpen() {
        return status == Status.PREPARED
                || status == Status.WORLD_APPLIED
                || status == Status.RECOVERY_REQUIRED;
    }

    public void setStatus(Status next) {
        this.status = Objects.requireNonNull(next, "status");
        this.updatedAtMillis = System.currentTimeMillis();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("transactionId", transactionId.toString());
        map.put("ticketId", ticketId.toString());
        map.put("playerId", playerId.toString());
        map.put("buildingId", buildingId.toString());
        map.put("dimension", dimension.toString());
        map.put("baseRevision", baseRevision);
        map.put("operation", operation.name());
        map.put("status", status.name());
        map.put("createdAtMillis", createdAtMillis);
        map.put("updatedAtMillis", updatedAtMillis);
        map.put("expectedWorldStates", packStates(expectedWorldStates));
        map.put("targetWorldStates", packStates(targetWorldStates));
        map.put("targetPart", targetPart.toMap());
        return map;
    }

    @SuppressWarnings("unchecked")
    public static BuildingEditTransaction fromMap(Map<String, Object> map) {
        if (map == null) return null;
        try {
            BuildingEditTargetPart part = BuildingEditTargetPart.none();
            Object rawPart = map.get("targetPart");
            if (rawPart instanceof Map<?, ?> partMap) {
                part = BuildingEditTargetPart.fromMap((Map<String, Object>) partMap);
            }
            return new BuildingEditTransaction(
                    UUID.fromString(String.valueOf(map.get("transactionId"))),
                    UUID.fromString(String.valueOf(map.get("ticketId"))),
                    UUID.fromString(String.valueOf(map.get("playerId"))),
                    UUID.fromString(String.valueOf(map.get("buildingId"))),
                    Identifier.of(String.valueOf(map.get("dimension"))),
                    ((Number) map.get("baseRevision")).longValue(),
                    Operation.valueOf(String.valueOf(map.get("operation"))),
                    unpackStates((Map<String, String>) map.get("expectedWorldStates")),
                    unpackStates((Map<String, String>) map.get("targetWorldStates")),
                    part,
                    Status.valueOf(String.valueOf(map.get("status"))),
                    ((Number) map.get("createdAtMillis")).longValue(),
                    ((Number) map.get("updatedAtMillis")).longValue());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Map<String, String> packStates(Map<BlockPos, String> states) {
        Map<String, String> packed = new LinkedHashMap<>();
        for (var e : states.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            packed.put(BuildingMemoryKeys.cellKey(e.getKey()), e.getValue());
        }
        return packed;
    }

    private static Map<BlockPos, String> unpackStates(Map<String, String> packed) {
        if (packed == null || packed.isEmpty()) return Map.of();
        Map<BlockPos, String> out = new LinkedHashMap<>();
        for (var e : packed.entrySet()) {
            BlockPos pos = parseCellKey(e.getKey());
            if (pos != null && e.getValue() != null) out.put(pos, e.getValue());
        }
        return out;
    }

    private static BlockPos parseCellKey(String key) {
        if (key == null || key.isBlank()) return null;
        String[] parts = key.split(",");
        if (parts.length < 3) return null;
        try {
            return new BlockPos(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
