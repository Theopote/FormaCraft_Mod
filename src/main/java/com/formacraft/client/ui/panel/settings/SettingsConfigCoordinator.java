package com.formacraft.client.ui.panel.settings;

import com.formacraft.config.SettingsConfig;
import net.minecraft.text.Text;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * 设置面板的配置读写协调器：负责把 {@link SettingsConfig} 与面板草稿态互相同步。
 * <p>
 * 从 {@code SettingsPanel} 抽出 {@code loadFromConfig} / {@code saveSettings}，
 * 全程仅通过 {@link SettingsPanelRenderHost} 访问面板状态，不直接触碰面板私有字段。
 * 控件的实际刷新仍由 {@link SettingsPanelRenderHost#syncWidgetStateFromDraft()} 完成。
 */
public final class SettingsConfigCoordinator {
    private SettingsConfigCoordinator() {}

    /** 从磁盘配置载入并同步到草稿态 + 控件。 */
    public static void load(SettingsPanelRenderHost host) {
        SettingsConfig.load();
        SettingsConfig cfg = SettingsConfig.INSTANCE;

        host.apiKeyInput().setText(cfg.apiKey != null ? cfg.apiKey : "");
        host.orchestratorInput().setText(cfg.orchestratorEndpoint != null ? cfg.orchestratorEndpoint : "http://localhost:8000");
        // UX：即便选择了隐藏，也在编辑时显示明文，避免“看不到输入内容”
        host.apiKeyInput().setPasswordMode(host.hideKey() && !host.apiKeyInput().isFocused());
        host.apiKeyInput().setMaxLength(256);
        host.orchestratorInput().setMaxLength(256);
        host.llmBaseUrlInput().setMaxLength(256);
        host.modelInput().setMaxLength(128);

        // 同步草稿态：允许为空（自动），不再限制预设列表
        String model = cfg.model != null ? cfg.model.trim() : "";
        host.setDraftModel(model);
        host.modelInput().setText(model);

        String provider = (cfg.llmProvider == null || cfg.llmProvider.isBlank()) ? "auto" : cfg.llmProvider.trim();
        host.setDraftLlmProvider(provider);

        String rawBaseUrl = cfg.llmBaseUrl != null ? cfg.llmBaseUrl.trim() : "";
        String sanitizedBaseUrl = SettingsModelCatalog.sanitizeLlmBaseUrlOrNull(rawBaseUrl);
        // 如果配置里是明显错误的 baseUrl（例如 https://ps://...），自动清空并保存，避免反复踩坑
        if (sanitizedBaseUrl == null) {
            sanitizedBaseUrl = "";
            if (cfg.llmBaseUrl != null && !cfg.llmBaseUrl.isBlank()) {
                SettingsConfig.INSTANCE.llmBaseUrl = "";
                SettingsConfig.save();
            }
        }
        host.setDraftLlmBaseUrl(sanitizedBaseUrl);
        host.llmBaseUrlInput().setText(sanitizedBaseUrl);
        host.syncBaseUrlPresetFromValue(sanitizedBaseUrl);

        host.setDraftShowDebugWarnings(cfg.showDebugWarnings);
        host.setDraftTemperature(clamp01(cfg.temperature));
        host.setDraftFontSize(clampInt(cfg.fontSize));
        host.setDraftInteractionReach(clampReach(cfg.interactionReach));

        host.updateCachedTemperatureText();
        host.syncWidgetStateFromDraft();
    }

    /** 校验并保存草稿态到磁盘；成功后重新载入以确保一致。返回是否保存成功。 */
    public static boolean save(SettingsPanelRenderHost host) {
        String apiKey = host.apiKeyInput().getText();
        String endpoint = sanitizeEndpoint(host.orchestratorInput().getText());
        String llmBaseUrlRaw = host.llmBaseUrlInput().getText() != null ? host.llmBaseUrlInput().getText().trim() : "";
        String llmBaseUrl = SettingsModelCatalog.sanitizeLlmBaseUrlOrNull(llmBaseUrlRaw);
        if (llmBaseUrl == null && !llmBaseUrlRaw.isBlank()) {
            host.showToast("LLM Base URL 无效：必须以 http:// 或 https:// 开头", true);
            return false;
        }
        String draftProvider = host.draftLlmProvider();
        String provider = (draftProvider == null || draftProvider.isBlank()) ? "auto" : draftProvider.trim();
        // 模型以输入框为准（留空=自动）
        String model = host.modelInput().getText() == null ? "" : host.modelInput().getText().trim();
        host.setDraftModel(model);

        // 验证 API Key：DeepSeek/OpenAI 等通常需要；本地 ollama 可不填
        boolean requiresKey = !"ollama".equals(provider.toLowerCase());
        if (!requiresKey && (apiKey == null || apiKey.trim().isEmpty())) {
            // ok，本地 provider 允许空 key
        } else if (requiresKey && !isValidApiKey(apiKey)) {
            host.showToast(Text.translatable("formacraft.settings.error.api_key_empty").getString(), true);
            return false;
        }

        try {
            SettingsConfig.INSTANCE.apiKey = apiKey;
            SettingsConfig.INSTANCE.orchestratorEndpoint = endpoint;
            SettingsConfig.INSTANCE.model = model;
            SettingsConfig.INSTANCE.llmProvider = provider;
            SettingsConfig.INSTANCE.llmBaseUrl = llmBaseUrl;
            SettingsConfig.INSTANCE.showDebugWarnings = host.draftShowDebugWarnings();
            SettingsConfig.INSTANCE.temperature = clamp01(host.draftTemperature());
            SettingsConfig.INSTANCE.fontSize = clampInt(host.draftFontSize());
            SettingsConfig.INSTANCE.interactionReach = clampReach(host.draftInteractionReach());
            SettingsConfig.save();
            load(host); // 重新加载以确保同步
            host.showToast(Text.translatable("formacraft.settings.saved").getString(), false);
            return true;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "unknown" : e.getMessage();
            host.showToast(Text.translatable("formacraft.settings.error.save_failed", msg).getString(), true);
            return false;
        }
    }

    // ---- 本地校验/换算工具（与面板侧同源，避免跨包暴露私有静态方法）----

    private static float clamp01(float v) {
        if (v < 0.0f) return 0.0f;
        return Math.min(v, 1.0f);
    }

    private static int clampInt(int v) {
        if (v < SettingsPanelLayout.MIN_FONT_SIZE) return SettingsPanelLayout.MIN_FONT_SIZE;
        return Math.min(v, SettingsPanelLayout.MAX_FONT_SIZE);
    }

    private static int clampReach(int v) {
        if (v < SettingsPanelLayout.MIN_INTERACTION_REACH) return SettingsPanelLayout.MIN_INTERACTION_REACH;
        return Math.min(v, SettingsPanelLayout.MAX_INTERACTION_REACH);
    }

    private static String sanitizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.trim().isEmpty()) {
            return "http://localhost:8000";
        }
        String v = endpoint.trim();
        // 简单容错：如果用户只填了 localhost:8000，则补协议
        if (!v.startsWith("http://") && !v.startsWith("https://")) {
            v = "http://" + v;
        }
        // 移除末尾斜杠
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        // 验证URL格式（使用URI避免弃用警告）
        try {
            URI uri = new URI(v);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
                return "http://localhost:8000";
            }
        } catch (URISyntaxException e) {
            return "http://localhost:8000";
        }
        return v;
    }

    private static boolean isValidApiKey(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return false;
        }
        String trimmed = apiKey.trim();
        return trimmed.length() >= 10 && trimmed.length() <= 256;
    }
}
