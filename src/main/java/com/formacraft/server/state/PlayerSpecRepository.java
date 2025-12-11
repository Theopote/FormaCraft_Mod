package com.formacraft.server.state;

import net.minecraft.server.network.ServerPlayerEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家规格仓库
 * 管理每个玩家当前绑定的 CitySpec 和 BuildingSpec
 */
public class PlayerSpecRepository {
    
    // 城市规格缓存：玩家 UUID -> CitySpec JSON
    private static final Map<UUID, String> currentCityJson = new HashMap<>();
    
    // 城市 ID 缓存：玩家 UUID -> cityId
    private static final Map<UUID, String> currentCityId = new HashMap<>();
    
    // 建筑规格缓存：玩家 UUID -> BuildingSpec JSON
    private static final Map<UUID, String> currentBuildingJson = new HashMap<>();
    
    // 建筑 ID 缓存：玩家 UUID -> buildingId
    private static final Map<UUID, String> currentBuildingId = new HashMap<>();
    
    /**
     * 设置玩家的当前城市规格
     */
    public static void setCitySpec(ServerPlayerEntity player, String cityId, String json) {
        UUID uuid = player.getUuid();
        currentCityId.put(uuid, cityId);
        currentCityJson.put(uuid, json);
    }
    
    /**
     * 获取玩家的当前城市 ID
     */
    public static String getCityId(ServerPlayerEntity player) {
        return currentCityId.get(player.getUuid());
    }
    
    /**
     * 获取玩家的当前城市规格 JSON
     */
    public static String getCityJson(ServerPlayerEntity player) {
        return currentCityJson.get(player.getUuid());
    }
    
    /**
     * 设置玩家的当前建筑规格
     */
    public static void setBuildingSpec(ServerPlayerEntity player, String buildingId, String json) {
        UUID uuid = player.getUuid();
        currentBuildingId.put(uuid, buildingId);
        currentBuildingJson.put(uuid, json);
    }
    
    /**
     * 获取玩家的当前建筑 ID
     */
    public static String getBuildingId(ServerPlayerEntity player) {
        return currentBuildingId.get(player.getUuid());
    }
    
    /**
     * 获取玩家的当前建筑规格 JSON
     */
    public static String getBuildingJson(ServerPlayerEntity player) {
        return currentBuildingJson.get(player.getUuid());
    }
    
    /**
     * 清除玩家的所有规格缓存
     */
    public static void clear(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        currentCityId.remove(uuid);
        currentCityJson.remove(uuid);
        currentBuildingId.remove(uuid);
        currentBuildingJson.remove(uuid);
    }
}

