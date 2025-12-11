package com.formacraft.common.model;

import net.minecraft.util.math.BlockPos;
import java.util.List;
import java.util.Collections;

/**
 * 玩家请求数据结构（Minecraft → Python）
 * 包含玩家信息、请求文本、世界上下文和选择区域
 */
public class FormaRequest {
    public PlayerInfo player;
    public String request;
    public WorldInfo world;
    public SelectionInfo selection;
    public String sessionId;
    public List<String> chatHistory;

    public FormaRequest() {
        this.chatHistory = Collections.emptyList();
    }

    public FormaRequest(PlayerInfo player, String request, WorldInfo world, SelectionInfo selection) {
        this.player = player;
        this.request = request;
        this.world = world;
        this.selection = selection;
        this.chatHistory = Collections.emptyList();
    }

    public static class PlayerInfo {
        public String name;
        public int[] pos; // [x, y, z]
        public String facing; // NORTH, SOUTH, EAST, WEST

        public PlayerInfo() {}

        public PlayerInfo(String name, BlockPos pos, String facing) {
            this.name = name;
            this.pos = new int[]{pos.getX(), pos.getY(), pos.getZ()};
            this.facing = facing;
        }
    }

    public static class WorldInfo {
        public String dimension; // minecraft:overworld
        public String biome; // minecraft:plains

        public WorldInfo() {}

        public WorldInfo(String dimension, String biome) {
            this.dimension = dimension;
            this.biome = biome;
        }
    }

    public static class SelectionInfo {
        public int[] min; // [x, y, z]
        public int[] max; // [x, y, z]

        public SelectionInfo() {}

        public SelectionInfo(BlockPos min, BlockPos max) {
            this.min = new int[]{min.getX(), min.getY(), min.getZ()};
            this.max = new int[]{max.getX(), max.getY(), max.getZ()};
        }
    }
}

