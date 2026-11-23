package com.formacraft.client.ui.panel;

import com.formacraft.client.ui.panel.settings.*;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.client.ui.widget.HudTextInput;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import com.formacraft.config.SettingsConfig;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.formacraft.client.ui.panel.settings.SettingsPanelLayout.*;

/**
 * FormaCraft 设置面板（HUD 左侧栏）
 * <p>
 * Settings panel for FormaCraft HUD overlay.
 * Handles configuration for API keys, model selection, temperature, and font size.
 */
public class SettingsPanel extends BasePanel implements SettingsPanelRenderHost {

    public static final FcaLog LOG = FcaLog.of("SettingsPanel");

    public final MinecraftClient client = MinecraftClient.getInstance();

    // 输入组件（HUD 模式，不依赖 Screen）
    public final SettingsBackendSection backendSection = new SettingsBackendSection(this);
    public final SettingsChoicePanel choices = new SettingsChoicePanel();
    public final SettingsDetailsPanel details = new SettingsDetailsPanel();
    public final SettingsUiBindings ui = new SettingsUiBindings();
    public final java.util.EnumMap<SettingsGroup,ButtonWidget> groupButtons = new java.util.EnumMap<>(SettingsGroup.class);
    public final java.util.EnumSet<SettingsGroup> collapsed = java.util.EnumSet.of(SettingsGroup.BACKEND);
    public final HudTextInput reachInput = new HudTextInput(), temperatureInput = new HudTextInput();
    public ButtonWidget statusButton;
    public long searchRevision;
    public SearchIdentity searchIdentity;
    public record SearchIdentity(String backend,String provider,String key,String cx) {}
    public long connectionRevision;
    public record ConnectionIdentity(String backend, String key, String provider, String baseUrl) {}
    public ConnectionIdentity connectionIdentity;
    public boolean modelNeedsConfirmation;
    public final HudTextInput orchestratorInput = new HudTextInput();
    public final HudTextInput apiKeyInput = new HudTextInput();
    public final HudTextInput llmBaseUrlInput = new HudTextInput();
    public final HudTextInput modelInput = new HudTextInput();
    public final HudTextInput searchApiKeyInput = new HudTextInput();
    public final HudTextInput googleCseCxInput = new HudTextInput();
    // 默认显示明文（用户可以手动点 Hide 隐藏）
    public boolean hideKey = false;

    // 草稿态（UI）——只有 Save 时才写入 SettingsConfig
    // 允许为空：表示“让后端自行决定模型”
    public String draftModel = "";
    /** auto / deepseek / openai / openai_compat / ollama */
    public String draftLlmProvider = "auto";
    /** OpenAI-compatible base URL（可为空，表示由后端环境变量/默认决定） */
    public String draftLlmBaseUrl = "";
    /** 调试：是否在聊天面板显示后端 debugWarnings */
    public boolean draftShowDebugWarnings = false;
    /** auto / duckduckgo / bing / google_cse / wikipedia_only */
    public String draftSearchProvider = "auto";
    public float draftTemperature = 0.7f;
    public int draftInteractionReach = DEFAULT_INTERACTION_REACH;

    // 简易提示（保存成功/失败）
    public String toast = null;
    public boolean isToastError = false;

    // 原版风格控件（参考 Pushdozer：ButtonWidget / SliderWidget）
    public ButtonWidget showHideButton;
    public ButtonWidget pasteButton;
    public ButtonWidget testKeyButton;
    public ButtonWidget saveButton;
    public ButtonWidget cancelButton;
    public ButtonWidget resetButton;
    public ButtonWidget detectModelButton;
    public ButtonWidget modelChoicesButton;
    public ButtonWidget llmProviderButton;
    public ButtonWidget llmBaseUrlPresetButton;
    public ButtonWidget debugWarningsButton;
    public ButtonWidget searchProviderButton;
    public ButtonWidget testSearchKeyButton;
    public SettingsSliders.Temperature temperatureSlider;
    public SettingsSliders.InteractionReach interactionReachSlider;

    // 地址预设只负责显式填充；输入框始终允许编辑。
    // 统一来源：SettingsBaseUrlPresets.CATALOG —— 选中预设时会同时写好 provider + baseUrl。
    public record BaseUrlPreset(String id, String label, String url, String provider, boolean local) {}
    public static final List<BaseUrlPreset> BASE_URL_PRESETS = SettingsWidgetAssembler.buildPresetsFromCatalog();

    public int baseUrlPresetIndex = 0;

    // 模型探测（HTTP）
    public final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    public volatile boolean detectingModel = false;
    public volatile boolean validatingKey = false;
    public volatile boolean validatingSearchKey = false;

    public record DetectResponse(int status, String body) {
    }

