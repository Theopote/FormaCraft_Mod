package com.formacraft.common.blueprint;

import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.blueprint.Blueprint;
import com.google.gson.JsonElement;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 蓝图存储管理器（客户端版本）
 * 负责蓝图的保存、加载、删除和列表
 * 支持两种格式：
 * 1. 直接 BuildingSpec JSON（新格式）
 * 2. Blueprint 包装格式（旧格式，从 Blueprint.data 中提取 BuildingSpec）
 */
public class BlueprintStorage {

    private static final File DIR = new File("formacraft/blueprints");
    private static final Map<String, BuildingSpec> CACHE = new LinkedHashMap<>();

    /**
     * 加载所有蓝图到缓存
     */
    public static void loadAll() {
        CACHE.clear();
        DIR.mkdirs();

        File[] files = DIR.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return;

        for (File f : files) {
            try {
                String name = f.getName().replace(".json", "");
                BuildingSpec spec = loadSpecFromFile(f);
                if (spec != null) {
                    CACHE.put(name, spec);
                }
            } catch (Exception e) {
                System.err.println("[FormaCraft] Failed to load blueprint: " + f.getName() + " - " + e.getMessage());
            }
        }
    }

    /**
     * 从文件读取 BuildingSpec（支持两种格式）
     */
    private static BuildingSpec loadSpecFromFile(File file) {
        try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            // 先尝试作为 Blueprint 格式读取
            Blueprint bp = JsonUtil.get().fromJson(r, Blueprint.class);
            if (bp != null && bp.getData() != null && "BuildingSpec".equals(bp.getType())) {
                // 从 Blueprint.data 中提取 BuildingSpec
                JsonElement data = bp.getData();
                return JsonUtil.get().fromJson(data, BuildingSpec.class);
            }
        } catch (Exception e) {
            // 如果不是 Blueprint 格式，尝试直接读取 BuildingSpec
            try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                return JsonUtil.get().fromJson(r, BuildingSpec.class);
            } catch (Exception e2) {
                // 忽略，返回 null
            }
        }
        return null;
    }

    /**
     * 获取所有蓝图名称列表
     */
    public static List<String> listNames() {
        return new ArrayList<>(CACHE.keySet());
    }

    /**
     * 获取指定名称的蓝图
     */
    public static BuildingSpec get(String name) {
        return CACHE.get(name);
    }

    /**
     * 保存蓝图
     */
    public static void save(String name, BuildingSpec spec) {
        if (name == null || name.isEmpty() || spec == null) {
            return;
        }

        // 清理文件名（防止路径遍历）
        name = sanitizeFileName(name);

        DIR.mkdirs();
        File f = new File(DIR, name + ".json");

        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            JsonUtil.get().toJson(spec, w);
            CACHE.put(name, spec);
        } catch (Exception e) {
            System.err.println("[FormaCraft] Failed to save blueprint: " + name + " - " + e.getMessage());
        }
    }

    /**
     * 删除蓝图
     */
    public static void delete(String name) {
        if (name == null || name.isEmpty()) {
            return;
        }

        name = sanitizeFileName(name);
        File f = new File(DIR, name + ".json");
        if (f.exists()) {
            f.delete();
        }
        CACHE.remove(name);
    }

    /**
     * 清理文件名，防止路径遍历攻击
     */
    private static String sanitizeFileName(String name) {
        // 移除路径分隔符和危险字符
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /**
     * 检查蓝图是否存在
     */
    public static boolean exists(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        name = sanitizeFileName(name);
        return CACHE.containsKey(name);
    }
}

