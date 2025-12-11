package com.formacraft.config;

import com.formacraft.common.json.JsonUtil;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * FormaCraft 设置的全局配置（持久化到 JSON）
 */
public class SettingsConfig {

    private static final File CONFIG_FILE = new File("config/formacraft_settings.json");

    // === 配置字段 ===
    public String apiKey = "";
    public String model = "gpt-4o-mini";  // 默认模型
    public float temperature = 0.4f;       // 默认温度
    public int fontSize = 10;              // 默认字体大小（相对偏移）

    // === 单例 ===
    public static SettingsConfig INSTANCE = new SettingsConfig();

    public static void load() {
        try {
            if (!CONFIG_FILE.exists()) {
                save(); // 创建默认配置
                return;
            }
            FileReader reader = new FileReader(CONFIG_FILE);
            INSTANCE = JsonUtil.get().fromJson(reader, SettingsConfig.class);
            reader.close();
            
            // 确保字段不为 null
            if (INSTANCE.apiKey == null) INSTANCE.apiKey = "";
            if (INSTANCE.model == null) INSTANCE.model = "gpt-4o-mini";
        } catch (Exception e) {
            System.err.println("[FormaCraft] Failed to load settings: " + e.getMessage());
            INSTANCE = new SettingsConfig();
        }
    }

    public static void save() {
        try {
            CONFIG_FILE.getParentFile().mkdirs();
            FileWriter writer = new FileWriter(CONFIG_FILE);
            JsonUtil.get().toJson(INSTANCE, writer);
            writer.close();
        } catch (Exception e) {
            System.err.println("[FormaCraft] Failed to save settings: " + e.getMessage());
        }
    }
}

