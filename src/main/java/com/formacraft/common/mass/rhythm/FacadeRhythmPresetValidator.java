package com.formacraft.common.mass.rhythm;

import com.formacraft.common.mass.BuildingMassComposition;
import com.formacraft.FormacraftMod;

/**
 * FacadeRhythmPresetValidator（立面节奏预设校验器）
 * <p>
 * 🎯 核心职责：
 * 系统侧的 Guard（不可省略）
 * <p>
 * 校验规则：
 * - 单层建筑 ❌ TEMPLE_AXIAL（需要多层才有效果）
 * - 工业建筑 ❌ PALACE_HIERARCHICAL（风格不匹配）
 * - 微调参数必须在允许范围内
 */
public final class FacadeRhythmPresetValidator {

    private FacadeRhythmPresetValidator() {}

    /**
     * 校验预设兼容性
     *
     * @param preset 预设
     * @param composition 体量组合
     * @param floorCount 楼层数
     * @return 校验结果
     */
    public static ValidationResult validatePresetCompatibility(
            FacadeRhythmPreset preset,
            BuildingMassComposition composition,
            int floorCount
    ) {
        if (preset == null) {
            return ValidationResult.error("Preset cannot be null");
        }

        // 检查层数范围
        if (floorCount < preset.minFloors()) {
            return ValidationResult.error(
                    String.format("Preset %s requires at least %d floors, but building has %d",
                            preset.id(), preset.minFloors(), floorCount)
            );
        }

        if (preset.maxFloors() > 0 && floorCount > preset.maxFloors()) {
            return ValidationResult.error(
                    String.format("Preset %s allows at most %d floors, but building has %d",
                            preset.id(), preset.maxFloors(), floorCount)
            );
        }

        // 检查特定规则
        // 例如：单层建筑不能使用 TEMPLE_AXIAL
        if (floorCount == 1 && "TEMPLE_AXIAL".equals(preset.id())) {
            return ValidationResult.error("TEMPLE_AXIAL requires at least 2 floors");
        }

        // 例如：工业建筑不应该使用 PALACE_HIERARCHICAL
        // 这里可以根据 composition 的特征来判断（v1 简化）

        return ValidationResult.ok();
    }

    /**
     * 校验微调参数
     *
     * @param preset 预设
     * @param tuning 微调参数
     * @return 校验结果
     */
    public static ValidationResult validateTuning(
            FacadeRhythmPreset preset,
            FacadeRhythmPreset.FacadeRhythmTuning tuning
    ) {
        if (preset == null) {
            return ValidationResult.error("Preset cannot be null");
        }

        if (tuning == null) {
            return ValidationResult.ok(); // 无微调是允许的
        }

        // 使用预设的 TuningRange 校验
        if (preset.tuningRange() != null && !preset.tuningRange().isValidTuning(tuning)) {
            return ValidationResult.error(
                    String.format("Tuning parameters for preset %s are out of allowed range", preset.id())
            );
        }

        return ValidationResult.ok();
    }

    /**
     * 校验结果
     */
    public record ValidationResult(
            boolean valid,
            String errorMessage
    ) {
        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult error(String message) {
            FormacraftMod.LOGGER.warn("FacadeRhythmPresetValidator: {}", message);
            return new ValidationResult(false, message);
        }
    }
}
