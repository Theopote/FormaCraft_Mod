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
    private final SettingsBackendSection backendSection = new SettingsBackendSection(this);
    private final SettingsChoicePanel choices = new SettingsChoicePanel();
    private long connectionRevision;
    private record ConnectionIdentity(String backend, String key, String provider, String baseUrl) {}
    private ConnectionIdentity connectionIdentity;
    private boolean modelNeedsConfirmation;
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

    // 原版风格控件（参考 Pushdozer：ButtonWidget / SliderWidget）
    private ButtonWidget showHideButton;
    private ButtonWidget pasteButton;
    private ButtonWidget testKeyButton;
    private ButtonWidget saveButton;
    private ButtonWidget cancelButton;
    private ButtonWidget resetButton;
    private ButtonWidget detectModelButton;
    private ButtonWidget modelChoicesButton;
    private ButtonWidget llmProviderButton;
    private ButtonWidget llmBaseUrlPresetButton;
    private ButtonWidget debugWarningsButton;
    ButtonWidget searchProviderButton;
    ButtonWidget testSearchKeyButton;
    private SettingsSliders.Temperature temperatureSlider;
    private SettingsSliders.InteractionReach interactionReachSlider;
    private SliderWidget activeSlider = null; // 只允许同时操作一个滑条

    // 地址预设只负责显式填充；输入框始终允许编辑。
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

    // 面板滚动（Settings 内容较多时允许滚轮上下滚动）
    private int scrollY = 0;
    private int maxScrollY = 0;

    // 模型选择：候选列表（来自 /models）
    private List<String> availableModels = new ArrayList<>();
    private long availableModelsUpdatedAtMs = 0L;

    public SettingsPanel() {
        loadFromConfig();
        initWidgets();
    }

    private void loadFromConfig() {
        choices.close();
        connectionRevision++;
        SettingsConfigCoordinator.load(this);
        connectionIdentity = currentConnectionIdentity();
        detectingModel = false;
        validatingKey = false;
        if (testKeyButton != null) testKeyButton.setMessage(Text.literal("校验 Key"));
        availableModels = new ArrayList<>();
        availableModelsUpdatedAtMs = 0L;
        modelNeedsConfirmation = false;
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
        syncConnectionIdentity();
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

            y = SettingsPanelDrawSupport.drawSectionHeader(client, ctx, Text.literal("LLM 与后端"), x, y, w);
            y = SettingsConnectionSection.drawSection(this, ctx, x, y, w);
            y = SettingsPanelDrawSupport.drawSectionHeader(client, ctx, Text.literal("建筑研究搜索"), x, y, w);
            y = SettingsSearchSection.drawSection(this, ctx, x, y, w);
            y = SettingsPanelDrawSupport.drawSectionHeader(client, ctx, Text.literal("界面与偏好"), x, y, w);
            y = SettingsPreferencesSection.drawSection(this, ctx, x, y, w);
            y = SettingsPanelDrawSupport.drawSectionHeader(client, ctx, Text.literal("操作"), x, y, w);
            y = SettingsActionsSection.drawSection(this, ctx, x, y, w);
            y = SettingsPanelDrawSupport.drawSectionHeader(client, ctx, Text.literal("本机后端服务"), x, y, w);
            y = backendSection.draw(ctx,x,y,w);

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
        choices.render(ctx, client, panelX + panelWidth, getContentY());
    }

    // =======================
    //   鼠标交互
    // =======================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (choices.isOpen()) { choices.click(mouseX, mouseY, button); return true; }
        if (super.mouseClicked(mouseX, mouseY, button)) return true; // 处理顶部 Tab 切换
        if (isMouseOver(mouseX,mouseY)
                && mouseY >= getContentY() && mouseY < panelY+panelHeight
                && backendSection.click(mouseX,mouseY,button)) {
            for (var input : List.of(orchestratorInput,apiKeyInput,llmBaseUrlInput,modelInput,searchApiKeyInput,googleCseCxInput)) input.setFocused(false);
            return true;
        }
        backendSection.blur();
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
        if (choices.isOpen()) { choices.scroll(amount); return; }
        SettingsInputController.handleScroll(this, mouseX, mouseY, amount);
    }

    @Override
    protected boolean drawCustomTooltip(DrawContext ctx, double mouseX, double mouseY) {
        if (choices.isOpen()) {
            String option = choices.hoveredLabel(mouseX, mouseY);
            if (option != null) drawTooltipCompat(ctx, List.of(Text.literal(option)), (int) mouseX, (int) mouseY);
            return true;
        }
        String backendTip = backendSection.tooltip(mouseX,mouseY);
        if (backendTip != null && isMouseOver(mouseX,mouseY)) {
            drawTooltipCompat(ctx, java.util.Arrays.stream(backendTip.split("\\n")).map(Text::literal).map(t -> (Text)t).toList(),(int)mouseX,(int)mouseY);
            return true;
        }
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
        if (modelChoicesButton != null && modelChoicesButton.isMouseOver(mouseX, mouseY)) {
            drawTooltipCompat(
                    ctx,
                    java.util.List.of(Text.literal("打开完整模型列表，也可手动填写模型 ID（留空=自动）")),
                    (int) mouseX,
                    (int) mouseY
            );
            return true;
        }
        if (llmProviderButton != null && llmProviderButton.isMouseOver(mouseX, mouseY)) {
            String p = (draftLlmProvider == null || draftLlmProvider.isBlank()) ? "auto" : draftLlmProvider.trim();
            drawTooltipCompat(
                    ctx,
                    java.util.List.of(Text.literal("LLM Provider（选择）"), Text.literal("当前：" + p)),
                    (int) mouseX,
                    (int) mouseY
            );
            return true;
        }
        if (llmBaseUrlPresetButton != null && llmBaseUrlPresetButton.isMouseOver(mouseX, mouseY)) {
            BaseUrlPreset p = getSelectedBaseUrlPreset();
            String name = (p == null) ? "自动" : p.label;
            String u = llmBaseUrlInput.getText().isBlank() ? "(自动)" : llmBaseUrlInput.getText();
            drawTooltipCompat(
                    ctx,
                    java.util.List.of(Text.literal("LLM Base URL（选择预设）"), Text.literal("当前：" + name), Text.literal("URL: " + u)),
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
        llmBaseUrlInput.setPlaceholder("https://服务地址/v1");
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

        modelChoicesButton = ButtonWidget.builder(Text.literal("选择 / 输入…"), b -> openModels())
                .dimensions(0, 0, 0, BUTTON_HEIGHT).build();

        llmProviderButton = ButtonWidget.builder(getLlmProviderButtonText(), b -> openProviders())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("选择 LLM Provider（DeepSeek 优先；其它 OpenAI-compatible 也可用）")))
                .build();

        llmBaseUrlPresetButton = ButtonWidget.builder(getBaseUrlPresetButtonText(), b -> openBaseUrlPresets())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("选择 Base URL 预设；下方地址始终可手动编辑")))
                .build();

        debugWarningsButton = ButtonWidget.builder(getDebugWarningsButtonText(), b -> toggleDebugWarnings())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("调试：将后端 debugWarnings 追加显示到聊天面板（便于调 prompt / 纠错链路）")))
                .build();

        searchProviderButton = ButtonWidget.builder(getSearchProviderButtonText(), b -> openSearchProviders())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("建筑研究联网搜索：选择搜索引擎或 API")))
                .build();

        testSearchKeyButton = ButtonWidget.builder(Text.literal("测试搜索 Key"), b -> startValidateSearchKey())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("调用后端 /search/validate 探测当前搜索引擎配置")))
                .build();

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

    private Text getSearchProviderButtonText() {
        String p = (draftSearchProvider == null || draftSearchProvider.isBlank()) ? "auto" : draftSearchProvider.trim();
        for (SettingsSearchProviders.Entry e : SettingsSearchProviders.CYCLE) {
            if (e.id().equalsIgnoreCase(p)) {
                return Text.literal("搜索：" + e.label());
            }
        }
        return Text.literal("搜索：" + p);
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
            var r = SettingsHttpProbe.get(httpClient, finalUrl, searchApiKey, Duration.ofSeconds(15));
            return new DetectResponse(r.status(), r.body());
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

    private void applyBaseUrlPreset(int idx) {
        if (idx < 0 || idx >= BASE_URL_PRESETS.size()) return;
        ConnectionIdentity before = currentConnectionIdentity();
        baseUrlPresetIndex = idx;

        BaseUrlPreset p = BASE_URL_PRESETS.get(idx);
        if (p.url == null) {
            draftLlmProvider = "openai";
            // A custom endpoint uses the OpenAI-compatible adapter and retains its address.
            llmBaseUrlInput.setFocused(true);
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

        if (!before.equals(currentConnectionIdentity())) syncConnectionIdentity();
        if (llmProviderButton != null) llmProviderButton.setMessage(getLlmProviderButtonText());
        if (llmBaseUrlPresetButton != null) llmBaseUrlPresetButton.setMessage(getBaseUrlPresetButtonText());
        if (p.local) {
            showToast("本地供应商：API Key 可留空", false);
        }

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

    private ConnectionIdentity currentConnectionIdentity() {
        return new ConnectionIdentity(orchestratorInput.getText().trim(), apiKeyInput.getText().trim(),
                draftLlmProvider == null ? "auto" : draftLlmProvider, llmBaseUrlInput.getText().trim());
    }

    private void syncConnectionIdentity() {
        ConnectionIdentity next = currentConnectionIdentity();
        if (next.equals(connectionIdentity)) return;
        boolean changed = connectionIdentity != null;
        connectionIdentity = next;
        connectionRevision++;
        detectingModel = false;
        validatingKey = false;
        if (testKeyButton != null) testKeyButton.setMessage(Text.literal("校验 Key"));
        availableModels = new ArrayList<>();
        availableModelsUpdatedAtMs = 0L;
        if (changed && !modelInput.getText().isBlank()) modelNeedsConfirmation = true;
        syncBaseUrlPresetFromValue(llmBaseUrlInput.getText());
    }

    @Override public boolean modelNeedsConfirmation() { return modelNeedsConfirmation; }

    private void blurInputs() {
        backendSection.blur();
        for (var input : List.of(orchestratorInput, apiKeyInput, llmBaseUrlInput, modelInput, searchApiKeyInput, googleCseCxInput)) input.setFocused(false);
    }

    private void openModels() {
        syncConnectionIdentity();
        blurInputs();
        choices.open("选择或输入模型 ID", availableModels.stream()
                .map(m -> new SettingsChoicePanel.Option(m, m)).toList(), modelInput.getText(), true, value -> {
            modelInput.setText(value);
            draftModel = value;
            modelNeedsConfirmation = false;
        });
    }

    private void openProviders() {
        blurInputs();
        var options = new ArrayList<SettingsChoicePanel.Option>();
        options.add(new SettingsChoicePanel.Option("openai", "自定义 / OpenAI 兼容"));
        for (var p : BASE_URL_PRESETS) if (p.provider != null)
            options.add(new SettingsChoicePanel.Option(p.provider, p.label));
        choices.open("选择供应商（保留当前 URL）", options, draftLlmProvider, false, value -> {
            draftLlmProvider = value;
            llmProviderButton.setMessage(getLlmProviderButtonText());
            syncConnectionIdentity();
        });
    }

    private void openBaseUrlPresets() {
        blurInputs();
        var options = new ArrayList<SettingsChoicePanel.Option>();
        for (var p : BASE_URL_PRESETS) options.add(new SettingsChoicePanel.Option(p.id, p.label));
        choices.open("填入地址预设", options, getSelectedBaseUrlPreset().id, false, value -> {
            for (int i = 0; i < BASE_URL_PRESETS.size(); i++) if (BASE_URL_PRESETS.get(i).id.equals(value)) { applyBaseUrlPreset(i); break; }
        });
    }

    private void openSearchProviders() {
        blurInputs();
        choices.open("选择搜索引擎", SettingsSearchProviders.CYCLE.stream()
                .map(p -> new SettingsChoicePanel.Option(p.id(), p.label())).toList(), draftSearchProvider, false, value -> {
            draftSearchProvider = value;
            searchProviderButton.setMessage(getSearchProviderButtonText());
        });
    }

    private void startDetectModel() {
        syncConnectionIdentity();
        if (detectingModel) return;
        long revision = connectionRevision;
        ConnectionIdentity identity = currentConnectionIdentity();
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
            showToast("Base URL 无效：请填写 HTTP(S) 服务地址（不含查询参数或片段）", true);
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
            var r = SettingsHttpProbe.get(httpClient, finalUrl, apiKey, Duration.ofSeconds(12));
            return new DetectResponse(r.status(), r.body());
        }).thenAccept(resp -> client.execute(() -> {
            if (revision != connectionRevision || !identity.equals(currentConnectionIdentity())) return;
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
            if (choices.isEditingValue()) choices.updateOptions(availableModels.stream()
                    .map(m -> new SettingsChoicePanel.Option(m, m)).toList());

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

            if (detectModelButton != null) detectModelButton.setMessage(getDetectModelButtonText());
        }));
    }

    private void startValidateKey() {
        syncConnectionIdentity();
        if (validatingKey) return;
        long revision = connectionRevision;
        ConnectionIdentity identity = currentConnectionIdentity();
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
            showToast("Base URL 无效：请填写 HTTP(S) 服务地址（不含查询参数或片段）", true);
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
            var r = SettingsHttpProbe.get(httpClient, finalUrl, apiKey, Duration.ofSeconds(10));
            return new DetectResponse(r.status(), r.body());
        }).thenAccept(resp -> client.execute(() -> {
            if (revision != connectionRevision || !identity.equals(currentConnectionIdentity())) return;
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
        syncConnectionIdentity();
        if (modelNeedsConfirmation) {
            showToast("连接已更改，请确认或重新填写 Model 后再保存", true);
            openModels();
            return false;
        }
        boolean saved = SettingsConfigCoordinator.save(this);
        if (saved) {
            connectionRevision++;
            connectionIdentity = currentConnectionIdentity();
            detectingModel = false;
        }
        return saved;
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
        syncConnectionIdentity();
        if (choices.isOpen()) { choices.charTyped(chr); return; }
        if (backendSection.focused()) { backendSection.charTyped(chr); return; }
        if (orchestratorInput.isFocused()) orchestratorInput.charTyped(chr);
        if (apiKeyInput.isFocused()) apiKeyInput.charTyped(chr);
        if (llmBaseUrlInput.isFocused()) llmBaseUrlInput.charTyped(chr);
        if (modelInput.isFocused()) {
            modelInput.charTyped(chr);
            draftModel = modelInput.getText() == null ? "" : modelInput.getText().trim();
            modelNeedsConfirmation = false;

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
        syncConnectionIdentity();
        if (choices.isOpen()) { choices.keyPressed(keyCode, modifiers); return; }
        if (backendSection.focused()) {
            backendSection.keyPressed(keyCode,modifiers);
            if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_TAB) backendSection.blur();
            return;
        }
        // 关键：要拿到 modifiers，才能支持 Ctrl+V / Shift 选区 等
        if (orchestratorInput.isFocused()) orchestratorInput.keyPressed(keyCode, modifiers);
        if (apiKeyInput.isFocused()) apiKeyInput.keyPressed(keyCode, modifiers);
        if (llmBaseUrlInput.isFocused()) llmBaseUrlInput.keyPressed(keyCode, modifiers);
        if (modelInput.isFocused()) {
            String previous = modelInput.getText();
            modelInput.keyPressed(keyCode, modifiers);
            draftModel = modelInput.getText().trim();
            if (!previous.equals(modelInput.getText())) modelNeedsConfirmation = false;

        }
        if (searchApiKeyInput.isFocused()) searchApiKeyInput.keyPressed(keyCode, modifiers);
        if (googleCseCxInput.isFocused()) googleCseCxInput.keyPressed(keyCode, modifiers);

        if (keyCode == GLFW.GLFW_KEY_TAB) {
            var inputs = List.of(orchestratorInput, apiKeyInput, llmBaseUrlInput, modelInput, searchApiKeyInput, googleCseCxInput);
            int focused = -1;
            for (int i = 0; i < inputs.size(); i++) if (inputs.get(i).isFocused()) focused = i;
            int next = Math.floorMod(focused + ((modifiers & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1), inputs.size());
            for (int i = 0; i < inputs.size(); i++) inputs.get(i).setFocused(i == next);
            return;
        }

        // Enter: 快速保存（可选，但很顺手）
        if (keyCode == GLFW.GLFW_KEY_ENTER) {
            saveSettings();
        }
    }

    @Override
    public boolean wantsKeyboardInput() {
        boolean baseUrlFocused = llmBaseUrlInput.isFocused();
        return choices.isOpen() || searchApiKeyInput.isFocused() || googleCseCxInput.isFocused() || backendSection.focused() || apiKeyInput.isFocused() || orchestratorInput.isFocused() || baseUrlFocused || modelInput.isFocused();
    }

    @Override public void loadBackendSettings() { backendSection.load(); }
    @Override public boolean saveBackendSettings() { return backendSection.save(); }

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
    public ButtonWidget modelChoicesButton() {
        return modelChoicesButton;
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
    public boolean isInteractiveArea(double mouseX, double mouseY) {
        return visible && (choices.isOpen() || super.isInteractiveArea(mouseX, mouseY));
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return super.isMouseOver(mouseX, mouseY) || choices.contains(mouseX, mouseY);
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
    }

    @Override
    public void setDraftInteractionReach(int reach) {
        this.draftInteractionReach = reach;
    }
}
