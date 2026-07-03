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

    ButtonWidget detectModelButton();

    ButtonWidget autoModelButton();

    ButtonWidget llmProviderButton();

    ButtonWidget llmBaseUrlPresetButton();

    ButtonWidget debugWarningsButton();

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
}
