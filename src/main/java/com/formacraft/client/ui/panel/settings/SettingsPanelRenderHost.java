package com.formacraft.client.ui.panel.settings;

import com.formacraft.client.ui.widget.HudTextInput;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.List;

/** Draw-time callbacks from section classes back to {@link com.formacraft.client.ui.panel.SettingsPanel}. */
public interface SettingsPanelRenderHost {
    MinecraftClient client();

    void ensureWidgets();

    HudTextInput orchestratorInput();

    HudTextInput apiKeyInput();

    HudTextInput llmBaseUrlInput();

    HudTextInput modelInput();

    boolean hideKey();

    boolean detectingModel();

    boolean draftShowDebugWarnings();

    int draftInteractionReach();

    int draftFontSize();

    String cachedTemperatureText();

    boolean modelDropdownOpen();

    boolean baseUrlPresetDropdownOpen();

    ButtonWidget showHideButton();

    ButtonWidget pasteButton();

    ButtonWidget testKeyButton();

    ButtonWidget detectModelButton();

    ButtonWidget autoModelButton();

    ButtonWidget llmProviderButton();

    ButtonWidget llmBaseUrlPresetButton();

    ButtonWidget debugWarningsButton();

    ButtonWidget searchProviderButton();

    HudTextInput searchApiKeyInput();

    HudTextInput googleCseCxInput();

    SliderWidget temperatureSlider();

    SliderWidget interactionReachSlider();

    SliderWidget fontSizeSlider();

    ButtonWidget saveButton();

    ButtonWidget cancelButton();

    ButtonWidget resetButton();

    List<ButtonWidget> llmBaseUrlPresetOptionButtons();

    List<ButtonWidget> modelOptionButtons();

    Text detectModelButtonText();

    Text llmProviderButtonText();

    Text baseUrlPresetButtonText();

    Text debugWarningsButtonText();

    Text searchProviderButtonText();

    SettingsBaseUrlPresets.Preset selectedBaseUrlPreset();

    void setPendingModelDropdownOverlay(boolean pending, int x, int y, int w);

    void setPendingBaseUrlDropdownOverlay(boolean pending, int x, int y, int w);

    void hideModelOptionButtons();

    void hideBaseUrlPresetButtons();

    void layoutModelOptionButtons(int x, int y, int w);

    void layoutBaseUrlPresetButtons(int x, int y, int w);

    boolean pendingModelDropdownOverlay();

    int pendingModelDropdownX();

    int pendingModelDropdownY();

    int pendingModelDropdownW();

    boolean pendingBaseUrlDropdownOverlay();

    int pendingBaseUrlDropdownX();

    int pendingBaseUrlDropdownY();

    int pendingBaseUrlDropdownW();

    // ---- 输入交互（供 SettingsInputController 使用）----

    /** 内容区左起始 X（考虑缓存与内边距）。 */
    int contentStartX();

    /** 内容区可用宽度（已减两侧内边距）。 */
    int contentWidth();

    /** 内容区顶部 Y（标题绘制起点，未加 TITLE_HEIGHT）。 */
    int contentTopY();

    boolean isMouseOver(double mouseX, double mouseY);

    int scrollY();

    void setScrollY(int scrollY);

    int maxScrollY();

    void setModelDropdownOpen(boolean open);

    void setBaseUrlPresetDropdownOpen(boolean open);

    boolean availableModelsEmpty();

    void setDraftModel(String model);

    net.minecraft.client.gui.widget.SliderWidget activeSlider();

    void setActiveSlider(net.minecraft.client.gui.widget.SliderWidget slider);

    // ---- 滑条数值回写（供 SettingsSliders 使用）----

    void applyTemperatureFromSlider(double value);

    void applyFontSizeFromSlider(double value);

    void applyInteractionReachFromSlider(double value);

    // ---- 配置读写（供 SettingsConfigCoordinator 使用）----

    String draftLlmProvider();

    float draftTemperature();

    void setDraftLlmProvider(String provider);

    void setDraftLlmBaseUrl(String baseUrl);

    void setDraftShowDebugWarnings(boolean show);

    void setDraftTemperature(float temperature);

    void setDraftFontSize(int fontSize);

    void setDraftInteractionReach(int reach);

    String draftSearchProvider();

    void setDraftSearchProvider(String provider);

    void syncBaseUrlPresetFromValue(String baseUrl);

    void updateCachedTemperatureText();

    void syncWidgetStateFromDraft();

    void showToast(String msg, boolean isError);
}
