package com.formacraft.common.component;

import java.util.List;

/**
 * 构件目录索引（v1）。
 * 用于快速列出构件，不必扫描目录。
 */
public class ComponentCatalog {
    public String schema = "formacraft.component_catalog.v1";
    public List<Entry> components;

    public static class Entry {
        public String id;
        public String name;
        public ComponentCategory category;
        public List<String> tags;
        public ComponentDefinition.Size size;
        /** 文件名，例如 door_xxx.json */
        public String file;
    }
}

