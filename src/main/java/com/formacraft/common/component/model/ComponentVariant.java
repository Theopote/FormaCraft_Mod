package com.formacraft.common.component.model;

/**
 * Variant（变体）v1：在不改变 prototype 身份的前提下，对参数做“规则内变化”。
 * <p>
 * 注意：v1 仅定义数据结构；真正的“非等比拉伸（分段 repeat/trim）”发生在 compileToPatch 阶段。
 */
public class ComponentVariant {
    public String schema = "formacraft.component.variant.v1";

    public String prototype_id;
    public String variant_id;
    public Params params;

    public static class Params {
        public Scale scale;
        /** "NONE" / "X" / "Z"（未来可扩展） */
        public String mirror;
        public String material_set;
        public String ornament_level;

        public static class Scale {
            public int x, y, z;
        }
    }
}

