package com.formacraft.server.edit;

import com.formacraft.server.memory.MemoryManager;
import com.formacraft.server.memory.ProjectMemory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Server authority for which registered building an edit targets.
 * Explicit identity wins; spatial matches must be unambiguous.
 */
public final class BuildingTargetResolver {
    public enum Failure {
        NO_TARGET,
        AMBIGUOUS,
        NOT_REGISTERED,
        DIMENSION_MISMATCH
    }

    public record Result(BuildingInstance instance, Failure failure, List<UUID> candidates) {
        public Result {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        public boolean ok() {
            return instance != null && failure == null;
        }

        public static Result ok(BuildingInstance instance) {
            return new Result(instance, null, List.of(instance.buildingId()));
        }

        public static Result fail(Failure failure, List<UUID> candidates) {
            return new Result(null, failure, candidates);
        }
    }

    public record Query(
            Identifier dimension,
            UUID explicitBuildingId,
            BlockPos selectionMin,
            BlockPos selectionMax,
            BlockPos lookAt,
            double nearestMaxDistance
    ) {
        public Query {
            nearestMaxDistance = nearestMaxDistance <= 0 ? 16.0 : nearestMaxDistance;
        }
    }

    private final MemoryManager memoryManager;

    public BuildingTargetResolver(MemoryManager memoryManager) {
        this.memoryManager = Objects.requireNonNull(memoryManager, "memoryManager");
    }

    public Result resolve(Query query) {
        if (query == null || query.dimension() == null) {
            return Result.fail(Failure.NO_TARGET, List.of());
        }

        if (query.explicitBuildingId() != null) {
            ProjectMemory memory = memoryManager.getMemory(query.explicitBuildingId().toString());
            BuildingInstance instance = BuildingInstance.fromMemory(memory);
            if (instance == null) {
                return Result.fail(Failure.NOT_REGISTERED, List.of(query.explicitBuildingId()));
            }
            if (!instance.dimension().equals(query.dimension())) {
                return Result.fail(Failure.DIMENSION_MISMATCH, List.of(instance.buildingId()));
            }
            return Result.ok(instance);
        }

        Set<UUID> ids = new LinkedHashSet<>();
        if (query.selectionMin() != null && query.selectionMax() != null) {
            ids.addAll(collectOverlapping(query.dimension(), query.selectionMin(), query.selectionMax()));
        }
        if (ids.isEmpty() && query.lookAt() != null) {
            for (ProjectMemory memory : memoryManager.findAtPosition(query.dimension(), query.lookAt())) {
                BuildingInstance instance = BuildingInstance.fromMemory(memory);
                if (instance != null) ids.add(instance.buildingId());
            }
        }
        if (ids.isEmpty() && query.lookAt() != null) {
            ProjectMemory nearest = memoryManager.findNearest(
                    query.dimension(), query.lookAt(), query.nearestMaxDistance());
            BuildingInstance instance = BuildingInstance.fromMemory(nearest);
            if (instance != null) ids.add(instance.buildingId());
        }

        if (ids.isEmpty()) {
            return Result.fail(Failure.NO_TARGET, List.of());
        }
        if (ids.size() > 1) {
            return Result.fail(Failure.AMBIGUOUS, List.copyOf(ids));
        }
        UUID only = ids.iterator().next();
        BuildingInstance instance = BuildingInstance.fromMemory(memoryManager.getMemory(only.toString()));
        if (instance == null) {
            return Result.fail(Failure.NOT_REGISTERED, List.of(only));
        }
        return Result.ok(instance);
    }

    private List<UUID> collectOverlapping(Identifier dimension, BlockPos min, BlockPos max) {
        int x0 = Math.min(min.getX(), max.getX());
        int y0 = Math.min(min.getY(), max.getY());
        int z0 = Math.min(min.getZ(), max.getZ());
        int x1 = Math.max(min.getX(), max.getX());
        int y1 = Math.max(min.getY(), max.getY());
        int z1 = Math.max(min.getZ(), max.getZ());
        // Sample corners + center to avoid scanning every cell of large selections.
        BlockPos[] samples = {
                new BlockPos(x0, y0, z0), new BlockPos(x1, y0, z0),
                new BlockPos(x0, y0, z1), new BlockPos(x1, y0, z1),
                new BlockPos(x0, y1, z0), new BlockPos(x1, y1, z0),
                new BlockPos(x0, y1, z1), new BlockPos(x1, y1, z1),
                new BlockPos((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2)
        };
        Set<UUID> ids = new LinkedHashSet<>();
        for (BlockPos sample : samples) {
            for (ProjectMemory memory : memoryManager.findAtPosition(dimension, sample)) {
                BuildingInstance instance = BuildingInstance.fromMemory(memory);
                if (instance == null) continue;
                if (rangesOverlap(instance.min(), instance.max(),
                        new BlockPos(x0, y0, z0), new BlockPos(x1, y1, z1))) {
                    ids.add(instance.buildingId());
                }
            }
        }
        return new ArrayList<>(ids);
    }

    private static boolean rangesOverlap(BlockPos aMin, BlockPos aMax, BlockPos bMin, BlockPos bMax) {
        return aMin.getX() <= bMax.getX() && aMax.getX() >= bMin.getX()
                && aMin.getY() <= bMax.getY() && aMax.getY() >= bMin.getY()
                && aMin.getZ() <= bMax.getZ() && aMax.getZ() >= bMin.getZ();
    }

    public static Optional<String> refusalMessage(Result result) {
        if (result == null || result.ok()) return Optional.empty();
        return Optional.of(switch (result.failure()) {
            case NO_TARGET -> "未找到可编辑的已登记建筑；请选择建筑或靠近目标后再试。";
            case AMBIGUOUS -> "附近存在多栋已登记建筑，目标不明确；请显式选择要修改的建筑。";
            case NOT_REGISTERED -> "指定建筑未登记在 Memory 中，拒绝猜测性全局修改。";
            case DIMENSION_MISMATCH -> "指定建筑不在当前维度。";
        });
    }
}