    // 缓存的计算值（性能优化）

    // 面板滚动（Settings 内容较多时允许滚轮上下滚动）
    public int scrollY = 0;
    public int maxScrollY = 0;

    // 模型选择：候选列表（来自 /models）
    public List<String> availableModels = new ArrayList<>();
    public long availableModelsUpdatedAtMs = 0L;

    public final SettingsProbeController probes = new SettingsProbeController(this);
    public final SettingsWidgetAssembler widgets = new SettingsWidgetAssembler(this);

    @Override public void syncWidgetStateFromDraft() { widgets.syncWidgetStateFromDraft(); }
    @Override public void syncBaseUrlPresetFromValue(String baseUrl) { widgets.syncBaseUrlPresetFromValue(baseUrl); }

    public void drawContentBackgroundPublic(DrawContext ctx) { drawContentBackground(ctx); }
public boolean superMouseClicked(double x, double y, int button) { return super.mouseClicked(x, y, button); }

    @Override protected void drawContents(DrawContext ctx) { SettingsPanelInteraction.drawContents(this, ctx); }
    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) { return SettingsPanelInteraction.mouseClicked(this, mouseX, mouseY, button); }
    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) { return SettingsPanelInteraction.mouseDragged(this, mouseX, mouseY, button, deltaX, deltaY); }
    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) { return SettingsPanelInteraction.mouseReleased(this, mouseX, mouseY, button); }
    @Override public void mouseScrolled(double mouseX, double mouseY, double amount) { SettingsPanelInteraction.mouseScrolled(this, mouseX, mouseY, amount); }
    @Override protected boolean drawCustomTooltip(DrawContext ctx, double mouseX, double mouseY) { return SettingsPanelInteraction.drawCustomTooltip(this, ctx, mouseX, mouseY); }
    public void afterTextEdit(HudTextInput input, String before) { SettingsPanelInteraction.afterTextEdit(this, input, before); }
    public void loadFromConfig() { SettingsPanelInteraction.loadFromConfig(this); }
    public boolean saveSettings() { return SettingsPanelInteraction.saveSettings(this); }
    @Override public void charTyped(char chr) { SettingsPanelInteraction.charTyped(this, chr); }
    @Override public void keyPressed(int keyCode) { SettingsPanelInteraction.keyPressed(this, keyCode); }
    @Override public void keyPressed(int keyCode, int scanCode, int modifiers) { SettingsPanelInteraction.keyPressed(this, keyCode, scanCode, modifiers); }
    @Override public boolean wantsKeyboardInput() { return SettingsPanelInteraction.wantsKeyboardInput(this); }
    @Override public void blurKeyboardFocus() { SettingsPanelInteraction.blurKeyboardFocus(this); }
    public static float clamp01(float v) { return SettingsPanelInteraction.clamp01(v); }
    public static int clampReach(int v) { return SettingsPanelInteraction.clampReach(v); }
    public static double reachToValue(int reach) { return SettingsPanelInteraction.reachToValue(reach); }
    public static int valueToReach(double value) { return SettingsPanelInteraction.valueToReach(value); }

    public SettingsPanel() {
        SettingsConfig.load();
        SettingsPanelInteraction.loadFromConfig(this);
        widgets.initWidgets();
    }

    // =======================
    //   鼠标交互
    // =======================

    // =======================
    // 原版控件：初始化与同步
    // =======================

    @Override public boolean modelNeedsConfirmation() { return modelNeedsConfirmation; }

    /**
     * 验证并清理端点URL
     */

    /**
     * 保存设置（带验证）
     */

    @Override
    public void showToast(String msg, boolean isError) {
        this.toast = msg;
        this.isToastError = isError;

    }

    public void showToast(String msg) {
        showToast(msg, false);
    }

    // =======================
    //   键盘输入
    // =======================

    @Override public void loadBackendSettings(SettingsConfig cfg) { backendSection.load(cfg); }
    @Override public void stageBackendSettings(SettingsConfig cfg) { backendSection.stage(cfg); }
    @Override public SettingsUiBindings ui() { return ui; }
    @Override public HudTextInput reachInput() { return reachInput; }
    @Override public HudTextInput temperatureInput() { return temperatureInput; }
    @Override public void openDetails(String text) { widgets.blurInputs(); choices.close(); details.open(text); }
    @Override public String preferenceError() {
        try { SettingsPreferenceValues.parse(reachInput.getText(),temperatureInput.getText()); return null; }
        catch(IllegalArgumentException e) { return e.getMessage(); }
    }

    // ---- SettingsPanelRenderHost ----

    @Override
    public void ensureWidgets() {
        if (showHideButton == null) {
            widgets.initWidgets();
        }
    }

    @Override public MinecraftClient client() { return client; }

    @Override public HudTextInput orchestratorInput() { return orchestratorInput; }

    @Override public HudTextInput apiKeyInput() { return apiKeyInput; }

    @Override public HudTextInput llmBaseUrlInput() { return llmBaseUrlInput; }

    @Override public HudTextInput modelInput() { return modelInput; }

    @Override public boolean hideKey() { return hideKey; }

    @Override public boolean detectingModel() { return detectingModel; }

    @Override public boolean draftShowDebugWarnings() { return draftShowDebugWarnings; }

    @Override public int draftInteractionReach() { return draftInteractionReach; }

    @Override public ButtonWidget showHideButton() { return showHideButton; }

    @Override public ButtonWidget pasteButton() { return pasteButton; }

    @Override public ButtonWidget testKeyButton() { return testKeyButton; }

    @Override public ButtonWidget detectModelButton() { return detectModelButton; }

    @Override public ButtonWidget modelChoicesButton() { return modelChoicesButton; }

    @Override public ButtonWidget llmProviderButton() { return llmProviderButton; }

    @Override public ButtonWidget llmBaseUrlPresetButton() { return llmBaseUrlPresetButton; }

    @Override public ButtonWidget debugWarningsButton() { return debugWarningsButton; }

    @Override public ButtonWidget searchProviderButton() { return searchProviderButton; }

    @Override public HudTextInput searchApiKeyInput() { return searchApiKeyInput; }

    @Override public HudTextInput googleCseCxInput() { return googleCseCxInput; }

    @Override public Text searchProviderButtonText() { return widgets.getSearchProviderButtonText(); }

    @Override public ButtonWidget testSearchKeyButton() { return testSearchKeyButton; }

    @Override public Text testSearchKeyButtonText() { return validatingSearchKey ? Text.literal("测试中...") : Text.literal("测试搜索配置"); }

    @Override public boolean validatingSearchKey() { return validatingSearchKey; }

    @Override public String draftSearchProvider() { return draftSearchProvider; }

    @Override public void setDraftSearchProvider(String provider) { this.draftSearchProvider = provider; }

    @Override public SliderWidget temperatureSlider() { return temperatureSlider; }

    @Override public SliderWidget interactionReachSlider() { return interactionReachSlider; }

    @Override public ButtonWidget saveButton() { return saveButton; }

    @Override public ButtonWidget cancelButton() { return cancelButton; }

    @Override public ButtonWidget resetButton() { return resetButton; }

    @Override public Text detectModelButtonText() { return widgets.getDetectModelButtonText(); }

    @Override public Text llmProviderButtonText() { return widgets.getLlmProviderButtonText(); }

    @Override public Text baseUrlPresetButtonText() { return widgets.getBaseUrlPresetButtonText(); }

    @Override public Text debugWarningsButtonText() { return widgets.getDebugWarningsButtonText(); }

    // ---- 输入交互（SettingsInputController）----

    @Override public boolean isInteractiveArea(double mouseX, double mouseY) { return visible && (choices.isOpen() || details.isOpen() || super.isInteractiveArea(mouseX, mouseY)); }

    @Override public boolean isMouseOver(double mouseX, double mouseY) { return super.isMouseOver(mouseX, mouseY) || choices.contains(mouseX, mouseY) || details.contains(mouseX,mouseY); }

    @Override public int scrollY() { return scrollY; }

    @Override public void setScrollY(int scrollY) { this.scrollY = scrollY; }

    @Override public int maxScrollY() { return maxScrollY; }

    @Override public void setDraftModel(String model) { this.draftModel = model; }

    @Override
    public void applyTemperatureFromSlider(double value) {
        draftTemperature = clamp01((float) value);
        if (!temperatureInput.isFocused()) temperatureInput.setText(String.format(java.util.Locale.ROOT,"%.2f",draftTemperature));
    }

    @Override
    public void applyInteractionReachFromSlider(double value) {
        draftInteractionReach = clampReach(valueToReach(value));
        if (!reachInput.isFocused()) reachInput.setText(Integer.toString(draftInteractionReach));
    }

    @Override public String draftLlmProvider() { return draftLlmProvider; }

    @Override public float draftTemperature() { return draftTemperature; }

    @Override public void setDraftLlmProvider(String provider) { this.draftLlmProvider = provider; }

    @Override public void setDraftLlmBaseUrl(String baseUrl) { this.draftLlmBaseUrl = baseUrl; }

    @Override public void setDraftShowDebugWarnings(boolean show) { this.draftShowDebugWarnings = show; }

    @Override public void setDraftTemperature(float temperature) { this.draftTemperature = clamp01(temperature); }

    @Override public void setDraftInteractionReach(int reach) { this.draftInteractionReach = reach; }
}
