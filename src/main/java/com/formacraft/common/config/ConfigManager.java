package com.formacraft.common.config;

import com.formacraft.FormacraftMod;
import com.formacraft.config.SettingsConfig;

/**
 * 配置管理器 - 负责加载和管理 FormaCraft 的所有配置
 * 目前主要委托给 SettingsConfig，未来可以扩展支持更多配置源
 */
public class ConfigManager {
    /**
     * 加载配置文件
     * 应该在模组初始化时调用（FormacraftMod.onInitialize）
     */
    public static void loadConfig() {
        try {
            SettingsConfig.load();
            FormacraftMod.LOGGER.info("FormaCraft config loaded successfully");
        } catch (Exception e) {
            FormacraftMod.LOGGER.error("Failed to load FormaCraft config", e);
        }
    }
    
    /**
     * 获取 Python 后端地址
     * @return 后端 URL（例如 "http://localhost:8000"）
     */
    public static String getOrchestratorEndpoint() {
        String endpoint = SettingsConfig.INSTANCE.orchestratorEndpoint;
        if (endpoint == null || endpoint.isBlank()) {
            return "http://localhost:8000";
        }
        // 确保 URL 格式正确（去除末尾斜杠）
        return endpoint.trim().replaceAll("/+$", "");
    }
    
    /**
     * 保存配置到文件
     */
    public static void saveConfig() {
        try {
            SettingsConfig.save();
        } catch (Exception e) {
            FormacraftMod.LOGGER.error("Failed to save FormaCraft config", e);
        }
    }
}
