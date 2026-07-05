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

    /**
     * LLM Provider（优先级：请求覆盖 > 本地配置 > 后端环境变量）
     * 推荐值：auto / deepseek / openai / openai_compat / ollama
     */
    public String llmProvider = "auto";
    /**
     * LLM Base URL（用于 OpenAI-compatible 服务）。
     * 例如：DeepSeek=<a href="https://api.deepseek.com/v1">...</a>，OpenAI=<a href="https://api.openai.com/v1">...</a>，Ollama=<a href="http://localhost:11434/v1">...</a>
     */
    public String llmBaseUrl = "";
    public float temperature = 0.7f;       // 默认温度
    public int fontSize = 14;              // 默认字体大小（用于 UI 缩放基准）
    /** 系统光标与世界交互的最远距离（用于光标 RayCast / hover 选中框） */
    public int interactionReach = 80;      // 默认 80（范围 5~100）

    /**
     * Python 后端（Orchestrator）地址（不含末尾路径时默认拼 /build）
     * 例如：<a href="http://localhost:8000">...</a>
     */
    public String orchestratorEndpoint = "http://localhost:8000";

    /** 是否随游戏启动自动拉起本地 Python 后端（仅对 localhost 生效）。 */
    public boolean autoStartBackend = true;

    /**
     * 调试：在聊天面板中显示后端返回的 debugWarnings（例如 LLM 输出纠错/回退信息）。
     * 默认关闭，避免打扰普通玩家。
     */
    public boolean showDebugWarnings = false;
    /**
     * Python 可执行文件（可为空，表示使用 "python"）。
     * Windows 示例：C:\\Program Files\\Python313\\python.exe
     */
    public String pythonExecutable = "";
    /** 后端工作目录（相对游戏启动目录），默认 python_backend */
    public String backendWorkDir = "python_backend";
    /** Uvicorn 端口（默认 8000） */
    public int backendPort = 8000;

    /**
     * 建筑研究网络搜索（优先于后端环境变量）。
     * auto | duckduckgo | bing | google_cse | wikipedia_only
     */
    public String searchProvider = "auto";
    /** Bing 或 Google Custom Search API Key */
    public String searchApiKey = "";
    /** Google Custom Search Engine ID（仅 google_cse / auto 使用） */
    public String googleCseCx = "";

    // === 单例 ===
    public static final SettingsConfig INSTANCE = new SettingsConfig();

    /**
     * 重置为默认配置（不会替换 INSTANCE 引用，只重置字段）
     */
    public void resetToDefault() {
        apiKey = "";
        orchestratorEndpoint = "http://localhost:8000";
        model = "gpt-4o";
        llmProvider = "auto";
        llmBaseUrl = "";
        temperature = 0.7f;
        fontSize = 14;
        interactionReach = 80;
        autoStartBackend = true;
        showDebugWarnings = false;
        pythonExecutable = "";
        backendWorkDir = "python_backend";
        backendPort = 8000;
        searchProvider = "auto";
        searchApiKey = "";
        googleCseCx = "";
    }

    private void copyFrom(SettingsConfig other) {
        if (other == null) return;
        this.apiKey = other.apiKey != null ? other.apiKey : "";
        this.model = other.model != null ? other.model : "gpt-4o";
        this.llmProvider = other.llmProvider != null ? other.llmProvider : "auto";
        this.llmBaseUrl = other.llmBaseUrl != null ? other.llmBaseUrl : "";
        this.temperature = other.temperature;
        this.fontSize = other.fontSize;
        this.orchestratorEndpoint = other.orchestratorEndpoint != null ? other.orchestratorEndpoint : "http://localhost:8000";
        this.interactionReach = other.interactionReach;
        this.autoStartBackend = other.autoStartBackend;
        this.showDebugWarnings = other.showDebugWarnings;
        this.pythonExecutable = other.pythonExecutable != null ? other.pythonExecutable : "";
        this.backendWorkDir = other.backendWorkDir != null ? other.backendWorkDir : "python_backend";
        this.backendPort = other.backendPort > 0 ? other.backendPort : 8000;
        this.searchProvider = other.searchProvider != null ? other.searchProvider : "auto";
        this.searchApiKey = other.searchApiKey != null ? other.searchApiKey : "";
        this.googleCseCx = other.googleCseCx != null ? other.googleCseCx : "";
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
            if (INSTANCE.interactionReach < 5) INSTANCE.interactionReach = 5;
            if (INSTANCE.interactionReach > 100) INSTANCE.interactionReach = 100;
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

