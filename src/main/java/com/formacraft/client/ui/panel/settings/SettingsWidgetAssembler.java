package com.formacraft.client.ui.panel.settings;

import com.formacraft.client.ui.panel.SettingsPanel;
import com.formacraft.config.SettingsConfig;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.util.ArrayList;
import java.util.List;
import static com.formacraft.client.ui.panel.settings.SettingsPanelLayout.*;

/** Package-private widget peer for SettingsPanel. */
public final class SettingsWidgetAssembler {
    private final SettingsPanel host;
    public SettingsWidgetAssembler(SettingsPanel panel) { this.host = panel; }

    public void initWidgets() {
        host.llmBaseUrlInput.setPlaceholder("https://服务地址/v1");
        host.reachInput.setMaxLength(4); host.temperatureInput.setMaxLength(8);
        for(var group:SettingsGroup.values()) host.groupButtons.put(group,ButtonWidget.builder(Text.literal(group.label),b -> {
            if(!host.collapsed.add(group)) host.collapsed.remove(group);
            blurInputs();
        }).dimensions(0,0,100,BUTTON_HEIGHT).build());
        host.statusButton=ButtonWidget.builder(Text.literal("状态详情"),b -> host.openDetails(host.toast==null?"普通设置仅在保存后生效。取消会撤销草稿。后端启动、停止与检查立即执行。":host.toast))
                .dimensions(0,0,100,BUTTON_HEIGHT).build();
        // API Key：Show/Hide
        host.showHideButton = ButtonWidget.builder(getShowHideText(), b -> toggleHideKey())
                .dimensions(0, 0, SHOW_HIDE_BUTTON_WIDTH, INPUT_HEIGHT)
                .tooltip(Tooltip.of(Text.translatable("formacraft.settings.tooltip.show_hide")))
                .build();

        // API Key：Paste
        host.pasteButton = ButtonWidget.builder(Text.translatable("formacraft.settings.paste"), b -> {
                    host.apiKeyInput.setFocused(true);
                    host.orchestratorInput.setFocused(false);
                    host.apiKeyInput.paste();
                })
                .dimensions(0, 0, PASTE_BUTTON_WIDTH, INPUT_HEIGHT)
                .tooltip(Tooltip.of(Text.translatable("formacraft.settings.tooltip.paste")))
                .build();

        host.testKeyButton = ButtonWidget.builder(Text.literal("校验 Key"), b -> host.probes.startValidateKey())
            .dimensions(0, 0, PASTE_BUTTON_WIDTH, INPUT_HEIGHT)
            .tooltip(Tooltip.of(Text.literal("调用后端 /models 快速校验当前 Key 是否可用")))
            .build();

        // Model list refresh (calls /models)
        host.detectModelButton = ButtonWidget.builder(getDetectModelButtonText(), b -> host.probes.startDetectModel())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("刷新模型列表（调用后端 /models；失败时仍可手动输入模型名）")))
                .build();

        host.modelChoicesButton = ButtonWidget.builder(Text.literal("选择 / 输入…"), b -> openModels())
                .dimensions(0, 0, 0, BUTTON_HEIGHT).build();

        host.llmProviderButton = ButtonWidget.builder(getLlmProviderButtonText(), b -> openProviders())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("选择 LLM Provider（DeepSeek 优先；其它 OpenAI-compatible 也可用）")))
                .build();

        host.llmBaseUrlPresetButton = ButtonWidget.builder(getBaseUrlPresetButtonText(), b -> openBaseUrlPresets())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("选择 Base URL 预设；下方地址始终可手动编辑")))
                .build();

        host.debugWarningsButton = ButtonWidget.builder(getDebugWarningsButtonText(), b -> toggleDebugWarnings())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("调试：将后端 debugWarnings 追加显示到聊天面板（便于调 prompt / 纠错链路）")))
                .build();

        host.searchProviderButton = ButtonWidget.builder(getSearchProviderButtonText(), b -> openSearchProviders())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("建筑研究联网搜索：选择搜索引擎或 API")))
                .build();

        host.testSearchKeyButton = ButtonWidget.builder(Text.literal("测试搜索配置"), b -> host.probes.startValidateSearchKey())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("调用后端 /search/validate 探测当前搜索引擎配置")))
                .build();

        // Sliders（用原版 SliderWidget 渲染）
        host.temperatureSlider = new SettingsSliders.Temperature(host, 0, 0, 0, INPUT_HEIGHT, Text.empty(), SettingsPanel.clamp01(host.draftTemperature));
        host.interactionReachSlider = new SettingsSliders.InteractionReach(host, 0, 0, 0, INPUT_HEIGHT, Text.empty(), SettingsPanel.reachToValue(host.draftInteractionReach));
        host.temperatureSlider.setTooltip(Tooltip.of(Text.translatable("formacraft.settings.tooltip.temperature")));
        host.interactionReachSlider.setTooltip(Tooltip.of(Text.translatable("formacraft.settings.tooltip.interaction_reach")));

        // Buttons row
        host.saveButton = ButtonWidget.builder(Text.translatable("formacraft.settings.save"), b -> {
                    if (host.saveSettings()) {
                        blurInputs();
                    }
                })
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.translatable("formacraft.settings.tooltip.save")))
                .build();
        host.cancelButton = ButtonWidget.builder(Text.translatable("formacraft.settings.cancel"), b -> {
                    host.loadFromConfig();
                    blurInputs();
                    host.showToast(Text.translatable("formacraft.settings.cancelled").getString());
                })
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.translatable("formacraft.settings.tooltip.cancel")))
                .build();
        host.resetButton = ButtonWidget.builder(Text.literal("恢复分组默认…"),b -> openResetGroups())
                .dimensions(0,0,0,BUTTON_HEIGHT).build();

        syncWidgetStateFromDraft();
    }

    public void syncWidgetStateFromDraft() {
        host.reachInput.setText(Integer.toString(host.draftInteractionReach));
        host.temperatureInput.setText(String.format(java.util.Locale.ROOT,"%.2f",host.draftTemperature));
        if (host.showHideButton != null) {
            host.showHideButton.setMessage(getShowHideText());
        }
        if (host.detectModelButton != null) {
            host.detectModelButton.setMessage(getDetectModelButtonText());
        }
        if (host.llmProviderButton != null) {
            host.llmProviderButton.setMessage(getLlmProviderButtonText());
        }
        if (host.debugWarningsButton != null) {
            host.debugWarningsButton.setMessage(getDebugWarningsButtonText());
        }
        if (host.searchProviderButton != null) {
            host.searchProviderButton.setMessage(getSearchProviderButtonText());
        }
        if (host.testSearchKeyButton != null) {
            host.testSearchKeyButton.setMessage(host.validatingSearchKey ? Text.literal("测试中...") : Text.literal("测试搜索配置"));
            host.testSearchKeyButton.active = !host.validatingSearchKey;
        }
        if (host.draftLlmBaseUrl != null) {
            // 避免 loadFromConfig 后输入框仍显示旧值
            host.llmBaseUrlInput.setText(host.draftLlmBaseUrl);
        }
        if (host.draftModel != null) {
            host.modelInput.setText(host.draftModel);
        }
        if (host.interactionReachSlider != null) {
            host.interactionReachSlider.setCustomValue(SettingsPanel.reachToValue(host.draftInteractionReach));
        }
        if (host.temperatureSlider != null) {
            host.temperatureSlider.setCustomValue(SettingsPanel.clamp01(host.draftTemperature));
        }
    }

    public void openResetGroups() {
        blurInputs();
        host.choices.open("恢复哪个分组？保存后生效",java.util.Arrays.stream(SettingsGroup.values())
                .map(g -> new SettingsChoicePanel.Option(g.name(), g.label)).toList(), "", false, value -> {
            SettingsConfig defaults=new SettingsConfig();
            SettingsGroup group=SettingsGroup.valueOf(value);
            switch(group) {
                case CONNECTION -> {
                    host.apiKeyInput.setText(defaults.apiKey); host.modelInput.setText(defaults.model); host.draftModel=defaults.model;
                    host.orchestratorInput.setText(defaults.orchestratorEndpoint);
                    host.draftLlmProvider=defaults.llmProvider; host.draftLlmBaseUrl=defaults.llmBaseUrl;
                    host.llmBaseUrlInput.setText(defaults.llmBaseUrl); host.probes.syncConnectionIdentity(); host.modelNeedsConfirmation=false;
                    host.llmProviderButton.setMessage(getLlmProviderButtonText());
                }
                case SEARCH -> {
                    host.draftSearchProvider=defaults.searchProvider; host.searchApiKeyInput.setText(defaults.searchApiKey); host.googleCseCxInput.setText(defaults.googleCseCx);
                    host.probes.syncSearchIdentity(); host.searchProviderButton.setMessage(getSearchProviderButtonText());
                }
                case PREFERENCES -> {
                    host.draftShowDebugWarnings=defaults.showDebugWarnings;
                    host.interactionReachSlider.setCustomValue(SettingsPanel.reachToValue(defaults.interactionReach));
                    host.temperatureSlider.setCustomValue(defaults.temperature);
                }
                case BACKEND -> host.backendSection.load(defaults);
            }
            host.collapsed.remove(group);
            host.showToast("已恢复“"+group.label+"”草稿；保存后生效",false);
        });
    }

    public void openModels() {
        host.probes.syncConnectionIdentity();
        blurInputs();
        host.choices.open("选择或输入模型 ID", host.availableModels.stream()
                .map(m -> new SettingsChoicePanel.Option(m, m)).toList(), host.modelInput.getText(), true, value -> {
            host.modelInput.setText(value);
            host.draftModel = value;
            host.modelNeedsConfirmation = false;
        });
    }

    public void openProviders() {
        blurInputs();
        var options = new ArrayList<SettingsChoicePanel.Option>();
        options.add(new SettingsChoicePanel.Option("openai", "自定义 / OpenAI 兼容"));
        for (var preset : host.BASE_URL_PRESETS) {
            if (preset.provider() != null) {
                options.add(new SettingsChoicePanel.Option(preset.provider(), preset.label()));
            }
        }
        host.choices.open("选择供应商（保留当前 URL）", options, host.draftLlmProvider, false, value -> {
            host.draftLlmProvider = value;
            host.llmProviderButton.setMessage(getLlmProviderButtonText());
            host.probes.syncConnectionIdentity();
        });
    }

    public void openBaseUrlPresets() {
        blurInputs();
        var options = new ArrayList<SettingsChoicePanel.Option>();
        for (var preset : host.BASE_URL_PRESETS) {
            options.add(new SettingsChoicePanel.Option(preset.id(), preset.label()));
        }
        host.choices.open("填入地址预设", options, getSelectedBaseUrlPreset().id(), false, value -> {
            for (int i = 0; i < host.BASE_URL_PRESETS.size(); i++) {
                if (host.BASE_URL_PRESETS.get(i).id().equals(value)) {
                    applyBaseUrlPreset(i);
                    break;
                }
            }
        });
    }

    public void openSearchProviders() {
        blurInputs();
        host.choices.open("选择搜索引擎", SettingsSearchProviders.CYCLE.stream()
                .map(sp -> new SettingsChoicePanel.Option(sp.id(), sp.label())).toList(), host.draftSearchProvider, false, value -> {
            host.draftSearchProvider = value;
            host.searchProviderButton.setMessage(getSearchProviderButtonText());
        });
    }

    public void toggleHideKey() {
        host.hideKey = !host.hideKey;
        host.apiKeyInput.setPasswordMode(host.hideKey);
        host.searchApiKeyInput.setPasswordMode(host.hideKey);
        if (host.showHideButton != null) {
            host.showHideButton.setMessage(getShowHideText());
        }
    }

    public Text getShowHideText() {
        return host.hideKey
                ? Text.translatable("formacraft.settings.show")
                : Text.translatable("formacraft.settings.hide");
    }

    public Text getDebugWarningsButtonText() {
        return Text.literal("调试信息：" + (host.draftShowDebugWarnings ? "开启" : "关闭"));
    }

    public void toggleDebugWarnings() {
        host.draftShowDebugWarnings = !host.draftShowDebugWarnings;
        if (host.debugWarningsButton != null) {
            host.debugWarningsButton.setMessage(getDebugWarningsButtonText());
        }
        host.showToast("DebugWarnings=" + (host.draftShowDebugWarnings ? "ON" : "OFF"), false);
    }

    public Text getDetectModelButtonText() {
        if (host.detectingModel) {
            return Text.literal("刷新中...");
        }
        int n = (host.availableModels == null) ? 0 : host.availableModels.size();
        return Text.literal(n > 0 ? ("刷新模型列表（" + n + "）") : "刷新模型列表");
    }

    public Text getLlmProviderButtonText() {
        String p = (host.draftLlmProvider == null || host.draftLlmProvider.isBlank()) ? "auto" : host.draftLlmProvider.trim();
        return Text.literal("Provider（当前：" + p + "）");
    }

    public Text getSearchProviderButtonText() {
        String p = (host.draftSearchProvider == null || host.draftSearchProvider.isBlank()) ? "auto" : host.draftSearchProvider.trim();
        for (SettingsSearchProviders.Entry e : SettingsSearchProviders.CYCLE) {
            if (e.id().equalsIgnoreCase(p)) {
                return Text.literal("搜索：" + e.label());
            }
        }
        return Text.literal("搜索：" + p);
    }

    public SettingsPanel.BaseUrlPreset getSelectedBaseUrlPreset() {
        if (host.baseUrlPresetIndex < 0 || host.baseUrlPresetIndex >= host.BASE_URL_PRESETS.size()) {
            host.baseUrlPresetIndex = 0;
        }
        return host.BASE_URL_PRESETS.get(host.baseUrlPresetIndex);
    }

    public Text getBaseUrlPresetButtonText() {
        SettingsPanel.BaseUrlPreset preset = getSelectedBaseUrlPreset();
        String name = (preset == null) ? "自动" : preset.label();
        return Text.literal("预设：" + name);
    }

    public void applyBaseUrlPreset(int idx) {
        if (idx < 0 || idx >= host.BASE_URL_PRESETS.size()) return;
        SettingsPanel.ConnectionIdentity before = host.probes.currentConnectionIdentity();
        host.baseUrlPresetIndex = idx;

        SettingsPanel.BaseUrlPreset preset = host.BASE_URL_PRESETS.get(idx);
        if (preset.url() == null) {
            host.draftLlmProvider = "openai";
            // A custom endpoint uses the OpenAI-compatible adapter and retains its address.
            host.llmBaseUrlInput.setFocused(true);
        } else {
            host.draftLlmBaseUrl = preset.url();
            host.llmBaseUrlInput.setText(preset.url());
            host.llmBaseUrlInput.setFocused(false);
            // 一次配好：选中供应商预设时同时写入后端 provider id（custom 除外）。
            if (preset.provider() != null && !preset.provider().isBlank()) {
                host.draftLlmProvider = preset.provider();
                if (host.llmProviderButton != null) host.llmProviderButton.setMessage(getLlmProviderButtonText());
            }
        }

        if (!before.equals(host.probes.currentConnectionIdentity())) host.probes.syncConnectionIdentity();
        if (host.llmProviderButton != null) host.llmProviderButton.setMessage(getLlmProviderButtonText());
        if (host.llmBaseUrlPresetButton != null) host.llmBaseUrlPresetButton.setMessage(getBaseUrlPresetButtonText());
        if (preset.local()) {
            host.showToast("本地供应商：API Key 可留空", false);
        }

    }

    public void syncBaseUrlPresetFromValue(String baseUrl) {
        String v = baseUrl == null ? "" : baseUrl.trim();

        if (v.isEmpty()) {
            // auto
            for (int i = 0; i < host.BASE_URL_PRESETS.size(); i++) {
                if ("auto".equals(host.BASE_URL_PRESETS.get(i).id())) {
                    host.baseUrlPresetIndex = i;
                    return;
                }
            }
            host.baseUrlPresetIndex = 0;
            return;
        }

        for (int i = 0; i < host.BASE_URL_PRESETS.size(); i++) {
            SettingsPanel.BaseUrlPreset preset = host.BASE_URL_PRESETS.get(i);
            if (preset.url() != null && preset.url().equalsIgnoreCase(v)) {
                host.baseUrlPresetIndex = i;
                return;
            }
        }

        // custom
        for (int i = 0; i < host.BASE_URL_PRESETS.size(); i++) {
            if ("custom".equals(host.BASE_URL_PRESETS.get(i).id())) {
                host.baseUrlPresetIndex = i;
                return;
            }
        }
    }

    public static List<SettingsPanel.BaseUrlPreset> buildPresetsFromCatalog() {
        List<SettingsPanel.BaseUrlPreset> out = new ArrayList<>(SettingsBaseUrlPresets.CATALOG.size());
        for (SettingsBaseUrlPresets.Entry e : SettingsBaseUrlPresets.CATALOG) {
            out.add(new SettingsPanel.BaseUrlPreset(e.id(), e.label(), e.url(), e.provider(), e.local()));
        }
        return List.copyOf(out);
    }

    public void blurInputs() {
        host.ui.blur();
        for(var input:List.of(host.orchestratorInput,host.apiKeyInput,host.llmBaseUrlInput,host.modelInput,host.searchApiKeyInput,host.googleCseCxInput,host.reachInput,host.temperatureInput)) input.setFocused(false);
    }

}
