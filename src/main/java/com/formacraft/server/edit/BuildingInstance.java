package com.formacraft.server.edit;

import com.formacraft.server.memory.ProjectMemory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable identity view over a registered {@link ProjectMemory}.
 * Server-owned; never constructed solely from an LLM-reported id.
 */
public final class BuildingInstance {
    private final UUID buildingId;
    private final Identifier dimension;
    private final BlockPos origin;
    private final BlockPos min;
    private final BlockPos max;
    private final long revision;
    private final String name;

    public BuildingInstance(
            UUID buildingId,
            Identifier dimension,
            BlockPos origin,
            BlockPos min,
            BlockPos max,
            long revision,
            String name
    ) {
        this.buildingId = Objects.requireNonNull(buildingId, "buildingId");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.origin = origin == null ? BlockPos.ORIGIN : origin.toImmutable();
        this.min = min == null ? BlockPos.ORIGIN : min.toImmutable();
        this.max = max == null ? BlockPos.ORIGIN : max.toImmutable();
        this.revision = Math.max(0L, revision);
        this.name = name == null ? "" : name;
    }

    public static BuildingInstance fromMemory(ProjectMemory memory) {
        if (memory == null || memory.getUuid() == null || memory.getUuid().isBlank()) {
            return null;
        }
        UUID id;
        try {
            id = UUID.fromString(memory.getUuid());
        } catch (IllegalArgumentException ex) {
            return null;
        }
        ProjectMemory.SpatialBounds bounds = memory.getBounds();
        if (bounds == null || bounds.getMinPos() == null || bounds.getMaxPos() == null
                || bounds.getDimension() == null || bounds.getDimension().isBlank()) {
            return null;
        }
        Identifier dimension = Identifier.tryParse(bounds.getDimension());
        if (dimension == null) {
            return null;
        }
        BlockPos origin = bounds.getAnchor("origin");
        if (origin == null) {
            origin = bounds.getMinPos();
        }
        return new BuildingInstance(
                id,
                dimension,
                origin,
                bounds.getMinPos(),
                bounds.getMaxPos(),
                BuildingMemoryKeys.revisionOf(memory),
                memory.getName()
        );
    }

    public UUID buildingId() { return buildingId; }
    public Identifier dimension() { return dimension; }
    public BlockPos origin() { return origin; }
    public BlockPos min() { return min; }
    public BlockPos max() { return max; }
    public long revision() { return revision; }
    public String name() { return name; }

    public boolean contains(BlockPos worldPos) {
        if (worldPos == null) return false;
        return worldPos.getX() >= min.getX() && worldPos.getX() <= max.getX()
                && worldPos.getY() >= min.getY() && worldPos.getY() <= max.getY()
                && worldPos.getZ() >= min.getZ() && worldPos.getZ() <= max.getZ();
    }
}
