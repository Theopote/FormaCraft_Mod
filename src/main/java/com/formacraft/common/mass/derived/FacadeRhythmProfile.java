package com.formacraft.common.mass.derived;

/**
 * FacadeRhythmProfile（立面节奏配置）
 * <p>
 * 🎯 核心定位（架构校准 2026-01-14）：
 * 立面节奏系统 = 决定"哪些 Socket 应该出现、对齐、重复、跳过"的规则层。
 * <p>
 * 它不负责：
 * - 门窗长什么样
 * - 用什么方块
 * <p>
 * 它只负责：
 * - 出现在哪里
 * - 出现的频率
 * - 是否对齐 / 对称 / 变化
 */
public class FacadeRhythmProfile {
    /** 节奏模式 */
    public final RhythmMode mode;

    /** 水平节奏（block） */
    public final int spacing;

    /** 对齐方式 */
    public final AlignmentMode align;

    /** 对称规则 */
    public final SymmetryMode symmetry;

    /** 变化模式 */
    public final VariationMode variation;

    public FacadeRhythmProfile(
            RhythmMode mode,
            int spacing,
            AlignmentMode align,
            SymmetryMode symmetry,
            VariationMode variation
    ) {
        this.mode = mode != null ? mode : RhythmMode.REGULAR;
        this.spacing = spacing > 0 ? spacing : 3;
        this.align = align != null ? align : AlignmentMode.NONE;
        this.symmetry = symmetry != null ? symmetry : SymmetryMode.NONE;
        this.variation = variation != null ? variation : VariationMode.NONE;
    }

    /**
     * 创建默认的立面节奏配置
     */
    public static FacadeRhythmProfile defaultProfile() {
        return new FacadeRhythmProfile(
                RhythmMode.REGULAR,
                3,
                AlignmentMode.NONE,
                SymmetryMode.NONE,
                VariationMode.NONE
        );
    }

    /**
     * 创建 Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder 用于创建 FacadeRhythmProfile
     */
    public static class Builder {
        private RhythmMode mode = RhythmMode.REGULAR;
        private int spacing = 3;
        private AlignmentMode align = AlignmentMode.NONE;
        private SymmetryMode symmetry = SymmetryMode.NONE;
        private VariationMode variation = VariationMode.NONE;

        public Builder rhythmMode(RhythmMode mode) {
            this.mode = mode;
            return this;
        }

        public Builder spacing(int spacing) {
            this.spacing = spacing;
            return this;
        }

        public Builder alignmentMode(AlignmentMode align) {
            this.align = align;
            return this;
        }

        public Builder symmetryMode(SymmetryMode symmetry) {
            this.symmetry = symmetry;
            return this;
        }

        public Builder variationMode(VariationMode variation) {
            this.variation = variation;
            return this;
        }

        public FacadeRhythmProfile build() {
            return new FacadeRhythmProfile(mode, spacing, align, symmetry, variation);
        }
    }

    /**
     * 节奏模式
     */
    public enum RhythmMode {
        /** 等距 */
        REGULAR,
        /** 成组（2-1-2） */
        GROUPED,
        /** 主次节奏 */
        HIERARCHICAL,
        /** 自由（谨慎） */
        FREE
    }

    /**
     * 对齐方式
     */
    public enum AlignmentMode {
        /** 不对齐 */
        NONE,
        /** 对齐主轴 */
        AXIS_ALIGNED,
        /** 对齐边界 */
        EDGE_ALIGNED,
        /** 居中 */
        CENTERED
    }

    /**
     * 对称规则
     */
    public enum SymmetryMode {
        /** 不对称 */
        NONE,
        /** 左右对称 */
        BILATERAL,
        /** 围绕中心（v2） */
        RADIAL
    }

    /**
     * 变化模式
     */
    public enum VariationMode {
        /** 无变化 */
        NONE,
        /** 小幅偏移（±1 block） */
        SMALL_SHIFT,
        /** 随机跳过少量 */
        SKIP_RANDOM
    }
}
