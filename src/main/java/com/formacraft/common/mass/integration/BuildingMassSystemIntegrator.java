package com.formacraft.common.mass.integration;

import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.PlanSkeleton;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.FormacraftMod;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * BuildingMassSystemIntegrator（建筑体量系统集成器）
 * <p>
 * 🎯 核心职责：
 * 将 BuildingMass 系统集成到现有的 Formacraft 编译流程中
 * <p>
 * 集成点：
 * - PlanProgramCompiler（可选使用 BuildingMass 路径）
 * - LlmPlan 处理（如果包含 PlanSkeleton，可以使用 BuildingMass）
 * - 直接调用（测试/示例用途）
 */
public final class BuildingMassSystemIntegrator {

    private BuildingMassSystemIntegrator() {}

    /**
     * 从 LlmPlan 编译为 BlockPatch（使用 BuildingMass 路径）
     * <p>
     * 如果 LlmPlan 包含 PlanSkeleton 且启用 BuildingMass 路径，则使用此方法
     *
     * @param llmPlan LlmPlan
     * @param origin 世界原点
     * @param world 服务器世界
     * @return BlockPatch 列表
     */
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
            // 检查是否有 PlanSkeleton
            PlanSkeleton planSkeleton = llmPlan.planSkeleton();
            if (planSkeleton == null && llmPlan.planProgram() != null) {
                // 尝试从 PlanProgram 转换
                planSkeleton = com.formacraft.common.llm.converter.PlanProgramToPlanSkeletonConverter.convert(
                        llmPlan.planProgram()
                );
            }

            if (planSkeleton == null) {
                FormacraftMod.LOGGER.warn("BuildingMassSystemIntegrator: no PlanSkeleton available");
                return List.of();
            }

            // 获取基础 Y 坐标
            int baseY = origin != null ? origin.getY() : 64;

            // 执行 BuildingMass 流程
            BuildingMassPipeline.BuildingMassPipelineResult result = BuildingMassPipeline.execute(
                    planSkeleton,
                    baseY
            );

            // 转换为 BlockPatch
            return BuildingMassToBlockPatchConverter.convertToBlockPatches(result, origin);

        } catch (Exception e) {
            FormacraftMod.LOGGER.error("BuildingMassSystemIntegrator: compilation failed", e);
            return List.of();
        }
    }

    /**
     * 检查是否应该使用 BuildingMass 路径
     * <p>
     * v1 简化：默认不使用，需要明确启用
     * 未来：可以根据 LlmPlan 的内容自动判断
     */
    public static boolean shouldUseBuildingMassPath(LlmPlan llmPlan) {
        if (llmPlan == null) {
            return false;
        }

        // v1 简化：如果 LlmPlan 明确包含 PlanSkeleton，可以使用 BuildingMass
        // 但默认仍使用传统路径
        return false; // 暂时禁用，等待进一步测试
    }
}
