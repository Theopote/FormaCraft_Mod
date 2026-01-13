package com.formacraft.client.component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 构件库使用统计（纯客户端）：
 * - 记录“最近加载”的时间戳，用于排序
 * - 不影响存储结构/网络协议
 */
public final class ComponentLibraryUsage {
    private ComponentLibraryUsage() {}

    private static final Map<String, Long> LAST_LOADED_MS = new ConcurrentHashMap<>();

    public static void markLoaded(String componentId) {
        if (componentId == null || componentId.isBlank()) return;
        LAST_LOADED_MS.put(componentId.trim(), System.currentTimeMillis());
    }

    public static long getLastLoadedMs(String componentId) {
        if (componentId == null || componentId.isBlank()) return 0L;
        Long v = LAST_LOADED_MS.get(componentId.trim());
        return v != null ? v : 0L;
    }
}

