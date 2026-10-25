package com.formacraft.server.edit;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** Result of validating compiled patches against a {@link BuildingEditScope}. */
public final class BuildingEditValidation {
    public enum Status { ACCEPTED, REJECTED }

    private final Status status;
    private final List<String> conflicts;
    private final List<BlockPos> unexpectedWrites;
    private final List<String> affectedParts;

    public BuildingEditValidation(
            Status status,
            List<String> conflicts,
            List<BlockPos> unexpectedWrites,
            List<String> affectedParts
    ) {
        this.status = status == null ? Status.REJECTED : status;
        this.conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        this.unexpectedWrites = unexpectedWrites == null ? List.of() : List.copyOf(unexpectedWrites);
        this.affectedParts = affectedParts == null ? List.of() : List.copyOf(affectedParts);
    }

    public static BuildingEditValidation accepted(List<String> affectedParts) {
        return new BuildingEditValidation(Status.ACCEPTED, List.of(), List.of(), affectedParts);
    }

    public static BuildingEditValidation rejected(List<String> conflicts, List<BlockPos> unexpected) {
        return new BuildingEditValidation(Status.REJECTED, conflicts, unexpected, List.of());
    }

    public boolean accepted() { return status == Status.ACCEPTED; }
    public Status status() { return status; }
    public List<String> conflicts() { return conflicts; }
    public List<BlockPos> unexpectedWrites() { return unexpectedWrites; }
    public List<String> affectedParts() { return affectedParts; }

    public String summary() {
        if (accepted()) return "accepted parts=" + affectedParts;
        return "rejected conflicts=" + conflicts + " unexpected=" + unexpectedWrites.size();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final List<String> conflicts = new ArrayList<>();
        private final List<BlockPos> unexpected = new ArrayList<>();
        private final List<String> parts = new ArrayList<>();

        public Builder conflict(String message) {
            if (message != null && !message.isBlank()) conflicts.add(message);
            return this;
        }

        public Builder unexpected(BlockPos pos) {
            if (pos != null) unexpected.add(pos.toImmutable());
            return this;
        }

        public Builder part(String part) {
            if (part != null && !part.isBlank()) parts.add(part);
            return this;
        }

        public BuildingEditValidation build() {
            if (!conflicts.isEmpty() || !unexpected.isEmpty()) {
                return BuildingEditValidation.rejected(conflicts, unexpected);
            }
            return BuildingEditValidation.accepted(parts);
        }
    }
}
