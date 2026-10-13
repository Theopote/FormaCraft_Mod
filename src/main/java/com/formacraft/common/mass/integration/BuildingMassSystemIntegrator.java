package com.formacraft.common.mass.integration;

import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.PlanSkeleton;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.FormacraftMod;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * BuildingMassSystemIntegrator：将 BuildingMass 集成到编译流程。
 * <p>
 * 启用条件（仅显式）：
 * 1. {@code global_constraints.use_building_mass=true}
 * 2. 系统属性 {@code -Dformacraft.useBuildingMass=true}
 * <p>
 * 不再因 zones 数量自动启用，避免与 Skeleton 隐式双路径。
 */
public final class BuildingMassSystemIntegrator {

    private BuildingMassSystemIntegrator() {}

    public static List<BlockPatch> compileWithBuildingMass(
            LlmPlan llmPlan,
            BlockPos origin,
            ServerWorld world
    ) {
        if (llmPlan == null) {
            FormacraftMod.LOGGER.warn("BuildingMassSystemIntegrator: llmPlan is null");
            return List.of();
        }

        try {
            PlanSkeleton planSkeleton = llmPlan.planSkeleton();
            if (planSkeleton == null && llmPlan.planProgram() != null) {
                planSkeleton = com.formacraft.common.llm.converter.PlanProgramToPlanSkeletonConverter.convert(
                        llmPlan.planProgram()
                );
            }

            if (planSkeleton == null) {
                FormacraftMod.LOGGER.warn("BuildingMassSystemIntegrator: no PlanSkeleton available");
                return List.of();
            }

            int baseY = origin != null ? origin.getY() : 64;
            BuildingMassPipeline.BuildingMassPipelineResult result = BuildingMassPipeline.execute(
                    planSkeleton,
                    baseY
            );
            return BuildingMassToBlockPatchConverter.convertToBlockPatches(result, origin);

        } catch (Exception e) {
            FormacraftMod.LOGGER.error("BuildingMassSystemIntegrator: compilation failed", e);
            return List.of();
        }
    }

    /**
     * 是否启用 BuildingMass 体块路径（替代重叠壳层，非隐式叠加）。
     */
    public static boolean shouldUseBuildingMassPath(LlmPlan llmPlan) {
        if (llmPlan == null || !llmPlan.usesPlanProgramMode()) {
            return false;
        }

        if (llmPlan.globalConstraints() != null && llmPlan.globalConstraints().useBuildingMassEnabled()) {
            FormacraftMod.LOGGER.debug("BuildingMassSystemIntegrator: enabled via global_constraints.use_building_mass");
            return true;
        }

        String useBuildingMass = System.getProperty("formacraft.useBuildingMass", "false");
        if ("true".equalsIgnoreCase(useBuildingMass)) {
            FormacraftMod.LOGGER.debug("BuildingMassSystemIntegrator: enabled via system property");
            return true;
        }

        return false;
    }
}
