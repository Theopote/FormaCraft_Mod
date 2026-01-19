package com.formacraft.common.mass.rhythm;

import com.formacraft.common.mass.BuildingMassComposition;
import com.formacraft.common.mass.derived.FacadeRhythmProfile;
import com.formacraft.FormacraftMod;

import java.util.List;

/**
 * FacadeRhythmPresetResolver（立面节奏预设解析器）
 * <p>
 * 🎯 核心职责：
 * 将 AI 选择的预设和微调参数转换为最终的 FacadeRhythmProfile
 * <p>
 * 完整流程：
 * AI 选择预设 → 校验 → 应用微调 → 生成最终 Profile
 */
public final class FacadeRhythmPresetResolver {

    private FacadeRhythmPresetResolver() {}

    /**
     * 解析预设选择为最终的 FacadeRhythmProfile
     *
     * @param selection AI 的预设选择
     * @param composition 体量组合（用于校验）
     * @param floorCount 楼层数
     * @param facadeName 立面名称（"FRONT", "SIDE", "BACK", "LEFT", "RIGHT"）
     * @return 最终的 FacadeRhythmProfile，如果校验失败则返回默认配置
     */
    public static FacadeRhythmProfile resolve(
            FacadeRhythmPresetSelector.PresetSelection selection,
            BuildingMassComposition composition,
            int floorCount,
            String facadeName
    ) {
        if (selection == null) {
            return FacadeRhythmProfile.defaultProfile();
        }

        // Step 1: 获取立面对应的预设 ID
        String presetId = selection.getPresetForFacade(facadeName);
        if (presetId == null || presetId.isEmpty()) {
            presetId = selection.primaryPresetId();
        }

        // Step 2: 获取预设
        FacadeRhythmPreset preset = FacadeRhythmPresetLibrary.getPreset(presetId);
        if (preset == null) {
            FormacraftMod.LOGGER.warn(
                    "FacadeRhythmPresetResolver: Preset {} not found, using default",
                    presetId
            );
            return FacadeRhythmProfile.defaultProfile();
        }

        // Step 3: 校验预设兼容性
        FacadeRhythmPresetValidator.ValidationResult compatibilityResult = 
                FacadeRhythmPresetValidator.validatePresetCompatibility(preset, composition, floorCount);
        if (!compatibilityResult.valid()) {
            FormacraftMod.LOGGER.warn(
                    "FacadeRhythmPresetResolver: Preset {} validation failed: {}, using default",
                    presetId, compatibilityResult.errorMessage()
            );
            return FacadeRhythmProfile.defaultProfile();
        }

        // Step 4: 校验微调参数
        FacadeRhythmPresetValidator.ValidationResult tuningResult = 
                FacadeRhythmPresetValidator.validateTuning(preset, selection.tuning());
        if (!tuningResult.valid()) {
            FormacraftMod.LOGGER.warn(
                    "FacadeRhythmPresetResolver: Tuning validation failed: {}, ignoring tuning",
                    tuningResult.errorMessage()
            );
            // 校验失败时忽略微调，但继续使用预设
        }

        // Step 5: 应用微调（如果有效）
        FacadeRhythmProfile baseProfile = preset.profile();
        if (selection.tuning() != null && tuningResult.valid()) {
            return applyTuning(baseProfile, selection.tuning());
        }

        return baseProfile;
    }

    /**
     * 应用微调参数到 Profile
     */
    private static FacadeRhythmProfile applyTuning(
            FacadeRhythmProfile baseProfile,
            FacadeRhythmPreset.FacadeRhythmTuning tuning
    ) {
        if (tuning == null) {
            return baseProfile;
        }

        FacadeRhythmProfile.Builder builder = FacadeRhythmProfile.builder()
                .rhythmMode(baseProfile.mode)
                .spacing(tuning.spacing() != null ? tuning.spacing() : baseProfile.spacing)
                .alignmentMode(tuning.alignmentMode() != null ? tuning.alignmentMode() : baseProfile.align)
                .symmetryMode(baseProfile.symmetry)
                .variationMode(tuning.variationMode() != null ? tuning.variationMode() : baseProfile.variation);

        return builder.build();
    }

    /**
     * 从建筑特征自动解析预设（简化版，用于默认情况）
     *
     * @param buildingType 建筑类型
     * @param style 风格
     * @param floorCount 层数
     * @return FacadeRhythmProfile
     */
    public static FacadeRhythmProfile resolveFromCharacteristics(
            String buildingType,
            String style,
            int floorCount
    ) {
        FacadeRhythmPresetSelector.BuildingCharacteristics characteristics = 
                new FacadeRhythmPresetSelector.BuildingCharacteristics(
                        buildingType,
                        style,
                        floorCount,
                        List.of(),
                        "none",
                        "simple"
                );

        FacadeRhythmPresetSelector.PresetSelection selection = 
                FacadeRhythmPresetSelector.selectPreset(characteristics);

        // 使用默认 composition 和 facade 解析
        return resolve(selection, null, floorCount, "FRONT");
    }
}
