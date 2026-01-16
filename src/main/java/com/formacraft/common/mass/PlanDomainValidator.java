package com.formacraft.common.mass;

import com.formacraft.common.llm.dto.PlanSkeleton;

/**
 * PlanDomainValidator（Plan Domain 验证器）
 * <p>
 * 验证 PlanSkeleton 作为 Domain（范围约束）的有效性
 * <p>
 * 核心职责：
 * 1. 验证 Domain 的合理性
 * 2. 提取 Domain 信息（范围、朝向、参考点）
 * 3. 为体量组合提供输入
 */
public final class PlanDomainValidator {

    private PlanDomainValidator() {}

    /**
     * 验证 PlanSkeleton 作为 Domain 的有效性
     *
     * @param planSkeleton PlanSkeleton（作为 Domain）
     * @return 验证结果
     */
    public static ValidationResult validate(PlanSkeleton planSkeleton) {
        if (planSkeleton == null) {
            return ValidationResult.invalid("PlanSkeleton cannot be null");
        }

        // v1 简化：基本验证
        // 1. 必须有 outline（Domain 必须有范围定义）
        if (planSkeleton.outline() == null) {
            return ValidationResult.invalid("PlanSkeleton must have an outline (Domain boundary)");
        }

        // 2. 验证 zones（如果有）
        if (planSkeleton.zones() != null && planSkeleton.zones().isEmpty()) {
            // 空的 zones 也是合理的（整个 Domain 作为一个整体）
        }

        return ValidationResult.valid();
    }

    /**
     * 提取 Domain 信息
     * <p>
     * 从 PlanSkeleton 提取体量组合所需的信息：
     * - 范围边界
     * - 主轴方向
     * - 参考点
     *
     * @param planSkeleton PlanSkeleton（作为 Domain）
     * @return Domain 信息
     */
    public static DomainInfo extractDomainInfo(PlanSkeleton planSkeleton) {
        if (planSkeleton == null) {
            return null;
        }

        // v1 简化：提取基本信息
        // 未来：从 outline 的实际几何数据提取边界框、主轴等

        return new DomainInfo(
                planSkeleton.outline() != null ? planSkeleton.outline().shape() : null,
                planSkeleton.axes() != null && !planSkeleton.axes().isEmpty()
        );
    }

    /**
     * 验证结果
     */
    public static class ValidationResult {
        public final boolean valid;
        public final String errorMessage;

        private ValidationResult(boolean valid, String errorMessage) {
            this.valid = valid;
            this.errorMessage = errorMessage;
        }

        public static ValidationResult valid() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(String errorMessage) {
            return new ValidationResult(false, errorMessage);
        }
    }

    /**
     * Domain 信息
     */
    public static class DomainInfo {
        /** Outline 形状 */
        public final String outlineShape;

        /** 是否有轴线 */
        public final boolean hasAxes;

        public DomainInfo(String outlineShape, boolean hasAxes) {
            this.outlineShape = outlineShape;
            this.hasAxes = hasAxes;
        }
    }
}
