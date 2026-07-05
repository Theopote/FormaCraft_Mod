package com.formacraft.client.ui.panel;

import com.formacraft.FormacraftMod;
import com.formacraft.client.ui.panel.settings.*;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.client.ui.widget.HudTextInput;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import com.formacraft.config.SettingsConfig;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.formacraft.client.ui.panel.settings.SettingsPanelLayout.*;

/**
 * FormaCraft 设置面板（HUD 左侧栏）
 * <p>
 * Settings panel for FormaCraft HUD overlay.
 * Handles configuration for API keys, model selection, temperature, and font size.
 */
public class SettingsPanel extends BasePanel implements SettingsPanelRenderHost {

    private static final FcaLog LOG = FcaLog.of("SettingsPanel");


    // Toast 持续时间（毫秒）
    private static final long TOAST_DURATION_MS = 2500L;

    private final MinecraftClient client = MinecraftClient.getInstance();

    // 输入组件（HUD 模式，不依赖 Screen）
    private final HudTextInput orchestratorInput = new HudTextInput();
    private final HudTextInput apiKeyInput = new HudTextInput();
    private final HudTextInput llmBaseUrlInput = new HudTextInput();
    private final HudTextInput modelInput = new HudTextInput();
    private final HudTextInput searchApiKeyInput = new HudTextInput();
    private final HudTextInput googleCseCxInput = new HudTextInput();
    // 默认显示明文（用户可以手动点 Hide 隐藏）
    private boolean hideKey = false;

    // 草稿态（UI）——只有 Save 时才写入 SettingsConfig
    // 允许为空：表示“让后端自行决定模型”
    private String draftModel = "";
    /** auto / deepseek / openai / openai_compat / ollama */
    private String draftLlmProvider = "auto";
    /** OpenAI-compatible base URL（可为空，表示由后端环境变量/默认决定） */
    private String draftLlmBaseUrl = "";
    /** 调试：是否在聊天面板显示后端 debugWarnings */
    private boolean draftShowDebugWarnings = false;
    /** auto / duckduckgo / bing / google_cse / wikipedia_only */
    private String draftSearchProvider = "auto";
    private float draftTemperature = 0.7f;
    private int draftInteractionReach = DEFAULT_INTERACTION_REACH;

    // 简易提示（保存成功/失败）
    private String toast = null;
    private long toastUntilMs = 0L;
    private boolean isToastError = false;

    // 焦点管理（用于Tab切换）
    private int currentFocusIndex = 0; // 0=orchestrator, 1=apiKey, 2=llmBaseUrl
    private static final int FOCUS_ORCHESTRATOR = 0;
    private static final int FOCUS_API_KEY = 1;
    private static final int FOCUS_LLM_BASEURL = 2;
    private static final int FOCUS_MODEL = 3;

    // 原版风格控件（参考 Pushdozer：ButtonWidget / SliderWidget）
    private ButtonWidget showHideButton;
    private ButtonWidget pasteButton;
    private ButtonWidget testKeyButton;
    private ButtonWidget saveButton;
    private ButtonWidget cancelButton;
    private ButtonWidget resetButton;
    private ButtonWidget detectModelButton;
    private ButtonWidget autoModelButton;
    private ButtonWidget llmProviderButton;
    private ButtonWidget llmBaseUrlPresetButton;
    private ButtonWidget debugWarningsButton;
    ButtonWidget searchProviderButton;
    ButtonWidget testSearchKeyButton;
    private List<ButtonWidget> llmBaseUrlPresetOptionButtons;
    private SettingsSliders.Temperature temperatureSlider;
    private SettingsSliders.InteractionReach interactionReachSlider;
    private SliderWidget activeSlider = null; // 只允许同时操作一个滑条

    // LLM Base URL：预设下拉（避免输入错误；需要特殊服务时选择“自定义”）。
    // 统一来源：SettingsBaseUrlPresets.CATALOG —— 选中预设时会同时写好 provider + baseUrl。
    private record BaseUrlPreset(String id, String label, String url, String provider, boolean local) {}
    private static final List<BaseUrlPreset> BASE_URL_PRESETS = buildPresetsFromCatalog();

    private static List<BaseUrlPreset> buildPresetsFromCatalog() {
        List<BaseUrlPreset> out = new ArrayList<>(SettingsBaseUrlPresets.CATALOG.size());
        for (SettingsBaseUrlPresets.Entry e : SettingsBaseUrlPresets.CATALOG) {
            out.add(new BaseUrlPreset(e.id(), e.label(), e.url(), e.provider(), e.local()));
        }
        return List.copyOf(out);
    }
    private int baseUrlPresetIndex = 0;
    private boolean baseUrlPresetDropdownOpen = false;

    // 下拉 overlay 渲染：确保永远在最上层（避免被后续控件盖住）
    private boolean pendingBaseUrlDropdownOverlay = false;
    private int pendingBaseUrlDropdownX = 0;
    private int pendingBaseUrlDropdownY = 0;
    private int pendingBaseUrlDropdownW = 0;

    // 模型探测（HTTP）
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private volatile boolean detectingModel = false;
    private volatile boolean validatingKey = false;
    private volatile boolean validatingSearchKey = false;

    private record DetectResponse(int status, String body) {
    }

    // 缓存的计算值（性能优化）
    private int cachedContentX = -1;
    private int cachedContentWidth = -1;
    private String cachedTemperatureText = null;

    // 面板滚动（Settings 内容较多时允许滚轮上下滚动）
    private int scrollY = 0;
    private int maxScrollY = 0;

    // 模型选择：候选列表（来自 /models）
    private List<String> availableModels = new ArrayList<>();
    private long availableModelsUpdatedAtMs = 0L;
    private boolean modelDropdownOpen = false;
    private boolean pendingModelDropdownOverlay = false;
    private int pendingModelDropdownX = 0;
    private int pendingModelDropdownY = 0;
    private int pendingModelDropdownW = 0;
    private List<ButtonWidget> modelOptionButtons = new ArrayList<>();

    public SettingsPanel() {
        loadFromConfig();
        initWidgets();
    }

    private void loadFromConfig() {
        SettingsConfigCoordinator.load(this);
    }

    private static float clamp01(float v) {
        if (v < 0.0f) return 0.0f;
        return Math.min(v, 1.0f);
    }

    private static int clampReach(int v) {
        if (v < MIN_INTERACTION_REACH) return MIN_INTERACTION_REACH;
        return Math.min(v, MAX_INTERACTION_REACH);
    }

