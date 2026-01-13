package com.formacraft.common.component;

import com.formacraft.common.component.placement.ComponentPlacementSpec;
import com.formacraft.common.component.socket.ComponentSocket;
import java.util.List;
import java.util.Set;

/**
 * 单个构件定义（v1）。
 * <p>
 * blocks 坐标系：以 anchor 为原点 (0,0,0)，blocks[].dx/dy/dz 为相对坐标。
 * anchor.facing 表示“构件正面”。
 */
public class ComponentDefinition {
    public String schema = "formacraft.component.v1";

    public String id;
    public String name;
    public ComponentCategory category = ComponentCategory.GENERIC;

    public List<String> tags;

    public Size size;
    public Anchor anchor;
    /** "NORTH"/"SOUTH"/"EAST"/"WEST" */
    public Set<String> allowed_facing;

    public PlacementRules placement_rules;

    /**
     * v1：语义放置规格（Attachment / Context / FacingPolicy / Constraints）。
     * <p>
     * 注意：这是高层语义，低层 blockstate 的 facing 仍由 transform 在落 patch 时推导/修正。
     */
    public ComponentPlacementSpec placementSpec;

    public List<BlockEntry> blocks;

    /** 可选：构件插槽（用于“安装/开洞”）。 */
    public List<ComponentSocket> sockets;

    public static class Size {
        public int w, h, d;
    }

    public static class Anchor {
        /** anchor 本身在构件内部相对坐标（v1 默认 0,0,0） */
        public int dx, dy, dz;
        /** "NORTH"/"SOUTH"/"EAST"/"WEST" */
        public String facing;
    }

    public static class PlacementRules {
        public boolean requires_ground = true;
        public boolean requires_wall = false;
        public boolean allow_mirror = true;
    }

    public static class BlockEntry {
        public int dx, dy, dz;
        /** v1：可选语义部位（用于风格驱动的材质替换；为空则走 block 字符串） */
        public com.formacraft.common.semantic.SemanticPart semantic;
        /** v1：blockstate string（例如 minecraft:spruce_door[facing=south,half=lower]） */
        public String block;
    }
}

