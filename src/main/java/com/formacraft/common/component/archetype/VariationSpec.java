package com.formacraft.common.component.archetype;

/**
 * VariationSpec（变形规格）：定义"AI 可以改哪里"的白名单。
 * <p>
 * 这是"可变但不乱变"的核心。
 */
public class VariationSpec {
    /**
     * X 轴缩放规则
     */
    public AxisScaleRule scaleX = AxisScaleRule.createDefault();

    /**
     * Y 轴缩放规则
     */
    public AxisScaleRule scaleY = AxisScaleRule.createDefault();

    /**
     * Z 轴缩放规则
     */
    public AxisScaleRule scaleZ = AxisScaleRule.createDefault();

    /**
     * 是否允许镜像
     */
    public boolean allowMirror = true;

    /**
     * 是否允许旋转
     */
    public boolean allowRotation = false;

    /**
     * 是否允许材质替换
     */
    public boolean allowMaterialSwap = true;

    /**
     * 重复规则
     */
    public RepeatRule repeatRule = RepeatRule.disabled();

    /**
     * 创建门的变形规格
     */
    public static VariationSpec forDoor() {
        VariationSpec spec = new VariationSpec();
        spec.scaleX = AxisScaleRule.scalable(0.8f, 1.5f);  // 宽度可变
        spec.scaleY = AxisScaleRule.scalable(1.8f, 3.0f);  // 高度可变
        spec.scaleZ = AxisScaleRule.locked();              // 厚度锁定
        spec.allowMirror = true;
        spec.allowRotation = false;
        spec.allowMaterialSwap = true;
        spec.repeatRule = RepeatRule.disabled();
        return spec;
    }

    /**
     * 创建窗的变形规格
     */
    public static VariationSpec forWindow() {
        VariationSpec spec = new VariationSpec();
        spec.scaleX = AxisScaleRule.scalable(0.8f, 2.0f);  // 宽度可变
        spec.scaleY = AxisScaleRule.scalable(1.0f, 2.5f);  // 高度可变
        spec.scaleZ = AxisScaleRule.locked();              // 厚度锁定
        spec.allowMirror = true;
        spec.allowRotation = false;
        spec.allowMaterialSwap = true;
        spec.repeatRule = RepeatRule.disabled();
        return spec;
    }

    /**
     * 创建栏杆的变形规格
     */
    public static VariationSpec forRailing() {
        VariationSpec spec = new VariationSpec();
        spec.scaleX = AxisScaleRule.scalable(1.0f, 10.0f); // X 轴可拉伸
        spec.scaleY = AxisScaleRule.locked();              // 高度锁定
        spec.scaleZ = AxisScaleRule.locked();              // 深度锁定
        spec.allowMirror = true;
        spec.allowRotation = false;
        spec.allowMaterialSwap = true;
        spec.repeatRule = RepeatRule.forRailing();         // 允许 X 轴重复
        return spec;
    }

    /**
     * 创建柱的变形规格
     */
    public static VariationSpec forColumn() {
        VariationSpec spec = new VariationSpec();
        spec.scaleX = AxisScaleRule.scalable(0.8f, 1.5f);  // 半径可变
        spec.scaleY = AxisScaleRule.scalable(1.0f, 5.0f);  // 高度可变
        spec.scaleZ = AxisScaleRule.scalable(0.8f, 1.5f);  // 深度可变（方形柱）
        spec.allowMirror = false;
        spec.allowRotation = true;
        spec.allowMaterialSwap = true;
        spec.repeatRule = RepeatRule.disabled();
        return spec;
    }
}
