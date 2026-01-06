package com.formacraft.common.style;

import java.util.HashMap;
import java.util.Map;

/**
 * SemanticStyleProfile 注册表
 */
public final class SemanticStyleProfileRegistry {

    private static final Map<String, SemanticStyleProfile> REGISTRY = new HashMap<>();

    private SemanticStyleProfileRegistry() {}

    /**
     * 注册风格配置
     */
    public static void register(SemanticStyleProfile profile) {
        if (profile != null && profile.getId() != null) {
            REGISTRY.put(profile.getId(), profile);
        }
    }

    /**
     * 获取风格配置，如果不存在则返回默认配置
     */
    public static SemanticStyleProfile getOrDefault(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return REGISTRY.get("DEFAULT");
        }
        SemanticStyleProfile profile = REGISTRY.get(profileId.trim());
        if (profile != null) return profile;
        return REGISTRY.get("DEFAULT");
    }

    /**
     * 获取风格配置（不返回默认值）
     */
    public static SemanticStyleProfile get(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return null;
        }
        return REGISTRY.get(profileId.trim());
    }
}

