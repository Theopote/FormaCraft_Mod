package com.formacraft.common.mass.rhythm;

import com.formacraft.common.mass.derived.FacadeRhythmProfile;
import com.formacraft.FormacraftMod;

import java.util.*;

/**
 * FacadeRhythmPresetLibrary（立面节奏预设库）
 * <p>
 * 🎯 核心职责：
 * 提供一组已验证的节奏预设，AI 只能从这些预设中选择，不能生成新的。
 * <p>
 * 这是防止 Formacraft 走偏的第一道、也是最重要的一道闸门。
 */
public final class FacadeRhythmPresetLibrary {

    private static final Map<String, FacadeRhythmPreset> PRESETS = new HashMap<>();
    private static boolean initialized = false;

    private FacadeRhythmPresetLibrary() {}

    /**
     * 初始化预设库
     */
    public static void initialize() {
        if (initialized) {
            return;
        }

        // 注册所有预设
        registerPresets();

        initialized = true;
        FormacraftMod.LOGGER.info("FacadeRhythmPresetLibrary: Initialized {} presets", PRESETS.size());
    }

    /**
     * 注册所有预设
     */
    private static void registerPresets() {
        // RESIDENTIAL_REGULAR：住宅常规节奏
        register(new FacadeRhythmPreset(
                "RESIDENTIAL_REGULAR",
                FacadeRhythmProfile.builder()
                        .rhythmMode(FacadeRhythmProfile.RhythmMode.REGULAR)
                        .alignmentMode(FacadeRhythmProfile.AlignmentMode.NONE)
                        .symmetryMode(FacadeRhythmProfile.SymmetryMode.NONE)
                        .variationMode(FacadeRhythmProfile.VariationMode.NONE)
                        .build(),
                Set.of(FacadeRhythmPreset.StyleTag.RESIDENTIAL, FacadeRhythmPreset.StyleTag.MODERN),
                Set.of(FacadeRhythmPreset.BuildingTag.RESIDENTIAL),
                1, 6, // 1-6 层
                new FacadeRhythmPreset.TuningRange(
                        new FacadeRhythmPreset.IntRange(2, 5), // 间距 2-5
                        EnumSet.of(FacadeRhythmProfile.VariationMode.NONE, FacadeRhythmProfile.VariationMode.SMALL_SHIFT),
                        EnumSet.of(FacadeRhythmProfile.AlignmentMode.NONE, FacadeRhythmProfile.AlignmentMode.EDGE_ALIGNED)
                )
        ));

        // RESIDENTIAL_GROUPED：住宅分组节奏
        register(new FacadeRhythmPreset(
                "RESIDENTIAL_GROUPED",
                FacadeRhythmProfile.builder()
                        .rhythmMode(FacadeRhythmProfile.RhythmMode.GROUPED)
                        .alignmentMode(FacadeRhythmProfile.AlignmentMode.EDGE_ALIGNED)
                        .symmetryMode(FacadeRhythmProfile.SymmetryMode.BILATERAL)
                        .variationMode(FacadeRhythmProfile.VariationMode.SMALL_SHIFT)
                        .build(),
                Set.of(FacadeRhythmPreset.StyleTag.RESIDENTIAL),
                Set.of(FacadeRhythmPreset.BuildingTag.RESIDENTIAL),
                2, 4, // 2-4 层
                new FacadeRhythmPreset.TuningRange(
                        new FacadeRhythmPreset.IntRange(3, 6),
                        EnumSet.of(FacadeRhythmProfile.VariationMode.SMALL_SHIFT),
                        EnumSet.of(FacadeRhythmProfile.AlignmentMode.EDGE_ALIGNED, FacadeRhythmProfile.AlignmentMode.CENTERED)
                )
        ));

        // PALACE_HIERARCHICAL：宫殿层次节奏
        register(new FacadeRhythmPreset(
                "PALACE_HIERARCHICAL",
                FacadeRhythmProfile.builder()
                        .rhythmMode(FacadeRhythmProfile.RhythmMode.HIERARCHICAL)
                        .alignmentMode(FacadeRhythmProfile.AlignmentMode.AXIS_ALIGNED)
                        .symmetryMode(FacadeRhythmProfile.SymmetryMode.BILATERAL)
                        .variationMode(FacadeRhythmProfile.VariationMode.NONE)
                        .build(),
                Set.of(FacadeRhythmPreset.StyleTag.PALACE, FacadeRhythmPreset.StyleTag.TRADITIONAL_CHINESE),
                Set.of(FacadeRhythmPreset.BuildingTag.PALACE),
                3, -1, // 3 层以上
                new FacadeRhythmPreset.TuningRange(
                        new FacadeRhythmPreset.IntRange(3, 8),
                        EnumSet.of(FacadeRhythmProfile.VariationMode.NONE),
                        EnumSet.of(FacadeRhythmProfile.AlignmentMode.AXIS_ALIGNED, FacadeRhythmProfile.AlignmentMode.CENTERED)
                )
        ));

        // TEMPLE_AXIAL：寺庙轴向节奏
        register(new FacadeRhythmPreset(
                "TEMPLE_AXIAL",
                FacadeRhythmProfile.builder()
                        .rhythmMode(FacadeRhythmProfile.RhythmMode.REGULAR)
                        .alignmentMode(FacadeRhythmProfile.AlignmentMode.AXIS_ALIGNED)
                        .symmetryMode(FacadeRhythmProfile.SymmetryMode.BILATERAL)
                        .variationMode(FacadeRhythmProfile.VariationMode.NONE)
                        .build(),
                Set.of(FacadeRhythmPreset.StyleTag.TEMPLE, FacadeRhythmPreset.StyleTag.TRADITIONAL_CHINESE),
                Set.of(FacadeRhythmPreset.BuildingTag.TEMPLE),
                2, -1, // 2 层以上
                new FacadeRhythmPreset.TuningRange(
                        new FacadeRhythmPreset.IntRange(3, 6),
                        EnumSet.of(FacadeRhythmProfile.VariationMode.NONE),
                        EnumSet.of(FacadeRhythmProfile.AlignmentMode.AXIS_ALIGNED)
                )
        ));

        // INDUSTRIAL_GRID：工业网格节奏
        register(new FacadeRhythmPreset(
                "INDUSTRIAL_GRID",
                FacadeRhythmProfile.builder()
                        .rhythmMode(FacadeRhythmProfile.RhythmMode.REGULAR)
                        .alignmentMode(FacadeRhythmProfile.AlignmentMode.EDGE_ALIGNED)
                        .symmetryMode(FacadeRhythmProfile.SymmetryMode.NONE)
                        .variationMode(FacadeRhythmProfile.VariationMode.NONE)
                        .build(),
                Set.of(FacadeRhythmPreset.StyleTag.INDUSTRIAL),
                Set.of(FacadeRhythmPreset.BuildingTag.INDUSTRIAL),
                1, -1, // 任意层数
                new FacadeRhythmPreset.TuningRange(
                        new FacadeRhythmPreset.IntRange(2, 4),
                        EnumSet.of(FacadeRhythmProfile.VariationMode.NONE),
                        EnumSet.of(FacadeRhythmProfile.AlignmentMode.EDGE_ALIGNED)
                )
        ));
    }

