package com.formacraft.common.component;

import com.formacraft.common.component.placement.ComponentPlacementSpec;
import com.formacraft.common.component.socket.ComponentSocket;
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
        /** 可选：缩略图文件名，例如 door_xxx.png（用于 UI 预览；v1 为简易缩略图）。 */
        public String thumbnail;
        /** 可选：最后保存时间（epoch ms），用于“最近保存”排序。 */
        public Long updatedAtMs;
        /** 可选：该构件定义的 sockets（用于 LLM mount / 自动开洞）。 */
        public List<ComponentSocket> sockets;
        /** 可选：语义放置规格（Attachment / Context / FacingPolicy / Constraints）。 */
        public ComponentPlacementSpec placementSpec;
    }
}

