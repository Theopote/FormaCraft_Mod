package com.formacraft.common.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.formacraft.common.genome.BuildingGenome;

import java.util.List;
import java.util.Map;

/**
 * LLM 输出的统一 Plan（build 或 patch）
 * 与 PromptAssembler 的 JSON schema 对齐。
 * <p>
 * 增强支持：
 * - 传统的 components[] 模式（现有系统）
 * - PlanProgram 模式（新的平面规划系统，可选）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmPlan(
        @JsonProperty("mode") Mode mode,
        @JsonProperty("style_profile") String styleProfile,
        @JsonProperty("anchor") Vec3i anchor,
        @JsonProperty("global_constraints") GlobalConstraints globalConstraints,
        @JsonProperty("layout") Layout layout,
        @JsonProperty("components") List<Component> components,
        @JsonProperty("genome") BuildingGenome genome,

        // 风格属性（AI 分析用户描述后提取，用于动态材质选择）
        @JsonProperty("style_attributes") StyleAttributes styleAttributes,

        /** M3：比例/洞口语法提示（与 proportion_cards 对齐） */
        @JsonProperty("proportion_hints") Map<String, Object> proportionHints,

        // patch 专用（可选）
        @JsonProperty("target_slot_id") String targetSlotId,
        @JsonProperty("allowed_area") String allowedArea,

        // 如果 LLM 直接输出 block-level patch（可选）
        @JsonProperty("patch") PatchBlockSection patch,

        // ========== 新增：PlanProgram 模式（可选）==========
        /**
         * PlanProgram（平面程序）
         * <p>
         * 如果提供，系统会使用新的 PlanProgram → PlanSkeleton → StructuralSkeleton → ExtrudedSolid 编译管线。
         * <p>
         * 如果未提供，系统使用传统的 components[] 模式（向后兼容）。
         */
        @JsonProperty("plan_program") PlanProgram planProgram,

        /**
         * PlanSkeleton（2D 几何语义）
         * <p>
         * 如果 LLM 直接提供了 PlanSkeleton（而不是 PlanProgram），可以直接使用。
         * <p>
         * 优先级：planSkeleton > planProgram（如果两者都提供，优先使用 planSkeleton）
         */
        @JsonProperty("plan_skeleton") PlanSkeleton planSkeleton,

        /** ok | capability_gap | error — orchestrator may set explicit failure instead of empty components */
        @JsonProperty("plan_status") String planStatus,

        /** Human-readable failure summary when plan_status != ok */
        @JsonProperty("error") String error,

        /** Structured ASSEMBLY / freeform geometry capability gap */
        @JsonProperty("capability_gap") CapabilityGap capabilityGap,

        /** Research-derived fidelity notice for the player (Chinese) */
        @JsonProperty("player_fidelity_notice_zh") String playerFidelityNoticeZh
) {
    public enum Mode { build, patch }

    public boolean hasCapabilityGap() {
        if (capabilityGap != null) {
            return true;
        }
        if (planStatus == null || planStatus.isBlank()) {
            return false;
        }
        return "capability_gap".equalsIgnoreCase(planStatus.trim());
    }

    /**
     * 检查是否使用 PlanProgram 模式
     */
    public boolean usesPlanProgramMode() {
        return planSkeleton != null || planProgram != null;
    }

    /**
     * 检查是否使用传统 components 模式
     */
    public boolean usesComponentMode() {
        return components != null && !components.isEmpty();
    }
}