    @Override
    protected void drawContents(DrawContext ctx) {
        ensureWidgets();
        // 缓存计算值（性能优化）
        if (cachedContentX < 0 || cachedContentWidth != panelWidth) {
            cachedContentX = panelX + CONTENT_PADDING;
            cachedContentWidth = panelWidth;
        }
        int x = cachedContentX;
        int y = getContentY() + CONTENT_PADDING - scrollY;
        int w = panelWidth - CONTENT_PADDING * 2;

        drawContentBackground(ctx);

        // 内容裁剪（避免滚动时画出边界）
        int sx0 = panelX + 1;
        int sy0 = getContentY() + 1;
        int sx1 = panelX + panelWidth - 1;
        int sy1 = panelY + panelHeight - 1;
        if (sx1 > sx0 && sy1 > sy0) ctx.enableScissor(sx0, sy0, sx1, sy1);
        try {
            // 每帧重置 overlay 申请（由 drawLlmBaseUrlField 触发）
            pendingBaseUrlDropdownOverlay = false;
            pendingModelDropdownOverlay = false;

            // 标题
            ctx.drawTextWithShadow(client.textRenderer,
                    Text.translatable("formacraft.settings.title"),
                    x, y, COLOR_WHITE);
            y += TITLE_HEIGHT;

            y = SettingsPanelDrawSupport.drawSectionHeader(client, ctx, Text.literal("LLM 与后端"), x, y, w);
            y = SettingsConnectionSection.drawSection(this, ctx, x, y, w);
            y = SettingsPanelDrawSupport.drawSectionHeader(client, ctx, Text.literal("建筑研究搜索"), x, y, w);
            y = SettingsSearchSection.drawSection(this, ctx, x, y, w);
            y = SettingsPanelDrawSupport.drawSectionHeader(client, ctx, Text.literal("界面与偏好"), x, y, w);
            y = SettingsPreferencesSection.drawSection(this, ctx, x, y, w);
            y = SettingsPanelDrawSupport.drawSectionHeader(client, ctx, Text.literal("操作"), x, y, w);
            y = SettingsActionsSection.drawSection(this, ctx, x, y, w);
            SettingsConnectionSection.renderOverlays(this, ctx);

            // 计算最大滚动（基于未滚动起点）
            int contentTop = getContentY() + CONTENT_PADDING;
            int visibleH = getContentHeight() - CONTENT_PADDING * 2;
            int totalH = (y + scrollY) - contentTop + LABEL_OFFSET; // y 是已减 scrollY 的
            maxScrollY = Math.max(0, totalH - visibleH);
            if (scrollY > maxScrollY) scrollY = maxScrollY;
            if (scrollY < 0) scrollY = 0;
        } finally {
            if (sx1 > sx0 && sy1 > sy0) ctx.disableScissor();
        }

        // Toast：不参与滚动，也不被 scissor 裁剪（否则用户会觉得“点了没反应”）
        int toastY = getContentY() + getContentHeight() - CONTENT_PADDING - client.textRenderer.fontHeight - 2;
        if (toast != null && System.currentTimeMillis() > toastUntilMs) {
            toast = null;
        }
        SettingsPanelDrawSupport.drawToast(client, ctx, toast, toastUntilMs, isToastError, x, toastY);
    }

    @Override
    public void updateCachedTemperatureText() {
        cachedTemperatureText = String.format("%.2f", draftTemperature);
    }

    // =======================
    //   鼠标交互
    // =======================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true; // 处理顶部 Tab 切换
        return SettingsInputController.handleClick(this, mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        return SettingsInputController.handleDrag(this, mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return SettingsInputController.handleRelease(this, mouseX, mouseY, button);
    }

    @Override
    public void mouseScrolled(double mouseX, double mouseY, double amount) {
        SettingsInputController.handleScroll(this, mouseX, mouseY, amount);
    }

    @Override
    protected boolean drawCustomTooltip(DrawContext ctx, double mouseX, double mouseY) {
        ensureWidgets();

        // HUD 场景不会像 Screen 一样自动渲染 Tooltip，这里手动做一层（参考 Pushdozer 的使用习惯）
        // 优先级：小按钮/滑条/下拉/底部按钮
        if (showHideButton != null && showHideButton.isMouseOver(mouseX, mouseY)) {
            drawTooltipCompat(ctx, java.util.Collections.singletonList(Text.translatable("formacraft.settings.tooltip.show_hide")), (int) mouseX, (int) mouseY);
            return true;
        }
        if (pasteButton != null && pasteButton.isMouseOver(mouseX, mouseY)) {
            drawTooltipCompat(ctx, java.util.Collections.singletonList(Text.translatable("formacraft.settings.tooltip.paste")), (int) mouseX, (int) mouseY);
            return true;
        }
        if (testKeyButton != null && testKeyButton.isMouseOver(mouseX, mouseY)) {
            drawTooltipCompat(ctx, java.util.Collections.singletonList(Text.literal("校验当前 API Key（provider/base URL 组合）")), (int) mouseX, (int) mouseY);
            return true;
        }
        if (temperatureSlider != null && temperatureSlider.isMouseOver(mouseX, mouseY)) {
            drawTooltipCompat(ctx, java.util.Collections.singletonList(Text.translatable("formacraft.settings.tooltip.temperature")), (int) mouseX, (int) mouseY);
            return true;
        }
        if (interactionReachSlider != null && interactionReachSlider.isMouseOver(mouseX, mouseY)) {
            drawTooltipCompat(ctx, java.util.Collections.singletonList(Text.translatable("formacraft.settings.tooltip.interaction_reach")), (int) mouseX, (int) mouseY);
            return true;
        }
        if (detectModelButton != null && detectModelButton.isMouseOver(mouseX, mouseY)) {
            int n = (availableModels == null) ? 0 : availableModels.size();
            String t = (modelInput.getText() == null) ? "" : modelInput.getText().trim();
            String cur = t.isBlank() ? "自动" : t;
            String age = (availableModelsUpdatedAtMs <= 0L) ? "未刷新" : ((System.currentTimeMillis() - availableModelsUpdatedAtMs) / 1000 + "s前");
            drawTooltipCompat(
                    ctx,
                    java.util.List.of(
                            Text.literal("刷新模型列表（/models）"),
                            Text.literal("当前：" + cur),
                            Text.literal("缓存：" + n + "（" + age + "）")
                    ),
                    (int) mouseX,
                    (int) mouseY
            );
            return true;
        }
        if (autoModelButton != null && autoModelButton.isMouseOver(mouseX, mouseY)) {
            drawTooltipCompat(
                    ctx,
                    java.util.List.of(Text.literal("清空模型（留空=自动）")),
                    (int) mouseX,
                    (int) mouseY
            );
            return true;
        }
        if (llmProviderButton != null && llmProviderButton.isMouseOver(mouseX, mouseY)) {
            String p = (draftLlmProvider == null || draftLlmProvider.isBlank()) ? "auto" : draftLlmProvider.trim();
            drawTooltipCompat(
                    ctx,
                    java.util.List.of(Text.literal("LLM Provider（切换）"), Text.literal("当前：" + p)),
                    (int) mouseX,
                    (int) mouseY
            );
            return true;
        }
        if (llmBaseUrlPresetButton != null && llmBaseUrlPresetButton.isMouseOver(mouseX, mouseY)) {
            BaseUrlPreset p = getSelectedBaseUrlPreset();
            String name = (p == null) ? "自动" : p.label;
            String u = (p == null) ? "" : (p.url == null ? "(自定义输入)" : (p.url.isBlank() ? "(自动)" : p.url));
            drawTooltipCompat(
                    ctx,
                    java.util.List.of(Text.literal("LLM Base URL（预设下拉）"), Text.literal("当前：" + name), Text.literal("URL: " + u)),
                    (int) mouseX,
                    (int) mouseY
            );
            return true;
        }
        if (debugWarningsButton != null && debugWarningsButton.isMouseOver(mouseX, mouseY)) {
            drawTooltipCompat(
                    ctx,
                    java.util.List.of(
                            Text.literal("Debug Warnings（切换）"),
                            Text.literal("当前：" + (draftShowDebugWarnings ? "ON" : "OFF")),
                            Text.literal("用于显示后端 debugWarnings（LLM 纠错/回退信息）")
                    ),
                    (int) mouseX,
                    (int) mouseY
            );
            return true;
        }
        if (testSearchKeyButton != null && testSearchKeyButton.isMouseOver(mouseX, mouseY)) {
            drawTooltipCompat(
                    ctx,
                    java.util.List.of(
                            Text.literal("测试搜索 Key"),
                            Text.literal("调用 /search/validate 探测当前搜索引擎")
                    ),
                    (int) mouseX,
                    (int) mouseY
            );
            return true;
        }
        if (saveButton != null && saveButton.isMouseOver(mouseX, mouseY)) {
            drawTooltipCompat(ctx, java.util.Collections.singletonList(Text.translatable("formacraft.settings.tooltip.save")), (int) mouseX, (int) mouseY);
            return true;
        }
        if (cancelButton != null && cancelButton.isMouseOver(mouseX, mouseY)) {
            drawTooltipCompat(ctx, java.util.Collections.singletonList(Text.translatable("formacraft.settings.tooltip.cancel")), (int) mouseX, (int) mouseY);
            return true;
        }
        if (resetButton != null && resetButton.isMouseOver(mouseX, mouseY)) {
            drawTooltipCompat(ctx, java.util.Collections.singletonList(Text.translatable("formacraft.settings.tooltip.reset")), (int) mouseX, (int) mouseY);
            return true;
        }
        return false;
    }

    // =======================
    // 原版控件：初始化与同步
    // =======================

    private void initWidgets() {
        // API Key：Show/Hide
        showHideButton = ButtonWidget.builder(getShowHideText(), b -> toggleHideKey())
                .dimensions(0, 0, SHOW_HIDE_BUTTON_WIDTH, INPUT_HEIGHT)
                .tooltip(Tooltip.of(Text.translatable("formacraft.settings.tooltip.show_hide")))
                .build();

        // API Key：Paste
        pasteButton = ButtonWidget.builder(Text.translatable("formacraft.settings.paste"), b -> {
                    apiKeyInput.setFocused(true);
                    orchestratorInput.setFocused(false);
                    apiKeyInput.paste();
                })
                .dimensions(0, 0, PASTE_BUTTON_WIDTH, INPUT_HEIGHT)
                .tooltip(Tooltip.of(Text.translatable("formacraft.settings.tooltip.paste")))
                .build();

        testKeyButton = ButtonWidget.builder(Text.literal("校验 Key"), b -> startValidateKey())
            .dimensions(0, 0, PASTE_BUTTON_WIDTH, INPUT_HEIGHT)
            .tooltip(Tooltip.of(Text.literal("调用后端 /models 快速校验当前 Key 是否可用")))
            .build();

        // Model list refresh (calls /models)
        detectModelButton = ButtonWidget.builder(getDetectModelButtonText(), b -> startDetectModel())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("刷新模型列表（调用后端 /models；失败时仍可手动输入模型名）")))
                .build();

