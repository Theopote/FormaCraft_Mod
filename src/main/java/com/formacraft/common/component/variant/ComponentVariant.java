package com.formacraft.common.component.variant;

import com.formacraft.common.component.ComponentDefinition;

/**
 * ComponentVariant（构件变体）：运行时产物，不存盘。
 * <p>
 * 核心思想：
 * - Variant 不应该被存盘
 * - Variant 是运行时产物
 * - 基于 ComponentDefinition（Archetype）生成
 * <p>
 * Variant 不改变：
 * - AttachmentType
 * - FacingPolicy
 * - Socket 类型
 * <p>
 * Variant 只影响：
 * - 尺寸
 * - 材质
 * - 局部形体
 */
public final class ComponentVariant {
    /** 基础构件定义（Archetype） */
    public final ComponentDefinition base;

    /** X 轴缩放比例 */
    public float scaleX = 1.0f;

    /** Y 轴缩放比例 */
    public float scaleY = 1.0f;

    /** Z 轴缩放比例 */
    public float scaleZ = 1.0f;

    /** 是否镜像 */
    public boolean mirrored = false;

    /** 镜像轴（X 或 Z） */
    public ComponentVariantSpec.Axis mirrorAxis = ComponentVariantSpec.Axis.X;

    /** 分段重复次数（0 = 不重复） */
    public int repeatCount = 0;

    /** 重复轴 */
    public ComponentVariantSpec.Axis repeatAxis = ComponentVariantSpec.Axis.X;

    /** 材质语义映射（例如：WALL_PRIMARY → stone_bricks） */
    public String materialSemantic;

    /** 裁剪后的尺寸（如果应用了裁剪） */
    public Integer trimmedWidth;
    public Integer trimmedHeight;
    public Integer trimmedDepth;

    /**
     * 创建变体
     */
    public ComponentVariant(ComponentDefinition base) {
        this.base = base;
    }

    /**
     * 应用缩放
     */
    public void applyScale(float scale, AxisScalePolicy policy) {
        switch (policy) {
            case NONE -> {
                // 不缩放
            }
            case UNIFORM -> {
                this.scaleX = scale;
                this.scaleY = scale;
                this.scaleZ = scale;
            }
            case XZ -> {
                this.scaleX = scale;
                this.scaleZ = scale;
                // Y 轴不变
            }
            case XYZ -> {
                // 独立缩放（简化处理：使用相同比例）
                this.scaleX = scale;
                this.scaleY = scale;
                this.scaleZ = scale;
            }
        }
    }

    /**
     * 应用镜像
     */
    public void applyMirror(ComponentVariantSpec.Axis axis) {
        this.mirrored = true;
        this.mirrorAxis = axis;
    }

    /**
     * 应用分段重复
     */
    public void applyRepeat(ComponentVariantSpec.Axis axis, int count) {
        this.repeatCount = count;
        this.repeatAxis = axis;
    }

    /**
     * 应用裁剪
     */
    public void applyTrim(int width, int height, int depth) {
        this.trimmedWidth = width;
        this.trimmedHeight = height;
        this.trimmedDepth = depth;
    }

    /**
     * 应用材质语义
     */
    public void applyMaterialSemantic(String semantic) {
        this.materialSemantic = semantic;
    }
}
