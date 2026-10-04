package com.formacraft.common.compiler.postprocess;

import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import net.minecraft.util.math.BlockPos;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Post-processing coordinates are local to the plan, including each compiled slot offset once. */
public record PostProcessContext(LlmPlan plan, BlockPos globalAnchor, Vec3i relativeAnchor,
                                 List<BuildingVolume> buildingVolumes, Set<BlockPos> protectedClearance,
                                 Set<BlockPos> generatedSurfaces, Set<BlockPos> protectedMaterials) {
    public record BuildingVolume(String slotId, ComponentFootprintUtil.Bounds bounds, int floorHeight) {
        public BuildingVolume {
            Objects.requireNonNull(bounds);
            if (bounds.width() <= 0 || bounds.depth() <= 0 || bounds.height() <= 0 || floorHeight <= 0)
                throw new IllegalArgumentException("Invalid building volume");
        }
        public boolean contains(int x, int y, int z) {
            return x >= bounds.minX() && x < bounds.maxX() && y >= bounds.minY() && y < bounds.maxY()
                && z >= bounds.minZ() && z < bounds.maxZ();
        }
    }
    public PostProcessContext {
        protectedMaterials = protectedMaterials == null ? Set.of() : protectedMaterials.stream()
            .map(BlockPos::toImmutable).collect(java.util.stream.Collectors.toUnmodifiableSet());
        buildingVolumes = buildingVolumes == null ? List.of() : List.copyOf(buildingVolumes);
        protectedClearance = protectedClearance == null ? Set.of() : protectedClearance.stream()
            .map(BlockPos::toImmutable).collect(java.util.stream.Collectors.toUnmodifiableSet());
        generatedSurfaces = generatedSurfaces == null ? Set.of() : generatedSurfaces.stream()
            .map(BlockPos::toImmutable).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    public PostProcessContext(LlmPlan plan, BlockPos globalAnchor, Vec3i relativeAnchor,
                              List<BuildingVolume> volumes, Set<BlockPos> clearance, Set<BlockPos> surfaces) {
        this(plan, globalAnchor, relativeAnchor, volumes, clearance, surfaces, Set.of());
    }
    public PostProcessContext(LlmPlan plan, BlockPos globalAnchor, Vec3i relativeAnchor,
                              List<BuildingVolume> volumes, Set<BlockPos> clearance) {
        this(plan, globalAnchor, relativeAnchor, volumes, clearance, Set.of());
    }
    public PostProcessContext(LlmPlan plan, BlockPos globalAnchor, Vec3i relativeAnchor, List<BuildingVolume> volumes) {
        this(plan, globalAnchor, relativeAnchor, volumes, Set.of());
    }
    /** Compatibility for direct callers without compiled building metadata. */
    public PostProcessContext(LlmPlan plan, BlockPos globalAnchor, Vec3i relativeAnchor) {
        this(plan, globalAnchor, relativeAnchor, List.of());
    }
    public static PostProcessContext create(LlmPlan plan, BlockPos globalAnchor) {
        return create(plan, globalAnchor, List.of());
    }
    public static PostProcessContext create(LlmPlan plan, BlockPos globalAnchor, List<BuildingVolume> volumes) {
        return create(plan, globalAnchor, volumes, Set.of());
    }
    public static PostProcessContext create(LlmPlan plan, BlockPos globalAnchor, List<BuildingVolume> volumes, Set<BlockPos> clearance) {
        Vec3i anchor = plan.anchor() != null ? plan.anchor() : new Vec3i(0, 0, 0);
        return new PostProcessContext(plan, globalAnchor, anchor, volumes, clearance);
    }
    public static PostProcessContext create(LlmPlan plan, BlockPos globalAnchor, List<BuildingVolume> volumes,
                                             Set<BlockPos> clearance, Set<BlockPos> surfaces) {
        Vec3i anchor = plan.anchor() != null ? plan.anchor() : new Vec3i(0, 0, 0);
        return new PostProcessContext(plan, globalAnchor, anchor, volumes, clearance, surfaces);
    }
}
