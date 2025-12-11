package com.formacraft.common.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

/**
 * JSON 序列化/反序列化工具类
 * 用于 FormaRequest 和 BuildingSpec 的 JSON 转换
 */
public class FormaJson {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    /**
     * 将对象转换为 JSON 字符串
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
     */
    @SuppressWarnings("unchecked")
    public static <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) {
            throw new JsonParseException("JSON string is null or empty");
        }
        try {
            return (T) GSON.fromJson(json, clazz);
        } catch (JsonParseException e) {
            throw new RuntimeException("Failed to parse " + clazz.getSimpleName() + " from JSON: " + json, e);
        }
    }
}

