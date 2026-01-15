package com.formacraft.common.component.variant;

/**
 * ComponentVariantSpec（构件变体规格）：定义"怎么变"。
 * <p>
 * 核心思想：
 * - Variant ≠ Random
 * - Variant = 受控变形
 * - 保持"看起来是同一个建筑师设计的东西"
 */
public final class ComponentVariantSpec {
    /** 是否允许整体缩放 */
    public boolean allowScaling = true;

    /** 允许的轴向缩放策略 */
    public AxisScalePolicy scalePolicy = AxisScalePolicy.XZ;

    /** 尺寸扰动范围（比例） */
    public float scaleMin = 0.8f;
    public float scaleMax = 1.3f;

    /** 是否允许分段重复（例如窗户一排） */
    public boolean allowSegmentRepeat = false;

    /** 可重复轴 */
    public Axis repeatAxis = Axis.X;

    /** 最小重复单元长度 */
    public int repeatUnit = 1;

    /** 是否允许裁剪 */
    public boolean allowTrim = false;

    /** 裁剪容差 */
    public int trimTolerance = 1;

    /** 材质替换规则 */
    public MaterialVariantPolicy materialPolicy = MaterialVariantPolicy.SAME_FAMILY;

    /**
     * 从 ComponentArchetype.VariationSpec 创建 ComponentVariantSpec
     */
    public static ComponentVariantSpec fromArchetype(com.formacraft.common.component.archetype.VariationSpec archetypeVariation) {
        if (archetypeVariation == null) {
            return createDefault();
        }

        ComponentVariantSpec spec = new ComponentVariantSpec();

        // 判断缩放策略
        boolean xScalable = !archetypeVariation.scaleX.locked;
        boolean yScalable = !archetypeVariation.scaleY.locked;
        boolean zScalable = !archetypeVariation.scaleZ.locked;

        if (!xScalable && !yScalable && !zScalable) {
            spec.scalePolicy = AxisScalePolicy.NONE;
            spec.allowScaling = false;
        } else if (xScalable && yScalable && zScalable) {
            spec.scalePolicy = AxisScalePolicy.XYZ;
        } else if (xScalable && zScalable && !yScalable) {
            spec.scalePolicy = AxisScalePolicy.XZ;
        } else {
            spec.scalePolicy = AxisScalePolicy.UNIFORM;
        }

        // 计算缩放范围
        if (xScalable || yScalable || zScalable) {
            float min = Float.MAX_VALUE;
            float max = Float.MIN_VALUE;
            if (xScalable) {
                min = Math.min(min, archetypeVariation.scaleX.min);
                max = Math.max(max, archetypeVariation.scaleX.max);
            }
            if (yScalable) {
                min = Math.min(min, archetypeVariation.scaleY.min);
                max = Math.max(max, archetypeVariation.scaleY.max);
            }
            if (zScalable) {
                min = Math.min(min, archetypeVariation.scaleZ.min);
                max = Math.max(max, archetypeVariation.scaleZ.max);
            }
            spec.scaleMin = min;
            spec.scaleMax = max;
        }

        // 分段重复
        if (archetypeVariation.repeatRule != null && archetypeVariation.repeatRule.enabled) {
            spec.allowSegmentRepeat = true;
            spec.repeatAxis = switch (archetypeVariation.repeatRule.axis) {
                case X -> Axis.X;
                case Y -> Axis.Y;
                case Z -> Axis.Z;
            };
            spec.repeatUnit = archetypeVariation.repeatRule.minSegments;
        }

        // 材质替换
        spec.materialPolicy = archetypeVariation.allowMaterialSwap
                ? MaterialVariantPolicy.SAME_FAMILY
                : MaterialVariantPolicy.NONE;

        return spec;
    }

    /**
     * 创建默认规格（完全固定）
     */
    public static ComponentVariantSpec createDefault() {
        ComponentVariantSpec spec = new ComponentVariantSpec();
        spec.allowScaling = false;
        spec.scalePolicy = AxisScalePolicy.NONE;
        return spec;
    }

    /**
     * 轴向枚举
     */
    public enum Axis {
        X, Y, Z
    }
}
