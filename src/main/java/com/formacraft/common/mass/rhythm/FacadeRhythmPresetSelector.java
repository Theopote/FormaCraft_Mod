package com.formacraft.common.mass.rhythm;

import com.formacraft.common.mass.derived.FacadeRhythmProfile;
import com.formacraft.FormacraftMod;

import java.util.*;

/**
 * FacadeRhythmPresetSelector（立面节奏预设选择器）
 * <p>
 * 🎯 核心职责：
 * AI 真正参与的地方：Profile Selection & Tuning
 * <p>
 * AI 只做 3 件事：
 * 1. 选择一个或两个 FacadeRhythmPreset
 * 2. 在允许范围内微调参数（可选）
 * 3. 为不同立面选择不同节奏（进阶但安全）
 * <p>
 * ⚠️ 重要：AI 不能突破预设边界，这些是"安全轨道"
 */
public final class FacadeRhythmPresetSelector {

    private FacadeRhythmPresetSelector() {}

    /**
     * AI 的输入（高度语义层）
     * <p>
     * AI 不需要看到：所有 Socket、所有楼层、所有墙面
     * AI 只需要看到高度语义层
     */
    public record BuildingCharacteristics(
            String buildingType,      // "residential", "temple", "palace", etc.
            String style,             // "traditional_chinese", "modern", etc.
            int floors,               // 层数
            List<String> massing,     // ["primary", "wing"], etc.
            String symmetry,          // "strong", "none", "weak"
            String expression         // "calm", "grand", "simple", etc.
    ) {}

    /**
     * AI 的输出（选择结果）
     */
    public record PresetSelection(
            /**
             * 主要预设 ID
             */
            String primaryPresetId,

            /**
             * 次要预设 ID（可选，用于混合）
             */
            String secondaryPresetId,

            /**
             * 微调参数（可选）
             */
            FacadeRhythmPreset.FacadeRhythmTuning tuning,

            /**
             * 不同立面的覆盖（可选）
             */
            Map<String, String> facadeOverrides  // "FRONT" -> presetId, "SIDE" -> presetId
    ) {
        /**
         * 获取指定立面的预设 ID
         */
        public String getPresetForFacade(String facadeName) {
            if (facadeOverrides != null && facadeOverrides.containsKey(facadeName)) {
                return facadeOverrides.get(facadeName);
            }
            return primaryPresetId;
        }
    }

    /**
     * 根据建筑特征选择节奏预设（AI 调用的方法）
     * <p>
     * 这是 AI 真正参与的地方。
     * AI 输入高度语义层的建筑特征，输出预设选择。
     *
     * @param characteristics 建筑特征（AI 的输入）
     * @return 预设选择结果
     */
    public static PresetSelection selectPreset(BuildingCharacteristics characteristics) {
        if (characteristics == null) {
            return defaultSelection();
        }

        // Step 1: 将字符串标签转换为枚举
        FacadeRhythmPreset.StyleTag styleTag = parseStyleTag(characteristics.style);
        FacadeRhythmPreset.BuildingTag buildingTag = parseBuildingTag(characteristics.buildingType);

        // Step 2: 查找匹配的预设
        List<FacadeRhythmPreset> candidates = FacadeRhythmPresetLibrary.findMatchingPresets(
                characteristics.floors,
                styleTag,
                buildingTag
        );

        if (candidates.isEmpty()) {
            FormacraftMod.LOGGER.warn(
                    "FacadeRhythmPresetSelector: No matching preset found for {}, using default",
                    characteristics
            );
            return defaultSelection();
        }

        // Step 3: 评分并排序（模拟 AI 的智能选择）
        List<ScoredPreset> scored = candidates.stream()
                .map(preset -> new ScoredPreset(
                        preset,
                        scorePreset(preset, characteristics, styleTag, buildingTag)
                ))
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .toList();

        // Step 4: 选择最佳预设
        FacadeRhythmPreset primary = scored.get(0).preset;

        // Step 5: 根据对称性等信息决定是否需要微调
        FacadeRhythmPreset.FacadeRhythmTuning tuning = null;
        if ("strong".equalsIgnoreCase(characteristics.symmetry) && 
            primary.profile().symmetry == FacadeRhythmProfile.SymmetryMode.NONE) {
            // 如果需要强对称但预设没有，微调添加对称
            tuning = new FacadeRhythmPreset.FacadeRhythmTuning(
                    null,
                    null,
                    FacadeRhythmProfile.AlignmentMode.AXIS_ALIGNED
            );
        }

        // Step 6: 根据 expression 决定不同立面的覆盖（简化版）
        Map<String, String> facadeOverrides = null;
        if ("grand".equalsIgnoreCase(characteristics.expression) && scored.size() > 1) {
            // 如果要求"宏伟"，正面使用更正式的预设
            facadeOverrides = new HashMap<>();
            facadeOverrides.put("FRONT", primary.id());
            if (scored.size() > 1) {
                facadeOverrides.put("SIDE", scored.get(1).preset.id());
            }
        }

        return new PresetSelection(
                primary.id(),
                scored.size() > 1 ? scored.get(1).preset.id() : null,
                tuning,
                facadeOverrides
        );
    }