    /**
     * 注册一个预设
     */
    public static void register(FacadeRhythmPreset preset) {
        if (preset == null || preset.id() == null || preset.id().isEmpty()) {
            throw new IllegalArgumentException("Preset and preset.id must not be null or empty");
        }
        PRESETS.put(preset.id(), preset);
    }

    /**
     * 获取所有预设
     */
    public static Collection<FacadeRhythmPreset> getAllPresets() {
        ensureInitialized();
        return Collections.unmodifiableCollection(PRESETS.values());
    }

    /**
     * 根据 ID 获取预设
     */
    public static FacadeRhythmPreset getPreset(String id) {
        ensureInitialized();
        return PRESETS.get(id);
    }

    /**
     * 根据建筑特征查找匹配的预设
     */
    public static List<FacadeRhythmPreset> findMatchingPresets(
            int floorCount,
            FacadeRhythmPreset.StyleTag style,
            FacadeRhythmPreset.BuildingTag buildingType
    ) {
        ensureInitialized();
        List<FacadeRhythmPreset> matches = new ArrayList<>();
        for (FacadeRhythmPreset preset : PRESETS.values()) {
            if (preset.matches(floorCount, style, buildingType)) {
                matches.add(preset);
            }
        }
        return matches;
    }

    /**
     * 确保已初始化
     */
    private static void ensureInitialized() {
        if (!initialized) {
            initialize();
        }
    }
}
