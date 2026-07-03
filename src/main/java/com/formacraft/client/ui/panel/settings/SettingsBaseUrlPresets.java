package com.formacraft.client.ui.panel.settings;

import java.util.List;

/** LLM Base URL presets for the settings panel. */
public final class SettingsBaseUrlPresets {
    public record Preset(String id, String label, String url) {}

    public static final List<Preset> ALL = List.of(
            new Preset("auto", "自动（由 Provider 决定）", ""),
            new Preset("openai", "OpenAI", "https://api.openai.com/v1"),
            new Preset("deepseek", "DeepSeek", "https://api.deepseek.com/v1"),
            new Preset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1"),
            new Preset("groq", "Groq", "https://api.groq.com/openai/v1"),
            new Preset("together", "Together", "https://api.together.xyz/v1"),
            new Preset("ollama", "Ollama（本地）", "http://localhost:11434/v1"),
            new Preset("lmstudio", "LM Studio（本地）", "http://127.0.0.1:1234/v1"),
            new Preset("custom", "自定义…", null)
    );

    private SettingsBaseUrlPresets() {}
}