        autoModelButton = ButtonWidget.builder(Text.literal("自动"), b -> {
                    draftModel = "";
                    modelInput.setText("");
                    modelDropdownOpen = false;
                    hideModelOptionButtons();
                    showToast("模型=自动（留空）", false);
                })
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("清空模型设置（让后端/Provider 自动选择默认模型）")))
                .build();

        llmProviderButton = ButtonWidget.builder(getLlmProviderButtonText(), b -> cycleLlmProvider())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("选择 LLM Provider（DeepSeek 优先；其它 OpenAI-compatible 也可用）")))
                .build();

        llmBaseUrlPresetButton = ButtonWidget.builder(getBaseUrlPresetButtonText(), b -> toggleBaseUrlPresetDropdown())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("选择主流 LLM Base URL 预设（避免输入错误）；选“自定义”可手动输入")))
                .build();

        debugWarningsButton = ButtonWidget.builder(getDebugWarningsButtonText(), b -> toggleDebugWarnings())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("调试：将后端 debugWarnings 追加显示到聊天面板（便于调 prompt / 纠错链路）")))
                .build();

        searchProviderButton = ButtonWidget.builder(getSearchProviderButtonText(), b -> cycleSearchProvider())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("建筑研究联网搜索：选择搜索引擎或 API")))
                .build();

        testSearchKeyButton = ButtonWidget.builder(Text.literal("测试搜索 Key"), b -> startValidateSearchKey())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("调用后端 /search/validate 探测当前搜索引擎配置")))
                .build();

        // BaseURL 下拉选项（按钮形式，避免自绘命中不准）
        llmBaseUrlPresetOptionButtons = new ArrayList<>();
        for (int i = 0; i < BASE_URL_PRESETS.size(); i++) {
            final int idx = i;
            ButtonWidget opt = ButtonWidget.builder(Text.empty(), b -> applyBaseUrlPreset(idx))
                    .dimensions(0, 0, 0, BUTTON_HEIGHT)
                    .build();
            opt.visible = false;
            opt.active = true;
            llmBaseUrlPresetOptionButtons.add(opt);
        }

        // Model 下拉选项（按钮形式；数量动态，但我们预先创建一组上限）
        modelOptionButtons = new ArrayList<>();
        int maxModelOptions = 12;
        for (int i = 0; i < maxModelOptions; i++) {
            final int idx = i;
            ButtonWidget opt = ButtonWidget.builder(Text.empty(), b -> applyModelOption(idx))
                    .dimensions(0, 0, 0, BUTTON_HEIGHT)
                    .build();
            opt.visible = false;
            opt.active = true;
            modelOptionButtons.add(opt);
        }

        // Sliders（用原版 SliderWidget 渲染）
        temperatureSlider = new SettingsSliders.Temperature(this, 0, 0, 0, INPUT_HEIGHT, Text.empty(), clamp01(draftTemperature));
        interactionReachSlider = new SettingsSliders.InteractionReach(this, 0, 0, 0, INPUT_HEIGHT, Text.empty(), reachToValue(draftInteractionReach));
        temperatureSlider.setTooltip(Tooltip.of(Text.translatable("formacraft.settings.tooltip.temperature")));
        interactionReachSlider.setTooltip(Tooltip.of(Text.translatable("formacraft.settings.tooltip.interaction_reach")));

        // Buttons row
        saveButton = ButtonWidget.builder(Text.translatable("formacraft.settings.save"), b -> {
                    if (saveSettings()) {
                        orchestratorInput.setFocused(false);
                        apiKeyInput.setFocused(false);
                    }
                })
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.translatable("formacraft.settings.tooltip.save")))
                .build();
        cancelButton = ButtonWidget.builder(Text.translatable("formacraft.settings.cancel"), b -> {
                    loadFromConfig();
                    orchestratorInput.setFocused(false);
                    apiKeyInput.setFocused(false);
                    showToast(Text.translatable("formacraft.settings.cancelled").getString());
                })
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.translatable("formacraft.settings.tooltip.cancel")))
                .build();
        resetButton = ButtonWidget.builder(Text.translatable("formacraft.settings.reset"), b -> {
                    SettingsConfig.INSTANCE.resetToDefault();
                    SettingsConfig.save();
                    loadFromConfig();
                    orchestratorInput.setFocused(false);
                    apiKeyInput.setFocused(false);
                    showToast(Text.translatable("formacraft.settings.reset_success").getString());
                })
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.translatable("formacraft.settings.tooltip.reset")))
                .build();

        syncWidgetStateFromDraft();
    }

    private Text getDebugWarningsButtonText() {
        return Text.literal(draftShowDebugWarnings ? "开启：显示后端 debugWarnings" : "关闭：不显示 debugWarnings");
    }

    private void toggleDebugWarnings() {
        draftShowDebugWarnings = !draftShowDebugWarnings;
        SettingsConfig.INSTANCE.showDebugWarnings = draftShowDebugWarnings;
        if (debugWarningsButton != null) {
            debugWarningsButton.setMessage(getDebugWarningsButtonText());
        }
        showToast("DebugWarnings=" + (draftShowDebugWarnings ? "ON" : "OFF"), false);
    }

    @Override
    public void hideModelOptionButtons() {
        if (modelOptionButtons == null) return;
        for (ButtonWidget b : modelOptionButtons) {
            b.visible = false;
        }
    }

    private List<String> getFilteredModels() {
        if (availableModels == null || availableModels.isEmpty()) return java.util.Collections.emptyList();
        String q = modelInput.getText() == null ? "" : modelInput.getText().trim().toLowerCase();
        if (q.isEmpty()) return availableModels;
        List<String> out = new ArrayList<>();
        for (String m : availableModels) {
            if (m == null) continue;
            String t = m.trim();
            if (t.isEmpty()) continue;
            if (t.toLowerCase().contains(q)) out.add(t);
        }
        return out;
    }

    @Override
    public void layoutModelOptionButtons(int x, int y0, int w) {
        ensureWidgets();
        if (modelOptionButtons == null) return;

        List<String> items = getFilteredModels();
        int n = Math.min(items.size(), modelOptionButtons.size());
        String cur = (modelInput.getText() == null) ? "" : modelInput.getText().trim();
        for (int i = 0; i < modelOptionButtons.size(); i++) {
            ButtonWidget b = modelOptionButtons.get(i);
            if (i >= n) {
                b.visible = false;
                continue;
            }
            String it = items.get(i);
            String prefix = (!cur.isBlank() && cur.equals(it)) ? "▶ " : "";
            b.setMessage(Text.literal(prefix + it));
            b.setPosition(x, y0 + i * BUTTON_HEIGHT);
            b.setWidth(w);
            b.visible = true;
            b.active = true;
        }
    }

    private void applyModelOption(int optionIndex) {
        List<String> items = getFilteredModels();
        if (optionIndex < 0 || optionIndex >= items.size()) return;
        String picked = items.get(optionIndex);
        if (picked == null) return;
        String v = picked.trim();
        modelInput.setText(v);
        draftModel = v;
        modelDropdownOpen = false;
        hideModelOptionButtons();
        showToast("模型=" + v, false);
    }

    @Override
    public void hideBaseUrlPresetButtons() {
        if (llmBaseUrlPresetOptionButtons == null) return;
        for (ButtonWidget b : llmBaseUrlPresetOptionButtons) {
            b.visible = false;
        }
    }

    @Override
    public void layoutBaseUrlPresetButtons(int x, int y0, int w) {
        ensureWidgets();
        if (llmBaseUrlPresetOptionButtons == null) return;

        for (int i = 0; i < llmBaseUrlPresetOptionButtons.size(); i++) {
            ButtonWidget b = llmBaseUrlPresetOptionButtons.get(i);
            BaseUrlPreset it = BASE_URL_PRESETS.get(i);

            // 下拉选项仅显示“名称”，不拼接 URL（URL 已在 tooltip 中提供，避免过长遮挡）
            String label = (i == baseUrlPresetIndex ? "▶ " : "") + it.label;

            b.setMessage(Text.literal(label));
            b.setPosition(x, y0 + i * BUTTON_HEIGHT);
            b.setWidth(w);
            b.visible = true;
            b.active = true;
        }
    }

    private Text getDetectModelButtonText() {
        if (detectingModel) {
            return Text.literal("刷新中...");
        }
        int n = (availableModels == null) ? 0 : availableModels.size();
        return Text.literal(n > 0 ? ("刷新模型列表（" + n + "）") : "刷新模型列表");
    }

    private Text getLlmProviderButtonText() {
        String p = (draftLlmProvider == null || draftLlmProvider.isBlank()) ? "auto" : draftLlmProvider.trim();
        return Text.literal("Provider（当前：" + p + "）");
    }

    private void cycleLlmProvider() {
        // 与预设目录联动：循环遍历目录中「带 provider」的条目（跳过 custom），
        // 每次同时写好 provider + baseUrl，并同步预设下拉的选中项。
        List<BaseUrlPreset> cycle = new ArrayList<>();
        for (BaseUrlPreset p : BASE_URL_PRESETS) {
            if (p.provider != null && !p.provider.isBlank()) {
                cycle.add(p);
            }
        }
        if (cycle.isEmpty()) return;

        String cur = (draftLlmProvider == null || draftLlmProvider.isBlank()) ? "auto" : draftLlmProvider.trim().toLowerCase();
        int idx = -1;
        for (int i = 0; i < cycle.size(); i++) {
            if (cycle.get(i).provider.equalsIgnoreCase(cur)) {
                idx = i;
                break;
            }
        }
        BaseUrlPreset next = cycle.get((idx + 1) % cycle.size());
        draftLlmProvider = next.provider;

        // "auto" 不锁定 baseUrl（交给后端/Provider 决定）；其余供应商写入其默认端点。
        if ("auto".equalsIgnoreCase(next.provider)) {
            draftLlmBaseUrl = "";
        } else if (next.url != null) {
            draftLlmBaseUrl = next.url;
        }
        llmBaseUrlInput.setText(draftLlmBaseUrl != null ? draftLlmBaseUrl : "");
        syncBaseUrlPresetFromValue(draftLlmBaseUrl);

        if (llmProviderButton != null) llmProviderButton.setMessage(getLlmProviderButtonText());
        if (llmBaseUrlPresetButton != null) llmBaseUrlPresetButton.setMessage(getBaseUrlPresetButtonText());
        // Provider 切换后，旧的模型列表很可能不适用：清空缓存，等用户点击“刷新模型列表”
        availableModels = new ArrayList<>();
        availableModelsUpdatedAtMs = 0L;
        modelDropdownOpen = false;
        hideModelOptionButtons();
        showToast(next.local ? ("LLM Provider: " + next.provider + "（本地，API Key 可留空）")
                : ("LLM Provider: " + next.provider), false);
    }

    private Text getSearchProviderButtonText() {
        String p = (draftSearchProvider == null || draftSearchProvider.isBlank()) ? "auto" : draftSearchProvider.trim();
        for (SettingsSearchProviders.Entry e : SettingsSearchProviders.CYCLE) {
            if (e.id().equalsIgnoreCase(p)) {
                return Text.literal("搜索：" + e.label());
            }
        }
        return Text.literal("搜索：" + p);
    }

    private void cycleSearchProvider() {
        List<SettingsSearchProviders.Entry> cycle = SettingsSearchProviders.CYCLE;
        String cur = (draftSearchProvider == null || draftSearchProvider.isBlank()) ? "auto" : draftSearchProvider.trim().toLowerCase();
        int idx = -1;
        for (int i = 0; i < cycle.size(); i++) {
            if (cycle.get(i).id().equalsIgnoreCase(cur)) {
                idx = i;
                break;
            }
        }
        SettingsSearchProviders.Entry next = cycle.get((idx + 1) % cycle.size());
        draftSearchProvider = next.id();
        if (searchProviderButton != null) searchProviderButton.setMessage(getSearchProviderButtonText());
        showToast("搜索引擎: " + next.label(), false);
    }

    private void startValidateSearchKey() {
        if (validatingSearchKey) return;
        validatingSearchKey = true;
        if (testSearchKeyButton != null) {
            testSearchKeyButton.setMessage(Text.literal("测试中..."));
            testSearchKeyButton.active = false;
        }

        String base = sanitizeEndpoint(orchestratorInput.getText());
        String provider = (draftSearchProvider == null || draftSearchProvider.isBlank()) ? "auto" : draftSearchProvider.trim();
        String searchApiKey = searchApiKeyInput.getText() != null ? searchApiKeyInput.getText().trim() : "";
        String googleCx = googleCseCxInput.getText() != null ? googleCseCxInput.getText().trim() : "";

        if (SettingsSearchProviders.requiresApiKey(provider) && searchApiKey.isBlank()) {
            validatingSearchKey = false;
            if (testSearchKeyButton != null) {
                testSearchKeyButton.setMessage(Text.literal("测试搜索 Key"));
                testSearchKeyButton.active = true;
            }
            showToast("当前搜索引擎需要填写 Search API Key", true);
            return;
        }
        if ("google_cse".equalsIgnoreCase(provider) && googleCx.isBlank()) {
            validatingSearchKey = false;
            if (testSearchKeyButton != null) {
                testSearchKeyButton.setMessage(Text.literal("测试搜索 Key"));
                testSearchKeyButton.active = true;
            }
            showToast("Google 自定义搜索需要填写 CSE CX", true);
            return;
        }

        String computedUrl = base + "/search/validate";
        try {
            StringBuilder q = new StringBuilder();
            q.append("provider=").append(URLEncoder.encode(provider, StandardCharsets.UTF_8));
            if (!googleCx.isBlank()) {
                q.append("&google_cse_cx=").append(URLEncoder.encode(googleCx, StandardCharsets.UTF_8));
            }
            computedUrl = computedUrl + "?" + q;
        } catch (Exception e) {
            LOG.debug("build search validate URL failed", e);
        }
        final String finalUrl = computedUrl;

        CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder()
                        .uri(URI.create(finalUrl))
                        .timeout(Duration.ofSeconds(15))
                        .header("Accept", "application/json")
                        .GET();
                if (!searchApiKey.isBlank()) {
                    b.header("Authorization", "Bearer " + searchApiKey);
                }
                HttpResponse<String> resp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
                return new DetectResponse(resp.statusCode(), resp.body());
            } catch (Exception e) {
                return new DetectResponse(-1, e.toString());
            }
        }).thenAccept(resp -> client.execute(() -> {
            validatingSearchKey = false;
            if (testSearchKeyButton != null) {
                testSearchKeyButton.setMessage(Text.literal("测试搜索 Key"));
                testSearchKeyButton.active = true;
            }
            if (resp == null) {
                showToast("搜索校验失败：未知错误", true);
                return;
            }
            if (resp.status < 0) {
                showToast("搜索校验失败：" + SettingsModelCatalog.shortErr(resp.body), true);
                return;
            }
            if (resp.status < 200 || resp.status >= 300) {
                showToast("搜索校验 HTTP " + resp.status + "：" + SettingsModelCatalog.shortErr(resp.body), true);
                return;
            }
            String msg = readSearchValidateMessage(resp.body);
            boolean ok = readSearchValidateOk(resp.body);
            showToast(msg != null && !msg.isBlank() ? msg : (ok ? "搜索 Key 校验通过" : "搜索 Key 校验失败"), !ok);
        }));
    }

    private static boolean readSearchValidateOk(String body) {
        if (body == null || body.isBlank()) return false;
        try {
            var obj = com.formacraft.common.json.JsonUtil.get().fromJson(body, com.google.gson.JsonObject.class);
            return obj != null && obj.has("ok") && obj.get("ok").getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    private static String readSearchValidateMessage(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            var obj = com.formacraft.common.json.JsonUtil.get().fromJson(body, com.google.gson.JsonObject.class);
            if (obj != null && obj.has("message_zh") && !obj.get("message_zh").isJsonNull()) {
                return obj.get("message_zh").getAsString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private BaseUrlPreset getSelectedBaseUrlPreset() {
        if (baseUrlPresetIndex < 0 || baseUrlPresetIndex >= BASE_URL_PRESETS.size()) {
            baseUrlPresetIndex = 0;
        }
        return BASE_URL_PRESETS.get(baseUrlPresetIndex);
    }

    private Text getBaseUrlPresetButtonText() {
        BaseUrlPreset p = getSelectedBaseUrlPreset();
        String name = (p == null) ? "自动" : p.label;
        return Text.literal("预设：" + name);
    }

    private void toggleBaseUrlPresetDropdown() {
        baseUrlPresetDropdownOpen = !baseUrlPresetDropdownOpen;
        if (!baseUrlPresetDropdownOpen) {
            hideBaseUrlPresetButtons();
        }
    }

    private void applyBaseUrlPreset(int idx) {
        if (idx < 0 || idx >= BASE_URL_PRESETS.size()) return;
        baseUrlPresetIndex = idx;
        baseUrlPresetDropdownOpen = false;
        hideBaseUrlPresetButtons();

        BaseUrlPreset p = BASE_URL_PRESETS.get(idx);
        if (p.url == null) {
            // custom: keep input as-is; allow editing。不覆盖 provider（用户自定端点自行决定）。
            llmBaseUrlInput.setFocused(true);
            currentFocusIndex = FOCUS_LLM_BASEURL;
        } else {
            draftLlmBaseUrl = p.url;
            llmBaseUrlInput.setText(p.url);
            llmBaseUrlInput.setFocused(false);
            // 一次配好：选中供应商预设时同时写入后端 provider id（custom 除外）。
            if (p.provider != null && !p.provider.isBlank()) {
                draftLlmProvider = p.provider;
                if (llmProviderButton != null) llmProviderButton.setMessage(getLlmProviderButtonText());
            }
        }

        if (llmBaseUrlPresetButton != null) llmBaseUrlPresetButton.setMessage(getBaseUrlPresetButtonText());
        if (p.local) {
            showToast("本地供应商：API Key 可留空", false);
        }
        // Base URL 切换后，模型列表很可能不适用：清空缓存，等用户点击“刷新模型列表”
        availableModels = new ArrayList<>();
        availableModelsUpdatedAtMs = 0L;
        modelDropdownOpen = false;
        hideModelOptionButtons();
    }

    @Override
    public void syncBaseUrlPresetFromValue(String baseUrl) {
        String v = baseUrl == null ? "" : baseUrl.trim();

        if (v.isEmpty()) {
            // auto
            for (int i = 0; i < BASE_URL_PRESETS.size(); i++) {
                if ("auto".equals(BASE_URL_PRESETS.get(i).id)) {
                    baseUrlPresetIndex = i;
                    return;
                }
            }
            baseUrlPresetIndex = 0;
            return;
        }

        for (int i = 0; i < BASE_URL_PRESETS.size(); i++) {
            BaseUrlPreset p = BASE_URL_PRESETS.get(i);
            if (p.url != null && p.url.equalsIgnoreCase(v)) {
                baseUrlPresetIndex = i;
                return;
            }
        }

        // custom
        for (int i = 0; i < BASE_URL_PRESETS.size(); i++) {
            if ("custom".equals(BASE_URL_PRESETS.get(i).id)) {
                baseUrlPresetIndex = i;
                return;
            }
        }
    }

    private void startDetectModel() {
        if (detectingModel) return;
        detectingModel = true;
        if (detectModelButton != null) {
            detectModelButton.setMessage(getDetectModelButtonText());
        }

        String base = sanitizeEndpoint(orchestratorInput.getText());
        String url = base.endsWith("/models") ? base : (base + "/models");
        // 注意：这里用的是“当前输入框的 API Key”（草稿态），而不是已保存配置。
        // 否则用户未点 Save 时会一直使用旧 key，体验很差。
        String apiKey = apiKeyInput.getText() != null ? apiKeyInput.getText().trim() : "";
        String provider = (draftLlmProvider == null) ? "" : draftLlmProvider.trim();
        String llmBaseUrlRaw = llmBaseUrlInput.getText() != null ? llmBaseUrlInput.getText().trim() : "";
        String llmBaseUrl = SettingsModelCatalog.sanitizeLlmBaseUrlOrNull(llmBaseUrlRaw);
        if (llmBaseUrl == null && !llmBaseUrlRaw.isBlank()) {
            detectingModel = false;
            showToast("LLM Base URL 无效：必须以 http:// 或 https:// 开头", true);
            if (detectModelButton != null) detectModelButton.setMessage(getDetectModelButtonText());
            return;
        }

        // 附带 provider/baseUrl（让 orchestrator /models 也能探测真实 LLM 端点）
        String computedUrl = url;
        try {
            if (llmBaseUrl != null && (!provider.isBlank() || !llmBaseUrl.isBlank())) {
                StringBuilder q = new StringBuilder();
                if (!provider.isBlank())
                    q.append("provider=").append(URLEncoder.encode(provider, StandardCharsets.UTF_8));
                if (!llmBaseUrl.isBlank()) {
                    if (!q.isEmpty()) q.append('&');
                    q.append("base_url=").append(URLEncoder.encode(llmBaseUrl, StandardCharsets.UTF_8));
                }
                computedUrl = url + "?" + q;
            }
        } catch (Exception e) {
            LOG.debug("build models query URL failed", e);
        }
        final String finalUrl = computedUrl;

        // 记录关键信息到日志（避免 toast 过长看不清）
        FormacraftMod.LOGGER.debug(
                "[DetectModel] backend={} provider={} base_url={} url={}",
                base, provider, llmBaseUrl, finalUrl);

        CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder()
                        .uri(URI.create(finalUrl))
                        // /models 可能触发后端去探测外网（OpenAI/DeepSeek/OpenRouter 等），4s 太容易超时
                        .timeout(Duration.ofSeconds(12))
                        .header("Accept", "application/json")
                        .GET();

                // OpenAI / OpenAI-compatible：/models 通常需要 Authorization
                if (!apiKey.isBlank()) {
                    b.header("Authorization", "Bearer " + apiKey);
                }

                HttpRequest req = b.build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                return new DetectResponse(resp.statusCode(), resp.body());
            } catch (Exception e) {
                // 保留异常信息，方便用户排查（URL 非法/连接失败/DNS 失败等）
                return new DetectResponse(-1, e.toString());
            }
        }).thenAccept(resp -> client.execute(() -> {
            detectingModel = false;
            if (resp == null) {
                showToast("刷新失败：未知错误（resp=null）", true);
                if (detectModelButton != null) detectModelButton.setMessage(getDetectModelButtonText());
                return;
            }

            // 本地异常（URI.create/连接失败等）
            if (resp.status < 0) {
                String err = SettingsModelCatalog.shortErr(resp.body);
                // 针对超时做更友好的提示：后端可能健康，但上游模型列表探测不可达/太慢
                if (err.toLowerCase().contains("httptimeoutexception") || err.toLowerCase().contains("request timed out")) {
                    showToast("刷新超时：上游模型列表可能不可达（仍可手动输入模型）", true);
                } else {
                    showToast("刷新失败：请求异常（详见日志） err=" + err, true);
                }
                if (detectModelButton != null) detectModelButton.setMessage(getDetectModelButtonText());
                return;
            }

            if (resp.body == null || resp.body.isBlank()) {
                showToast("刷新失败：后端无响应内容（详见日志）", true);
                if (detectModelButton != null) detectModelButton.setMessage(getDetectModelButtonText());
                return;
            }

            if (resp.status < 200 || resp.status >= 300) {
                // 常见：OpenAI 兼容端点未带 key → 401；URL 不对 → 404
                showToast("刷新失败：" + resp.status + "（详见日志）", true);
                if (detectModelButton != null) detectModelButton.setMessage(getDetectModelButtonText());
                return;
            }

            // 解析返回：models[] + default_model + remote_models_ok
            String suggested = SettingsModelCatalog.parseDetectedModel(resp.body);
            List<String> models = SettingsModelCatalog.parseModelsList(resp.body);
            availableModels = (models == null) ? new ArrayList<>() : new ArrayList<>(models);
            availableModelsUpdatedAtMs = System.currentTimeMillis();

            boolean remoteOk = SettingsModelCatalog.readRemoteModelsOk(resp.body);
            String modelsSource = SettingsModelCatalog.readModelsSource(resp.body);

            // 同步草稿态（以输入框为准；留空=自动）
            draftModel = modelInput.getText() == null ? "" : modelInput.getText().trim();

            if (availableModels.isEmpty()) {
                showToast("模型列表为空（可手动输入模型）", true);
            } else {
                String tip = "模型列表已更新：" + availableModels.size();
                if (modelsSource != null && !modelsSource.isBlank()) {
                    if ("fallback".equalsIgnoreCase(modelsSource)) tip += "（常用预设）";
                    if ("remote".equalsIgnoreCase(modelsSource)) tip += "（在线拉取）";
                }
                if (!remoteOk) tip += "（上游不可达）";
                if (suggested != null && !suggested.isBlank()) tip += "（推荐：" + suggested.trim() + "）";
                showToast(tip, false);
            }

            // 如果输入框聚焦且有候选，自动展开下拉
            if (modelInput.isFocused() && !availableModels.isEmpty()) {
                modelDropdownOpen = true;
            }

            if (detectModelButton != null) detectModelButton.setMessage(getDetectModelButtonText());
        }));
    }

    private void startValidateKey() {
        if (validatingKey) return;
        validatingKey = true;
        if (testKeyButton != null) {
            testKeyButton.setMessage(Text.literal("校验中..."));
            testKeyButton.active = false;
        }

        String base = sanitizeEndpoint(orchestratorInput.getText());
        String url = base.endsWith("/models") ? base : (base + "/models");
        String apiKey = apiKeyInput.getText() != null ? apiKeyInput.getText().trim() : "";
        String provider = (draftLlmProvider == null) ? "" : draftLlmProvider.trim();
        String model = modelInput.getText() != null ? modelInput.getText().trim() : "";
        String llmBaseUrlRaw = llmBaseUrlInput.getText() != null ? llmBaseUrlInput.getText().trim() : "";
        String llmBaseUrl = SettingsModelCatalog.sanitizeLlmBaseUrlOrNull(llmBaseUrlRaw);
        String providerHint = provider.isBlank() ? "auto" : provider;
        String modelHint = model.isBlank() ? "auto" : model;
        String baseHint = (llmBaseUrl == null || llmBaseUrl.isBlank()) ? "(auto)" : llmBaseUrl;
        final String llmHint = providerHint + "/" + modelHint + " @ " + baseHint;

        if (llmBaseUrl == null && !llmBaseUrlRaw.isBlank()) {
            validatingKey = false;
            showToast("LLM Base URL 无效：必须以 http:// 或 https:// 开头", true);
            if (testKeyButton != null) {
                testKeyButton.setMessage(Text.literal("校验 Key"));
                testKeyButton.active = true;
            }
            return;
        }

        String computedUrl = url;
        try {
            if (llmBaseUrl != null && (!provider.isBlank() || !llmBaseUrl.isBlank())) {
                StringBuilder q = new StringBuilder();
                if (!provider.isBlank()) {
                    q.append("provider=").append(URLEncoder.encode(provider, StandardCharsets.UTF_8));
                }
                if (!llmBaseUrl.isBlank()) {
                    if (!q.isEmpty()) q.append('&');
                    q.append("base_url=").append(URLEncoder.encode(llmBaseUrl, StandardCharsets.UTF_8));
                }
                computedUrl = url + "?" + q;
            }
        } catch (Exception e) {
            LOG.debug("build validate key URL failed", e);
        }
        final String finalUrl = computedUrl;

        CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder()
                        .uri(URI.create(finalUrl))
                        .timeout(Duration.ofSeconds(10))
                        .header("Accept", "application/json")
                        .GET();

                if (!apiKey.isBlank()) {
                    b.header("Authorization", "Bearer " + apiKey);
                }

                HttpResponse<String> resp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
                return new DetectResponse(resp.statusCode(), resp.body());
            } catch (Exception e) {
                return new DetectResponse(-1, e.toString());
            }
        }).thenAccept(resp -> client.execute(() -> {
            validatingKey = false;
            if (testKeyButton != null) {
                testKeyButton.setMessage(Text.literal("校验 Key"));
                testKeyButton.active = true;
            }

            if (resp == null) {
                showToast("校验失败：未知错误（resp=null）", true);
                return;
            }

            if (resp.status < 0) {
                showToast("校验失败：请求异常 " + SettingsModelCatalog.shortErr(resp.body), true);
                return;
            }

            if (resp.status >= 200 && resp.status < 300) {
                boolean remoteOk = SettingsModelCatalog.readRemoteModelsOk(resp.body);
                String source = SettingsModelCatalog.readModelsSource(resp.body);
                if (!remoteOk) {
                    String src = (source == null || source.isBlank()) ? "fallback" : source;
                    showToast("上游模型端点不可达（" + src + "），暂无法确认 Key 有效性。当前 LLM：" + llmHint, true);
                    return;
                }
                showToast("Key 校验通过（" + providerHint + "）", false);
                return;
            }

            if (resp.status == 401 || resp.status == 403) {
                showToast("Key 无效/未授权（" + resp.status + "）。当前 LLM：" + llmHint, true);
                return;
            }

            showToast("校验失败：" + resp.status + "。当前 LLM：" + llmHint + "。" + SettingsModelCatalog.shortErr(resp.body), true);
        }));
    }

    private void toggleHideKey() {
        hideKey = !hideKey;
        apiKeyInput.setPasswordMode(hideKey && !apiKeyInput.isFocused());
        searchApiKeyInput.setPasswordMode(hideKey && !searchApiKeyInput.isFocused());
        if (showHideButton != null) {
            showHideButton.setMessage(getShowHideText());
        }
    }

    private Text getShowHideText() {
        return hideKey
                ? Text.translatable("formacraft.settings.show")
                : Text.translatable("formacraft.settings.hide");
    }

    @Override
    public void syncWidgetStateFromDraft() {
        if (showHideButton != null) {
            showHideButton.setMessage(getShowHideText());
        }
        if (detectModelButton != null) {
            detectModelButton.setMessage(getDetectModelButtonText());
        }
        if (llmProviderButton != null) {
            llmProviderButton.setMessage(getLlmProviderButtonText());
        }
        if (debugWarningsButton != null) {
            debugWarningsButton.setMessage(getDebugWarningsButtonText());
        }
        if (searchProviderButton != null) {
            searchProviderButton.setMessage(getSearchProviderButtonText());
        }
        if (testSearchKeyButton != null) {
            testSearchKeyButton.setMessage(validatingSearchKey ? Text.literal("测试中...") : Text.literal("测试搜索 Key"));
            testSearchKeyButton.active = !validatingSearchKey;
        }
        if (draftLlmBaseUrl != null) {
            // 避免 loadFromConfig 后输入框仍显示旧值
            llmBaseUrlInput.setText(draftLlmBaseUrl);
        }
        if (draftModel != null) {
            modelInput.setText(draftModel);
        }
        if (interactionReachSlider != null) {
            interactionReachSlider.setCustomValue(reachToValue(draftInteractionReach));
        }
        if (temperatureSlider != null) {
            temperatureSlider.setCustomValue(clamp01(draftTemperature));
        }
    }

    private static double reachToValue(int reach) {
        int clamped = Math.max(MIN_INTERACTION_REACH, Math.min(MAX_INTERACTION_REACH, reach));
        return (clamped - MIN_INTERACTION_REACH) / (double) (MAX_INTERACTION_REACH - MIN_INTERACTION_REACH);
    }

    private static int valueToReach(double value) {
        double v = Math.max(0.0, Math.min(1.0, value));
        return MIN_INTERACTION_REACH + (int) Math.round(v * (MAX_INTERACTION_REACH - MIN_INTERACTION_REACH));
    }

    /**
     * 验证并清理端点URL
     */
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
            // 检查是否为有效的http/https URI
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
                return "http://localhost:8000";
            }
        } catch (URISyntaxException e) {
            // URL格式无效，返回默认值
            return "http://localhost:8000";
        }
        
        return v;
    }

    /**
     * 保存设置（带验证）
     */
    private boolean saveSettings() {
        return SettingsConfigCoordinator.save(this);
    }

    @Override
    public void showToast(String msg, boolean isError) {
        this.toast = msg;
        this.isToastError = isError;
        long toastStartMs = System.currentTimeMillis();
        this.toastUntilMs = toastStartMs + TOAST_DURATION_MS;
    }

    private void showToast(String msg) {
        showToast(msg, false);
    }

    // =======================
    //   键盘输入
    // =======================

    @Override
    public void charTyped(char chr) {
        if (orchestratorInput.isFocused()) orchestratorInput.charTyped(chr);
        if (apiKeyInput.isFocused()) apiKeyInput.charTyped(chr);
        BaseUrlPreset p = getSelectedBaseUrlPreset();
        if (p != null && p.url == null && llmBaseUrlInput.isFocused()) llmBaseUrlInput.charTyped(chr);
        if (modelInput.isFocused()) {
            modelInput.charTyped(chr);
            draftModel = modelInput.getText() == null ? "" : modelInput.getText().trim();
            if (!availableModels.isEmpty()) modelDropdownOpen = true;
        }
        if (searchApiKeyInput.isFocused()) searchApiKeyInput.charTyped(chr);
        if (googleCseCxInput.isFocused()) googleCseCxInput.charTyped(chr);
    }

    @Override
    public void keyPressed(int keyCode) {
        // 兼容旧调用：没有 modifiers 时仍能处理 Backspace / ESC
        keyPressed(keyCode, 0, 0);
    }

    @Override
    public void keyPressed(int keyCode, int scanCode, int modifiers) {
        // 关键：要拿到 modifiers，才能支持 Ctrl+V / Shift 选区 等
        if (orchestratorInput.isFocused()) orchestratorInput.keyPressed(keyCode, modifiers);
        if (apiKeyInput.isFocused()) apiKeyInput.keyPressed(keyCode, modifiers);
        BaseUrlPreset p = getSelectedBaseUrlPreset();
        if (p != null && p.url == null && llmBaseUrlInput.isFocused()) llmBaseUrlInput.keyPressed(keyCode, modifiers);
        if (modelInput.isFocused()) {
            modelInput.keyPressed(keyCode, modifiers);
            draftModel = modelInput.getText() == null ? "" : modelInput.getText().trim();
            if (!availableModels.isEmpty()) modelDropdownOpen = true;
        }
        if (searchApiKeyInput.isFocused()) searchApiKeyInput.keyPressed(keyCode, modifiers);
        if (googleCseCxInput.isFocused()) googleCseCxInput.keyPressed(keyCode, modifiers);

        // ESC：收起模型下拉（避免挡住操作）
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && modelDropdownOpen) {
            modelDropdownOpen = false;
            hideModelOptionButtons();
            return;
        }

        // Tab: 切换焦点
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
            boolean baseUrlCustom = (getSelectedBaseUrlPreset() != null && getSelectedBaseUrlPreset().url == null);
            int count = baseUrlCustom ? 4 : 3; // Orchestrator, API, (BaseURL?), Model
            currentFocusIndex = shift ? (currentFocusIndex + count - 1) % count : (currentFocusIndex + 1) % count;

            orchestratorInput.setFocused(currentFocusIndex == FOCUS_ORCHESTRATOR);
            apiKeyInput.setFocused(currentFocusIndex == FOCUS_API_KEY);
            llmBaseUrlInput.setFocused(baseUrlCustom && currentFocusIndex == FOCUS_LLM_BASEURL);
            // 非自定义 BaseURL 时，“Model”占用 index=2（复用 FOCUS_LLM_BASEURL 这个数值）
            modelInput.setFocused(baseUrlCustom ? (currentFocusIndex == FOCUS_MODEL) : (currentFocusIndex == FOCUS_LLM_BASEURL));

            if (!modelInput.isFocused()) {
                modelDropdownOpen = false;
                hideModelOptionButtons();
            } else if (!availableModels.isEmpty()) {
                modelDropdownOpen = true;
            }
            return;
        }

        // Enter: 快速保存（可选，但很顺手）
        if (keyCode == GLFW.GLFW_KEY_ENTER) {
            saveSettings();
        }
    }

    @Override
    public boolean wantsKeyboardInput() {
        BaseUrlPreset p = getSelectedBaseUrlPreset();
        boolean baseUrlFocused = (p != null && p.url() == null && llmBaseUrlInput.isFocused());
        return apiKeyInput.isFocused() || orchestratorInput.isFocused() || baseUrlFocused || modelInput.isFocused();
    }

    // ---- SettingsPanelRenderHost ----

    @Override
    public void ensureWidgets() {
        if (showHideButton == null) {
            initWidgets();
        }
    }

    @Override
    public MinecraftClient client() {
        return client;
    }

    @Override
    public HudTextInput orchestratorInput() {
        return orchestratorInput;
    }

    @Override
    public HudTextInput apiKeyInput() {
        return apiKeyInput;
    }

    @Override
    public HudTextInput llmBaseUrlInput() {
        return llmBaseUrlInput;
    }

    @Override
    public HudTextInput modelInput() {
        return modelInput;
    }

    @Override
    public boolean hideKey() {
        return hideKey;
    }

    @Override
    public boolean detectingModel() {
        return detectingModel;
    }

    @Override
    public boolean draftShowDebugWarnings() {
        return draftShowDebugWarnings;
    }

    @Override
    public int draftInteractionReach() {
        return draftInteractionReach;
    }

    @Override
    public String cachedTemperatureText() {
        if (cachedTemperatureText == null) {
            updateCachedTemperatureText();
        }
        return cachedTemperatureText;
    }

    @Override
    public boolean modelDropdownOpen() {
        return modelDropdownOpen;
    }

    @Override
    public boolean baseUrlPresetDropdownOpen() {
        return baseUrlPresetDropdownOpen;
    }

    @Override
    public ButtonWidget showHideButton() {
        return showHideButton;
    }

    @Override
    public ButtonWidget pasteButton() {
        return pasteButton;
    }

    @Override
    public ButtonWidget testKeyButton() {
        return testKeyButton;
    }

    @Override
    public ButtonWidget detectModelButton() {
        return detectModelButton;
    }

    @Override
    public ButtonWidget autoModelButton() {
        return autoModelButton;
    }

    @Override
    public ButtonWidget llmProviderButton() {
        return llmProviderButton;
    }

    @Override
    public ButtonWidget llmBaseUrlPresetButton() {
        return llmBaseUrlPresetButton;
    }

    @Override
    public ButtonWidget debugWarningsButton() {
        return debugWarningsButton;
    }

    @Override
    public ButtonWidget searchProviderButton() {
        return searchProviderButton;
    }

    @Override
    public HudTextInput searchApiKeyInput() {
        return searchApiKeyInput;
    }

    @Override
    public HudTextInput googleCseCxInput() {
        return googleCseCxInput;
    }

    @Override
    public Text searchProviderButtonText() {
        return getSearchProviderButtonText();
    }

    @Override
    public ButtonWidget testSearchKeyButton() {
        return testSearchKeyButton;
    }

    @Override
    public Text testSearchKeyButtonText() {
        return validatingSearchKey ? Text.literal("测试中...") : Text.literal("测试搜索 Key");
    }

    @Override
    public boolean validatingSearchKey() {
        return validatingSearchKey;
    }

    @Override
    public String draftSearchProvider() {
        return draftSearchProvider;
    }

    @Override
    public void setDraftSearchProvider(String provider) {
        this.draftSearchProvider = provider;
    }

    @Override
    public SliderWidget temperatureSlider() {
        return temperatureSlider;
    }

    @Override
    public SliderWidget interactionReachSlider() {
        return interactionReachSlider;
    }

    @Override
    public ButtonWidget saveButton() {
        return saveButton;
    }

    @Override
    public ButtonWidget cancelButton() {
        return cancelButton;
    }

    @Override
    public ButtonWidget resetButton() {
        return resetButton;
    }

    @Override
    public List<ButtonWidget> llmBaseUrlPresetOptionButtons() {
        return llmBaseUrlPresetOptionButtons;
    }

    @Override
    public List<ButtonWidget> modelOptionButtons() {
        return modelOptionButtons;
    }

    @Override
    public Text detectModelButtonText() {
        return getDetectModelButtonText();
    }

    @Override
    public Text llmProviderButtonText() {
        return getLlmProviderButtonText();
    }

    @Override
    public Text baseUrlPresetButtonText() {
        return getBaseUrlPresetButtonText();
    }

    @Override
    public Text debugWarningsButtonText() {
        return getDebugWarningsButtonText();
    }

    @Override
    public SettingsBaseUrlPresets.Preset selectedBaseUrlPreset() {
        BaseUrlPreset p = getSelectedBaseUrlPreset();
        return p == null ? null : new SettingsBaseUrlPresets.Preset(p.id(), p.label(), p.url());
    }

    @Override
    public void setPendingModelDropdownOverlay(boolean pending, int x, int y, int w) {
        pendingModelDropdownOverlay = pending;
        pendingModelDropdownX = x;
        pendingModelDropdownY = y;
        pendingModelDropdownW = w;
    }

    @Override
    public void setPendingBaseUrlDropdownOverlay(boolean pending, int x, int y, int w) {
        pendingBaseUrlDropdownOverlay = pending;
        pendingBaseUrlDropdownX = x;
        pendingBaseUrlDropdownY = y;
        pendingBaseUrlDropdownW = w;
    }

    @Override
    public boolean pendingModelDropdownOverlay() {
        return pendingModelDropdownOverlay;
    }

    @Override
    public int pendingModelDropdownX() {
        return pendingModelDropdownX;
    }

    @Override
    public int pendingModelDropdownY() {
        return pendingModelDropdownY;
    }

    @Override
    public int pendingModelDropdownW() {
        return pendingModelDropdownW;
    }

    @Override
    public boolean pendingBaseUrlDropdownOverlay() {
        return pendingBaseUrlDropdownOverlay;
    }

    @Override
    public int pendingBaseUrlDropdownX() {
        return pendingBaseUrlDropdownX;
    }

    @Override
    public int pendingBaseUrlDropdownY() {
        return pendingBaseUrlDropdownY;
    }

    @Override
    public int pendingBaseUrlDropdownW() {
        return pendingBaseUrlDropdownW;
    }

    // ---- 输入交互（SettingsInputController）----

    @Override
    public int contentStartX() {
        return cachedContentX >= 0 ? cachedContentX : (panelX + CONTENT_PADDING);
    }

    @Override
    public int contentWidth() {
        return panelWidth - CONTENT_PADDING * 2;
    }

    @Override
    public int contentTopY() {
        return getContentY();
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return super.isMouseOver(mouseX, mouseY);
    }

    @Override
    public int scrollY() {
        return scrollY;
    }

    @Override
    public void setScrollY(int scrollY) {
        this.scrollY = scrollY;
    }

    @Override
    public int maxScrollY() {
        return maxScrollY;
    }

    @Override
    public void setModelDropdownOpen(boolean open) {
        this.modelDropdownOpen = open;
    }

    @Override
    public void setBaseUrlPresetDropdownOpen(boolean open) {
        this.baseUrlPresetDropdownOpen = open;
    }

    @Override
    public boolean availableModelsEmpty() {
        return availableModels == null || availableModels.isEmpty();
    }

    @Override
    public void setDraftModel(String model) {
        this.draftModel = model;
    }

    @Override
    public SliderWidget activeSlider() {
        return activeSlider;
    }

    @Override
    public void setActiveSlider(SliderWidget slider) {
        this.activeSlider = slider;
    }

    @Override
    public void applyTemperatureFromSlider(double value) {
        draftTemperature = clamp01((float) value);
        SettingsConfig.INSTANCE.temperature = draftTemperature;
        updateCachedTemperatureText();
    }

    @Override
    public void applyInteractionReachFromSlider(double value) {
        draftInteractionReach = clampReach(valueToReach(value));
        SettingsConfig.INSTANCE.interactionReach = draftInteractionReach;
    }

    @Override
    public String draftLlmProvider() {
        return draftLlmProvider;
    }

    @Override
    public float draftTemperature() {
        return draftTemperature;
    }

    @Override
    public void setDraftLlmProvider(String provider) {
        this.draftLlmProvider = provider;
    }

    @Override
    public void setDraftLlmBaseUrl(String baseUrl) {
        this.draftLlmBaseUrl = baseUrl;
    }

    @Override
    public void setDraftShowDebugWarnings(boolean show) {
        this.draftShowDebugWarnings = show;
    }

    @Override
    public void setDraftTemperature(float temperature) {
        this.draftTemperature = clamp01(temperature);
        SettingsConfig.INSTANCE.temperature = this.draftTemperature;
        updateCachedTemperatureText();
    }

    @Override
    public void setDraftInteractionReach(int reach) {
        this.draftInteractionReach = reach;
    }
}