    /**
     * 评分预设（模拟 AI 的智能选择）
     */
    private static double scorePreset(
            FacadeRhythmPreset preset,
            BuildingCharacteristics characteristics,
            FacadeRhythmPreset.StyleTag styleTag,
            FacadeRhythmPreset.BuildingTag buildingTag
    ) {
        double score = 0.0;

        // 风格匹配度（50%）
        if (styleTag != null && preset.styleTags().contains(styleTag)) {
            score += 0.5;
        }

        // 建筑类型匹配度（30%）
        if (buildingTag != null && preset.buildingTags().contains(buildingTag)) {
            score += 0.3;
        }

        // 层数兼容性（20%）
        int floorCount = characteristics.floors;
        if (floorCount >= preset.minFloors() && (preset.maxFloors() <= 0 || floorCount <= preset.maxFloors())) {
            score += 0.2;
        }

        // 对称性匹配（额外加分）
        if ("strong".equalsIgnoreCase(characteristics.symmetry) && 
            preset.profile().symmetry == FacadeRhythmProfile.SymmetryMode.BILATERAL) {
            score += 0.1;
        }

        return score;
    }

    /**
     * 解析风格标签
     */
    private static FacadeRhythmPreset.StyleTag parseStyleTag(String style) {
        if (style == null) {
            return null;
        }
        String lower = style.toLowerCase();
        if (lower.contains("traditional") && lower.contains("chinese")) {
            return FacadeRhythmPreset.StyleTag.TRADITIONAL_CHINESE;
        } else if (lower.contains("modern")) {
            return FacadeRhythmPreset.StyleTag.MODERN;
        } else if (lower.contains("classical")) {
            return FacadeRhythmPreset.StyleTag.CLASSICAL;
        } else if (lower.contains("industrial")) {
            return FacadeRhythmPreset.StyleTag.INDUSTRIAL;
        } else if (lower.contains("palace")) {
            return FacadeRhythmPreset.StyleTag.PALACE;
        } else if (lower.contains("temple")) {
            return FacadeRhythmPreset.StyleTag.TEMPLE;
        }
        return null;
    }

    /**
     * 解析建筑类型标签
     */
    private static FacadeRhythmPreset.BuildingTag parseBuildingTag(String buildingType) {
        if (buildingType == null) {
            return null;
        }
        String lower = buildingType.toLowerCase();
        if (lower.contains("residential")) {
            return FacadeRhythmPreset.BuildingTag.RESIDENTIAL;
        } else if (lower.contains("commercial")) {
            return FacadeRhythmPreset.BuildingTag.COMMERCIAL;
        } else if (lower.contains("temple")) {
            return FacadeRhythmPreset.BuildingTag.TEMPLE;
        } else if (lower.contains("palace")) {
            return FacadeRhythmPreset.BuildingTag.PALACE;
        } else if (lower.contains("industrial")) {
            return FacadeRhythmPreset.BuildingTag.INDUSTRIAL;
        }
        return null;
    }

    /**
     * 默认选择（fallback）
     */
    private static PresetSelection defaultSelection() {
        return new PresetSelection(
                "RESIDENTIAL_REGULAR",
                null,
                null,
                null
        );
    }

    /**
     * 带评分的预设
     */
    private static class ScoredPreset {
        final FacadeRhythmPreset preset;
        final double score;

        ScoredPreset(FacadeRhythmPreset preset, double score) {
            this.preset = preset;
            this.score = score;
        }
    }
}
