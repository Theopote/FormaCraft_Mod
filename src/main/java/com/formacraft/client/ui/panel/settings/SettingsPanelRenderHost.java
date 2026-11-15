package com.formacraft.client.ui.panel.settings;

import com.formacraft.client.ui.widget.HudTextInput;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;


/** Draw-time callbacks from section classes back to {@link com.formacraft.client.ui.panel.SettingsPanel}. */
public interface SettingsPanelRenderHost {
    void loadBackendSettings(com.formacraft.config.SettingsConfig cfg);
    void stageBackendSettings(com.formacraft.config.SettingsConfig cfg);
    SettingsUiBindings ui();
    HudTextInput reachInput();
    HudTextInput temperatureInput();
    void openDetails(String message);
    String preferenceError();

    MinecraftClient client();

    void ensureWidgets();

    HudTextInput orchestratorInput();

    HudTextInput apiKeyInput();

    HudTextInput llmBaseUrlInput();

    HudTextInput modelInput();

    boolean hideKey();

    boolean modelNeedsConfirmation();

    boolean detectingModel();

    boolean draftShowDebugWarnings();

    int draftInteractionReach();

    ButtonWidget showHideButton();

    ButtonWidget pasteButton();

    ButtonWidget testKeyButton();

    ButtonWidget detectModelButton();

    ButtonWidget modelChoicesButton();

    ButtonWidget llmProviderButton();

    ButtonWidget llmBaseUrlPresetButton();

    ButtonWidget debugWarningsButton();

    ButtonWidget searchProviderButton();

    HudTextInput searchApiKeyInput();

    HudTextInput googleCseCxInput();

    SliderWidget temperatureSlider();

    SliderWidget interactionReachSlider();

    ButtonWidget saveButton();

    ButtonWidget cancelButton();

    ButtonWidget resetButton();

    Text detectModelButtonText();

    Text llmProviderButtonText();

    Text baseUrlPresetButtonText();

    Text debugWarningsButtonText();

    Text searchProviderButtonText();

    ButtonWidget testSearchKeyButton();

    Text testSearchKeyButtonText();

    boolean validatingSearchKey();

    // ---- 输入交互（供 SettingsInputController 使用）----

    boolean isMouseOver(double mouseX, double mouseY);

    int scrollY();

    void setScrollY(int scrollY);

    int maxScrollY();

    void setDraftModel(String model);

    // ---- 滑条数值回写（供 SettingsSliders 使用）----

    void applyTemperatureFromSlider(double value);

    void applyInteractionReachFromSlider(double value);

    // ---- 配置读写（供 SettingsConfigCoordinator 使用）----

    String draftLlmProvider();

    float draftTemperature();

    void setDraftLlmProvider(String provider);

    void setDraftLlmBaseUrl(String baseUrl);

    void setDraftShowDebugWarnings(boolean show);

    void setDraftTemperature(float temperature);

    void setDraftInteractionReach(int reach);

    String draftSearchProvider();

    void setDraftSearchProvider(String provider);

    void syncBaseUrlPresetFromValue(String baseUrl);

    void syncWidgetStateFromDraft();

    void showToast(String msg, boolean isError);
}
