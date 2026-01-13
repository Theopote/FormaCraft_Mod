package com.formacraft.common.component.model;

import com.formacraft.common.component.ComponentCategory;

import java.util.List;

/**
 * Prototype catalog（索引）v1：避免每次启动扫描 prototypes 目录。
 * <p>
 * 存储位置由 ComponentPrototypeStorage 决定（全局库目录下）。
 */
public class ComponentPrototypeCatalog {
    public String schema = "formacraft.component.prototype_catalog.v1";
    public List<Entry> prototypes;

    public static class Entry {
        public String id;
        public String name;
        public ComponentCategory category = ComponentCategory.GENERIC;
        public List<String> tags;
        /** 最后更新时间（文件 mtime 或 save 时写入，epoch ms） */
        public Long updatedAtMs;
    }
}

