package com.formacraft.common.component.archetype;

/**
 * GeometryHint（几何提示）：专门给 AI / Generator 的形态提示。
 * <p>
 * 这不是强约束，而是"生成风格提示"，用于 PromptAssembler。
 */
public class GeometryHint {
    /**
     * 几何原型类型
     */
    public GeometryArchetype archetype = GeometryArchetype.FLAT_PANEL;

    /**
     * 视觉识别性描述（给 AI 的提示）
     * 例如："arched opening", "heavy vertical support", "decorative bracket"
     */
    public String visualIdentity;

    /**
     * 是否偏好对称
     */
    public boolean symmetryPreferred = false;

    /**
     * 创建门的几何提示
     */
    public static GeometryHint forDoor() {
        GeometryHint hint = new GeometryHint();
        hint.archetype = GeometryArchetype.FRAME;
        hint.visualIdentity = "vertical door opening";
        hint.symmetryPreferred = true;
        return hint;
    }

    /**
     * 创建窗的几何提示
     */
    public static GeometryHint forWindow() {
        GeometryHint hint = new GeometryHint();
        hint.archetype = GeometryArchetype.FRAME;
        hint.visualIdentity = "window opening";
        hint.symmetryPreferred = true;
        return hint;
    }

    /**
     * 创建柱的几何提示
     */
    public static GeometryHint forColumn() {
        GeometryHint hint = new GeometryHint();
        hint.archetype = GeometryArchetype.COLUMN;
        hint.visualIdentity = "vertical support column";
        hint.symmetryPreferred = true;
        return hint;
    }

    /**
     * 创建栏杆的几何提示
     */
    public static GeometryHint forRailing() {
        GeometryHint hint = new GeometryHint();
        hint.archetype = GeometryArchetype.LINEAR;
        hint.visualIdentity = "horizontal railing";
        hint.symmetryPreferred = false;
        return hint;
    }
}
