package com.formacraft.common.component.model;

/**
 * Instance（实例）v1：实际放在世界里的一个构件实例。
 * <p>
 * v1 作为“记忆/撤销/追踪”的最小骨架；applied_patch 的 hash/统计后续可填充。
 */
public class ComponentInstance {
    public String schema = "formacraft.component.instance.v1";

    public String uuid;
    public String prototype_id;
    public String variant_id;

    public WorldRef world;
    public AppliedPatch applied_patch;
    public Relations relations;

    public static class WorldRef {
        /** 例如 "minecraft:overworld" */
        public String dimension;
        /** [x,y,z] */
        public int[] anchor;
        /** "NORTH"/"SOUTH"/"EAST"/"WEST" */
        public String facing;
    }

    public static class AppliedPatch {
        public int[] origin;
        public String patch_hash;
        public int block_count;
    }

    public static class Relations {
        public String belongs_to_building;
        public String socket_id;
    }
}

