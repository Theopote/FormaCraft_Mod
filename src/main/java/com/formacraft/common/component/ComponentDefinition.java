package com.formacraft.common.component;

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

    public List<BlockEntry> blocks;

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

