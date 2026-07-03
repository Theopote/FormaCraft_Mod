package com.formacraft.common.component.model;

/**
 * 持久化变体文档（存盘 schema）：在不改变 prototype 身份的前提下，对参数做"规则内变化"。
 * <p>
 * 对应 JSON schema {@code formacraft.component.variant.v1}，写入 prototype 目录下的 variant 文件。
 * 运行时产物见 {@link com.formacraft.common.component.variant.ComponentVariant}。
 * <p>
 * v1 仅定义数据结构；非等比拉伸（分段 repeat/trim）在 compile 阶段由 {@link com.formacraft.common.component.variant.ComponentVariantCompiler} 处理。
 */
public class PersistedComponentVariant {
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
