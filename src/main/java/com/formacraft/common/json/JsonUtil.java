package com.formacraft.common.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

/**
 * 统一的 JSON 工具类
 * 支持 Fabric mod 完全兼容，使用 Gson 进行序列化/反序列化
 */
public class JsonUtil {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create();

    /**
     * 将对象转换为 JSON 字符串
     * @param obj 要序列化的对象
     * @return JSON 字符串
     */
    public static String toJson(Object obj) {
        if (obj == null) return "{}";
        try {
            return GSON.toJson(obj);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize to JSON", e);
        }
    }

    /**
     * 将 JSON 字符串转换为指定类型的对象
     * @param json JSON 字符串
     * @param clazz 目标类型
     * @param <T> 泛型类型
     * @return 解析后的对象
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) {
            throw new JsonParseException("JSON string is null or empty");
        }
        try {
            return GSON.fromJson(json, clazz);
        } catch (JsonParseException e) {
            throw new RuntimeException("Failed to parse " + clazz.getSimpleName() + " from JSON: " + json, e);
        }
    }

    /**
     * 获取 Gson 实例（用于高级用法）
     * @return Gson 实例
     */
    public static Gson get() {
        return GSON;
    }
}

