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
     * 启用条件（按优先级）：
     * 1. LlmPlan 的 global_constraints 中包含 useBuildingMass=true（最高优先级）
     * 2. 系统属性 -Dformacraft.useBuildingMass=true
     * 3. 如果 PlanSkeleton 包含多个体量或复杂结构，自动启用（未来实现）
     * <p>
     * 注意：只有 PlanProgram 模式（包含 planSkeleton 或 planProgram）才支持 BuildingMass
     */
    public static boolean shouldUseBuildingMassPath(LlmPlan llmPlan) {
        if (llmPlan == null) {
            return false;
        }

        // 检查是否为 PlanProgram 模式（BuildingMass 只支持此模式）
        if (!llmPlan.usesPlanProgramMode()) {
            return false;
        }

        // 优先级 1：检查 global_constraints 中是否明确指定使用 BuildingMass
        if (llmPlan.globalConstraints() != null) {
            // 检查 global_constraints 中是否有相关字段（通过反射或扩展接口）
            // v1：暂时通过检查 planSkeleton 的复杂度来推断
            // 如果有 planSkeleton 且包含多个 zones，可能适合使用 BuildingMass
            PlanSkeleton planSkeleton = llmPlan.planSkeleton();
            if (planSkeleton != null && planSkeleton.zones() != null && planSkeleton.zones().size() > 1) {
                FormacraftMod.LOGGER.debug(
                        "BuildingMassSystemIntegrator: detected multi-zone PlanSkeleton ({} zones), considering BuildingMass path",
                        planSkeleton.zones().size()
                );
                // v1: 如果超过 2 个 zones，自动启用 BuildingMass
                if (planSkeleton.zones().size() > 2) {
                    return true;
                }
            }
        }

        // 优先级 2：检查系统属性
        String useBuildingMass = System.getProperty("formacraft.useBuildingMass", "false");
        if ("true".equalsIgnoreCase(useBuildingMass)) {
            FormacraftMod.LOGGER.debug("BuildingMassSystemIntegrator: enabled via system property");
            return true;
        }

        // 默认不使用 BuildingMass 路径（保持向后兼容）
        return false;
    }
}
