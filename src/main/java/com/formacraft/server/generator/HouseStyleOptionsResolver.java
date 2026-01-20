package com.formacraft.server.generator;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.StyleGenome;

/**
 * 房屋风格选项解析器
 * <p>
 * 负责从 BuildingSpec、StyleGenome、StyleProfile 中解析各种风格选项。
 * 解析优先级：StyleOptions（显式）> genome.params > styleProfile.rules > 默认值
 * </p>
 */
public final class HouseStyleOptionsResolver {

    private HouseStyleOptionsResolver() {
        // 工具类，禁止实例化
    }

    /**
     * 解析门样式
     * <p>
     * 优先级：
     * 1. spec.getStyleOptions().getDoorStyle() (显式指定)
     * 2. genome.params.doorStyle (风格基因)
     * 3. "single" (默认值)
     * </p>
     *
     * @param spec 建筑规范
     * @param genome 风格基因
     * @return 门样式字符串
     */
    public static String resolveDoorStyle(BuildingSpec spec, StyleGenome genome) {
        if (spec.getStyleOptions() != null && spec.getStyleOptions().getDoorStyle() != null) {
            return spec.getStyleOptions().getDoorStyle();
        }
        if (genome != null && genome.params != null && genome.params.doorStyle != null) {
            return genome.params.doorStyle;
        }
        return "single";
    }

    /**
     * 解析屋顶类型
     * <p>
     * 优先级：
     * 1. spec.getStyleOptions().getRoofType() (显式指定)
     * 2. genome.params.roofType (风格基因)
     * 3. profile.rules.allowFlatRoof == false ? "gable" : "flat" (风格配置文件)
     * 4. "flat" (默认值)
     * </p>
     *
     * @param spec 建筑规范
     * @param genome 风格基因
     * @param profile 风格配置文件
     * @param style 建筑风格
     * @return 屋顶类型字符串
     */
    public static String resolveRoofType(BuildingSpec spec, StyleGenome genome, StyleProfile profile, BuildingStyle style) {
        if (spec.getStyleOptions() != null && spec.getStyleOptions().getRoofType() != null) {
            return spec.getStyleOptions().getRoofType();
        }
        if (genome != null && genome.params != null && genome.params.roofType != null) {
            return genome.params.roofType;
        }
        if (profile != null && profile.rules() != null && !profile.rules().allowFlatRoof) {
            return "gable";
        }
        return "flat";
    }

    /**
     * 解析窗户比例
     * <p>
     * 优先级：
     * 1. spec.getStyleOptions().getWindowRatio() (显式指定)
     * 2. genome.params.windowDensity (风格基因)
     * 3. genome.params.windowRatio (风格基因，备选)
     * 4. profile.rules.windowDensity (风格配置文件)
     * 5. 0.3 (默认值)
     * </p>
     * <p>
     * 然后根据 windowStyle 提示进行调整：
     * - curtain_wall: 提高至至少 0.70
     * - slit/bars: 降低至最多 0.14
     * - shoji: 限制在 0.32-0.55
     * - fence/lattice: 限制在 0.22-0.45
     * </p>
     *
     * @param spec 建筑规范
     * @param genome 风格基因
     * @param profile 风格配置文件
     * @param style 建筑风格
     * @return 窗户比例 (0.0-1.0)
     */
    public static double resolveWindowRatio(BuildingSpec spec, StyleGenome genome, StyleProfile profile, BuildingStyle style) {
        double windowRatio;
        if (spec.getStyleOptions() != null) {
            windowRatio = spec.getStyleOptions().getWindowRatio();
        } else if (genome != null && genome.params != null && genome.params.windowDensity != null) {
            windowRatio = genome.params.windowDensity;
        } else if (genome != null && genome.params != null && genome.params.windowRatio != null) {
            windowRatio = genome.params.windowRatio;
        } else if (profile != null && profile.rules() != null) {
            windowRatio = profile.rules().windowDensity;
        } else {
            windowRatio = 0.3;
        }
        windowRatio = Math.max(0.0, Math.min(1.0, windowRatio));

        // Apply windowStyle hints (broad, cross-style)
        String effWindowStyle = HouseMaterialResolver.resolveEffectiveWindowStyle(spec, genome, profile, style);
        String ews = (effWindowStyle != null) ? effWindowStyle.trim().toLowerCase(java.util.Locale.ROOT) : "";
        if (!ews.isBlank()) {
            if (ews.contains("curtain")) {
                windowRatio = Math.max(windowRatio, 0.70);
            } else if (ews.contains("slit") || ews.contains("bars")) {
                windowRatio = Math.min(windowRatio, 0.14);
            } else if (ews.contains("shoji")) {
                windowRatio = Math.max(Math.min(windowRatio, 0.55), 0.32);
            } else if (ews.contains("fence") || ews.contains("lattice")) {
                windowRatio = Math.max(Math.min(windowRatio, 0.45), 0.22);
            }
        }

        return windowRatio;
    }

    /**
     * 解析墙体花纹
     * <p>
     * 优先级：
     * 1. spec.getStyleOptions().getWallPattern() (显式指定)
     * 2. genome.params.wallPattern (风格基因)
     * 3. "uniform" (默认值)
     * </p>
     *
     * @param spec 建筑规范
     * @param genome 风格基因
     * @return 墙体花纹字符串
     */
    public static String resolveWallPattern(BuildingSpec spec, StyleGenome genome) {
        if (spec.getStyleOptions() != null && spec.getStyleOptions().getWallPattern() != null) {
            return spec.getStyleOptions().getWallPattern();
        }
        if (genome != null && genome.params != null && genome.params.wallPattern != null) {
            return genome.params.wallPattern;
        }
        return "uniform";
    }

    /**
     * 解析调色板ID
     * <p>
     * 优先级：
     * 1. spec.getExtra().get("paletteId") (显式指定)
     * 2. profile.details().paletteId (风格配置文件)
     * 3. null (未指定)
     * </p>
     *
     * @param spec 建筑规范
     * @param profile 风格配置文件
     * @return 调色板ID字符串，如果未指定则返回 null
     */
    public static String resolvePaletteId(BuildingSpec spec, StyleProfile profile) {
        String paletteId = null;
        if (spec.getExtra() != null) {
            Object pid = spec.getExtra().get("paletteId");
            if (pid != null) paletteId = String.valueOf(pid).trim();
        }
        if ((paletteId == null || paletteId.isBlank()) && profile != null && profile.details() != null
                && profile.details().paletteId != null && !profile.details().paletteId.isBlank()) {
            paletteId = profile.details().paletteId.trim();
        }
        return paletteId;
    }
}
