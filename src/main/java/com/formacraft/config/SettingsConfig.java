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
    public String model = "gpt-4o";        // 默认模型
    public float temperature = 0.7f;       // 默认温度
    public int fontSize = 14;              // 默认字体大小（用于 UI 缩放基准）

    /**
     * Python 后端（Orchestrator）地址（不含末尾路径时默认拼 /build）
     * 例如：http://localhost:8000
     */
    public String orchestratorEndpoint = "http://localhost:8000";

    // === 单例 ===
    public static final SettingsConfig INSTANCE = new SettingsConfig();

    /**
     * 重置为默认配置（不会替换 INSTANCE 引用，只重置字段）
     */
    public void resetToDefault() {
        apiKey = "";
        orchestratorEndpoint = "http://localhost:8000";
        model = "gpt-4o";
        temperature = 0.7f;
        fontSize = 14;
    }

    private void copyFrom(SettingsConfig other) {
        if (other == null) return;
        this.apiKey = other.apiKey != null ? other.apiKey : "";
        this.model = other.model != null ? other.model : "gpt-4o";
        this.temperature = other.temperature;
        this.fontSize = other.fontSize;
        this.orchestratorEndpoint = other.orchestratorEndpoint != null ? other.orchestratorEndpoint : "http://localhost:8000";
    }

    public static void load() {
        try {
            if (!CONFIG_FILE.exists()) {
                INSTANCE.resetToDefault();
                save(); // 创建默认配置
                return;
            }
            FileReader reader = new FileReader(CONFIG_FILE);
            SettingsConfig loaded = JsonUtil.get().fromJson(reader, SettingsConfig.class);
            reader.close();
            
            if (loaded == null) {
                // 解析失败：保留现有 INSTANCE，但确保有默认值
                INSTANCE.resetToDefault();
                return;
            }

            // 只填充字段，不替换 INSTANCE 引用
            INSTANCE.copyFrom(loaded);

            // 基础校验/兜底
            if (INSTANCE.temperature < 0.0f) INSTANCE.temperature = 0.0f;
            if (INSTANCE.temperature > 1.0f) INSTANCE.temperature = 1.0f;
            if (INSTANCE.fontSize < 8) INSTANCE.fontSize = 8;
            if (INSTANCE.fontSize > 26) INSTANCE.fontSize = 26;
        } catch (Exception e) {
            System.err.println("[FormaCraft] Failed to load settings: " + e.getMessage());
            INSTANCE.resetToDefault();
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

