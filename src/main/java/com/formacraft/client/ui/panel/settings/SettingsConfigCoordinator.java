package com.formacraft.client.ui.panel.settings;

import com.formacraft.config.SettingsConfig;
import net.minecraft.text.Text;


/**
 * 设置面板的配置读写协调器：负责把 {@link SettingsConfig} 与面板草稿态互相同步。
 * <p>
 * 从 {@code SettingsPanel} 抽出 {@code loadFromConfig} / {@code saveSettings}，
 * 全程仅通过 {@link SettingsPanelRenderHost} 访问面板状态，不直接触碰面板私有字段。
 * 控件的实际刷新仍由 {@link SettingsPanelRenderHost#syncWidgetStateFromDraft()} 完成。
 */
public final class SettingsConfigCoordinator {
    private SettingsConfigCoordinator() {}

    /** 从已提交配置载入草稿；取消编辑不写盘、不重载全局状态。 */
    public static void load(SettingsPanelRenderHost host) {
        SettingsConfig cfg = SettingsConfig.INSTANCE;

        host.apiKeyInput().setText(cfg.apiKey != null ? cfg.apiKey : "");
        host.orchestratorInput().setText(cfg.orchestratorEndpoint != null ? cfg.orchestratorEndpoint : "http://localhost:8000");
        host.apiKeyInput().setPasswordMode(host.hideKey());
        host.apiKeyInput().setMaxLength(256);
        host.orchestratorInput().setMaxLength(256);
        host.llmBaseUrlInput().setMaxLength(256);
        host.modelInput().setMaxLength(128);
        host.searchApiKeyInput().setMaxLength(256);
        host.googleCseCxInput().setMaxLength(128);

        // 同步草稿态：允许为空（自动），不再限制预设列表
        String model = cfg.model != null ? cfg.model.trim() : "";
        host.setDraftModel(model);
        host.modelInput().setText(model);

        String provider = (cfg.llmProvider == null || cfg.llmProvider.isBlank()) ? "auto" : cfg.llmProvider.trim();
        host.setDraftLlmProvider(provider);

        String rawBaseUrl = cfg.llmBaseUrl != null ? cfg.llmBaseUrl.trim() : "";
        String sanitizedBaseUrl = SettingsModelCatalog.sanitizeLlmBaseUrlOrNull(rawBaseUrl);
        // Keep invalid saved text editable; opening settings must never erase a user's endpoint.
        if (sanitizedBaseUrl == null) sanitizedBaseUrl = rawBaseUrl;
        host.setDraftLlmBaseUrl(sanitizedBaseUrl);
        host.llmBaseUrlInput().setText(sanitizedBaseUrl);
        host.syncBaseUrlPresetFromValue(sanitizedBaseUrl);

        host.setDraftShowDebugWarnings(cfg.showDebugWarnings);
        String searchProvider = (cfg.searchProvider == null || cfg.searchProvider.isBlank()) ? "auto" : cfg.searchProvider.trim();
        host.setDraftSearchProvider(searchProvider);
        host.searchApiKeyInput().setText(cfg.searchApiKey != null ? cfg.searchApiKey : "");
        host.googleCseCxInput().setText(cfg.googleCseCx != null ? cfg.googleCseCx : "");
        host.setDraftTemperature(clamp01(cfg.temperature));
        host.setDraftInteractionReach(clampReach(cfg.interactionReach));

        host.loadBackendSettings(cfg);
        host.syncWidgetStateFromDraft();
    }

    /** 校验并保存草稿态到磁盘；成功后重新载入以确保一致。返回是否保存成功。 */
    public static boolean save(SettingsPanelRenderHost host) {
        try {
            SettingsPreferenceValues values = SettingsPreferenceValues.parse(host.reachInput().getText(),host.temperatureInput().getText());
            SettingsConfig draft = SettingsConfig.INSTANCE.copy();
            host.stageBackendSettings(draft);
            String endpointRaw = host.orchestratorInput().getText().trim();
            if (endpointRaw.isBlank()) endpointRaw = "http://localhost:" + draft.backendPort;
            String endpoint = SettingsModelCatalog.sanitizeLlmBaseUrlOrNull(endpointRaw.contains("://") ? endpointRaw : "http://" + endpointRaw);
            if (endpoint == null) throw new IllegalArgumentException("后端地址无效，请填写完整的 HTTP(S) 服务地址");
            String baseUrl = SettingsModelCatalog.sanitizeLlmBaseUrlOrNull(host.llmBaseUrlInput().getText());
            if (baseUrl == null) throw new IllegalArgumentException("Base URL 无效，请填写 HTTP(S) 服务地址");
            draft.apiKey = host.apiKeyInput().getText().trim();
            draft.orchestratorEndpoint = endpoint;
            draft.model = host.modelInput().getText().trim();
            draft.llmProvider = host.draftLlmProvider();
            draft.llmBaseUrl = baseUrl;
            draft.showDebugWarnings = host.draftShowDebugWarnings();
            draft.searchProvider = host.draftSearchProvider();
            draft.searchApiKey = host.searchApiKeyInput().getText().trim();
            draft.googleCseCx = host.googleCseCxInput().getText().trim();
            draft.temperature = values.temperature();
            draft.interactionReach = values.reach();
            SettingsConfig.commit(draft);
            load(host);
            host.showToast(Text.translatable("formacraft.settings.saved").getString(),false);
            return true;
        } catch (Exception e) {
            host.showToast("保存失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),true);
            return false;
        }
    }

    // ---- 本地校验/换算工具（与面板侧同源，避免跨包暴露私有静态方法）----

    private static float clamp01(float v) {
        if (v < 0.0f) return 0.0f;
        return Math.min(v, 1.0f);
    }

    private static int clampReach(int v) {
        if (v < SettingsPanelLayout.MIN_INTERACTION_REACH) return SettingsPanelLayout.MIN_INTERACTION_REACH;
        return Math.min(v, SettingsPanelLayout.MAX_INTERACTION_REACH);
    }

}
