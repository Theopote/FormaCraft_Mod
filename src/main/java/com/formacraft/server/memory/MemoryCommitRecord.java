package com.formacraft.server.memory;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.component.transform.BlockStateStringUtil;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.patch.BlockPatchTargetResolver;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Durable record of one building Memory commit.
 * <p>
 * The record owns the <b>stable memory identity</b> ({@code memoryUuid}) so a retry
 * updates the same Memory record instead of registering a second building.
 * It also carries the owning dimension, so a retry can never commit a building
 * into a foreign world.
 */
public final class MemoryCommitRecord {
    public enum Status {
        /** Commit not yet durably persisted. */
        PENDING,
        /** Memory record exists on disk. */
        COMMITTED,
        /** Retries exhausted — kept for diagnostics and manual recovery. */
        FAILED_NEEDS_ATTENTION,
        /** The building was fully undone before the Memory commit succeeded. */
        CANCELLED
    }

    public String memoryUuid;
    public String transactionId;
    /** Dimension identifier (e.g. {@code minecraft:overworld}). */
    public String dimension;
    public String name;
    public String description;
    public String ownerUuid;
    public int originX;
    public int originY;
    public int originZ;
    /** Parallel arrays: absolute positions and serialized block states. */
    public List<int[]> positions = new ArrayList<>();
    public List<String> states = new ArrayList<>();
    public String specJson;
    public String fbdSourcePlanJson;
    public Status status = Status.PENDING;
    public int attempts;
    /** Epoch millis of the next allowed retry. */
    public long nextAttemptMs;
    public String lastError;
    /** Undo progress recorded before the Memory existed (null = no undo yet). */
    public Integer undoRestoredPositions;
    public Integer undoTotalPositions;
    public boolean undoComplete;

    public static MemoryCommitRecord create(
            String memoryUuid,
            UUID transactionId,
            String dimension,
            String name,
            GeneratedStructure structure,
            BuildingSpec spec,
            String fbdSourcePlanJson
    ) {
        MemoryCommitRecord record = new MemoryCommitRecord();
        record.memoryUuid = memoryUuid;
        record.transactionId = transactionId == null ? null : transactionId.toString();
        record.dimension = dimension;
        record.name = name;
        record.specJson = spec == null ? null : JsonUtil.toJson(spec);
        record.fbdSourcePlanJson = fbdSourcePlanJson;
        if (structure != null) {
            record.description = structure.getDescription();
            record.originX = structure.getOrigin().getX();
            record.originY = structure.getOrigin().getY();
            record.originZ = structure.getOrigin().getZ();
            if (structure.getOwner() != null) record.ownerUuid = structure.getOwner().toString();
            for (PlannedBlock block : structure.getBlocks()) {
                if (block == null || block.getPos() == null) continue;
                BlockPos pos = block.getPos();
                record.positions.add(new int[]{pos.getX(), pos.getY(), pos.getZ()});
                record.states.add(BlockStateStringUtil.fromState(block.getTargetState()));
            }
        }
        return record;
    }

    /** Rebuild the structure snapshot for Memory registration. */
    public GeneratedStructure toStructure() {
        List<PlannedBlock> blocks = new ArrayList<>();
        int count = Math.min(positions.size(), states.size());
        for (int i = 0; i < count; i++) {
            int[] p = positions.get(i);
            if (p == null || p.length < 3) continue;
            BlockState state = BlockPatchTargetResolver.parse(states.get(i));
            if (state == null) continue;
            blocks.add(new PlannedBlock(new BlockPos(p[0], p[1], p[2]), state));
        }
        UUID owner = ownerUuid == null ? null : UUID.fromString(ownerUuid);
        return new GeneratedStructure(owner, new BlockPos(originX, originY, originZ),
                description == null ? "building" : description, blocks);
    }

    public BuildingSpec toSpec() {
        if (specJson == null || specJson.isBlank()) return null;
        try {
            return JsonUtil.fromJson(specJson, BuildingSpec.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public String toJson() {
        return JsonUtil.toJson(this);
    }

    public static MemoryCommitRecord fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JsonUtil.fromJson(json, MemoryCommitRecord.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public boolean fullyUndone() {
        return undoComplete;
    }
}
