package com.formacraft.common.mass.rhythm;

import com.formacraft.common.mass.derived.FacadeRhythmProfile;

import java.util.Set;

/**
 * FacadeRhythmPreset（立面节奏预设）
 * <p>
 * 🎯 核心定义：
 * AI 不生成 FacadeRhythmProfile，AI 只在"有限候选集合"中做选择与微调。
 * <p>
 * 这是防止 Formacraft 走偏的第一道、也是最重要的一道闸门。
 * <p>
 * 每个预设都是一个已经验证过"不会翻车"的规则组合。
 */
public record FacadeRhythmPreset(
        /**
         * 预设 ID（唯一标识）
         */
        String id,

        /**
         * 节奏配置（已验证的规则组合）
         */
        FacadeRhythmProfile profile,

        /**
         * 风格标签（匹配哪些建筑风格）
         */
        Set<StyleTag> styleTags,

        /**
         * 建筑类型标签（匹配哪些建筑类型）
         */
        Set<BuildingTag> buildingTags,

        /**
         * 最小层数（此预设适用的最小楼层数）
         */
        int minFloors,

        /**
         * 最大层数（此预设适用的最大楼层数，-1 表示无限制）
         */
        int maxFloors,

        /**
         * 允许的参数微调范围
         */
        TuningRange tuningRange
) {
    /**
     * 检查此预设是否适用于给定的建筑特征
     */
    public boolean matches(int floorCount, StyleTag style, BuildingTag buildingType) {
        // 检查层数范围
        if (floorCount < minFloors() || (maxFloors() > 0 && floorCount > maxFloors())) {
            return false;
        }

        // 检查风格标签
        if (style != null && !styleTags().isEmpty() && !styleTags().contains(style)) {
            return false;
        }

        // 检查建筑类型标签
        return buildingType == null || buildingTags().isEmpty() || buildingTags().contains(buildingType);
    }

    /**
     * 风格标签
     */
    public enum StyleTag {
        TRADITIONAL_CHINESE,
        MODERN,
        CLASSICAL,
        INDUSTRIAL,
        RESIDENTIAL,
        PALACE,
        TEMPLE,
        COMMERCIAL
    }

    /**
     * 建筑类型标签
     */
    public enum BuildingTag {
        RESIDENTIAL,
        COMMERCIAL,
        TEMPLE,
        PALACE,
        INDUSTRIAL,
        MIXED_USE
    }

    /**
     * 参数微调范围
     * <p>
     * 限制 AI 可以调整的参数范围，防止生成不合理的配置
     */
    public record TuningRange(
            /**
             * 允许的间距范围（min, max）
             */
            IntRange spacing,

            /**
             * 允许的变化模式
             */
            Set<FacadeRhythmProfile.VariationMode> allowedVariations,

            /**
             * 允许的对齐模式
             */
            Set<FacadeRhythmProfile.AlignmentMode> allowedAlignments
    ) {
        /**
         * 检查微调参数是否在允许范围内
         */
        public boolean isValidTuning(FacadeRhythmTuning tuning) {
            if (tuning == null) {
                return true; // 无微调是允许的
            }

            // 检查间距
            if (tuning.spacing() != null) {
                if (spacing != null) {
                    if (tuning.spacing() < spacing.min() || tuning.spacing() > spacing.max()) {
                        return false;
                    }
                }
            }

            // 检查变化模式
            if (tuning.variationMode() != null) {
                if (!allowedVariations.isEmpty() && !allowedVariations.contains(tuning.variationMode())) {
                    return false;
                }
            }

            // 检查对齐模式
            if (tuning.alignmentMode() != null) {
                return allowedAlignments.isEmpty() || allowedAlignments.contains(tuning.alignmentMode());
            }

            return true;
        }
    }

    /**
     * 整数范围
     */
    public record IntRange(int min, int max) {
        public IntRange {
            if (min > max) {
                throw new IllegalArgumentException("min must be <= max");
            }
        }
    }

    /**
     * AI 的微调参数
     */
    public record FacadeRhythmTuning(
            /**
             * 间距调整（可选）
             */
            Integer spacing,

            /**
             * 变化模式调整（可选）
             */
            FacadeRhythmProfile.VariationMode variationMode,

            /**
             * 对齐模式调整（可选）
             */
            FacadeRhythmProfile.AlignmentMode alignmentMode
    ) {}
}
