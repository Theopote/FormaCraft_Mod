package com.formacraft.common.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * LLM 输出的统一 Plan（build 或 patch）
 * 与 PromptAssembler 的 JSON schema 对齐。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmPlan(
        @JsonProperty("mode") Mode mode,
        @JsonProperty("style_profile") String styleProfile,
        @JsonProperty("anchor") Vec3i anchor,
        @JsonProperty("global_constraints") GlobalConstraints globalConstraints,
        @JsonProperty("layout") Layout layout,
        @JsonProperty("components") List<Component> components,

        // 风格属性（AI 分析用户描述后提取，用于动态材质选择）
        @JsonProperty("style_attributes") StyleAttributes styleAttributes,

        // patch 专用（可选）
        @JsonProperty("target_slot_id") String targetSlotId,
        @JsonProperty("allowed_area") String allowedArea,

        // 如果 LLM 直接输出 block-level patch（可选）
        @JsonProperty("patch") PatchBlockSection patch
) {
    public enum Mode { build, patch }
}

