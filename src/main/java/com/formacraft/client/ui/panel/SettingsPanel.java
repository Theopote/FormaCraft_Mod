package com.formacraft.client.ui.panel;

import com.formacraft.FormacraftMod;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.client.ui.panel.settings.SettingsActionsSection;
import com.formacraft.client.ui.panel.settings.SettingsBaseUrlPresets;
import com.formacraft.client.ui.panel.settings.SettingsConnectionSection;
import com.formacraft.client.ui.panel.settings.SettingsModelCatalog;
import com.formacraft.client.ui.panel.settings.SettingsPanelDrawSupport;
import com.formacraft.client.ui.panel.settings.SettingsPanelRenderHost;
import com.formacraft.client.ui.panel.settings.SettingsPreferencesSection;
import com.formacraft.client.ui.widget.HudTextInput;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.input.MouseInput;
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

    private static final int BUTTON_GAP_SMALL = 4;

    // Toast 持续时间（毫秒）
    private static final long TOAST_DURATION_MS = 2500L;

    private final MinecraftClient client = MinecraftClient.getInstance();

    // 输入组件（HUD 模式，不依赖 Screen）
    private final HudTextInput orchestratorInput = new HudTextInput();
    private final HudTextInput apiKeyInput = new HudTextInput();
    private final HudTextInput llmBaseUrlInput = new HudTextInput();
    private final HudTextInput modelInput = new HudTextInput();
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
    private float draftTemperature = 0.7f;
    private int draftFontSize = 14;
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
    private ButtonWidget saveButton;
    private ButtonWidget cancelButton;
    private ButtonWidget resetButton;
    private ButtonWidget detectModelButton;
    private ButtonWidget autoModelButton;
    private ButtonWidget llmProviderButton;
    private ButtonWidget llmBaseUrlPresetButton;
    private ButtonWidget debugWarningsButton;
    private List<ButtonWidget> llmBaseUrlPresetOptionButtons;
    private TemperatureSlider temperatureSlider;
    private FontSizeSlider fontSizeSlider;
    private InteractionReachSlider interactionReachSlider;
    private SliderWidget activeSlider = null; // 只允许同时操作一个滑条

    // LLM Base URL：预设下拉（避免输入错误；需要特殊服务时选择“自定义”）
    private record BaseUrlPreset(String id, String label, String url) {}
    private static final List<BaseUrlPreset> BASE_URL_PRESETS = List.of(
            new BaseUrlPreset("auto", "自动（由 Provider 决定）", ""),
            new BaseUrlPreset("openai", "OpenAI", "https://api.openai.com/v1"),
            new BaseUrlPreset("deepseek", "DeepSeek", "https://api.deepseek.com/v1"),
            new BaseUrlPreset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1"),
            new BaseUrlPreset("groq", "Groq", "https://api.groq.com/openai/v1"),
            new BaseUrlPreset("together", "Together", "https://api.together.xyz/v1"),
            new BaseUrlPreset("ollama", "Ollama（本地）", "http://localhost:11434/v1"),
            new BaseUrlPreset("lmstudio", "LM Studio（本地）", "http://127.0.0.1:1234/v1"),
            new BaseUrlPreset("custom", "自定义…", null)
    );
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
        SettingsConfig.load();
        SettingsConfig cfg = SettingsConfig.INSTANCE;
        this.apiKeyInput.setText(cfg.apiKey != null ? cfg.apiKey : "");
        this.orchestratorInput.setText(cfg.orchestratorEndpoint != null ? cfg.orchestratorEndpoint : "http://localhost:8000");
        // UX：即便选择了隐藏，也在编辑时显示明文，避免“看不到输入内容”
        this.apiKeyInput.setPasswordMode(hideKey && !apiKeyInput.isFocused());
        this.apiKeyInput.setMaxLength(256);
        this.orchestratorInput.setMaxLength(256);
        this.llmBaseUrlInput.setMaxLength(256);
        this.modelInput.setMaxLength(128);

        // 同步草稿态：允许为空（自动），不再限制预设列表
        this.draftModel = cfg.model != null ? cfg.model.trim() : "";
        this.modelInput.setText(this.draftModel);
        this.draftLlmProvider = (cfg.llmProvider == null || cfg.llmProvider.isBlank()) ? "auto" : cfg.llmProvider.trim();
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
        this.draftLlmBaseUrl = sanitizedBaseUrl;
        this.llmBaseUrlInput.setText(this.draftLlmBaseUrl);
        syncBaseUrlPresetFromValue(this.draftLlmBaseUrl);
        this.draftShowDebugWarnings = cfg.showDebugWarnings;
        this.draftTemperature = clamp01(cfg.temperature);
        this.draftFontSize = clampInt(cfg.fontSize);
        this.draftInteractionReach = clampReach(cfg.interactionReach);
        
        // 更新缓存
        updateCachedTemperatureText();

        // 同步原版控件（如果已初始化）
        syncWidgetStateFromDraft();
    }

    private static float clamp01(float v) {
        if (v < 0.0f) return 0.0f;
        return Math.min(v, 1.0f);
    }

    private static int clampInt(int v) {
        if (v < MIN_FONT_SIZE) return MIN_FONT_SIZE;
        return Math.min(v, MAX_FONT_SIZE);
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

        // 给设置页加一层半透明底（否则标题/标签直接叠在世界上，容易“看不见”）
        ctx.fill(panelX + 1, getContentY(), panelX + panelWidth - 1, panelY + panelHeight - 1, 0x80101010);

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

            y = SettingsConnectionSection.drawSection(this, ctx, x, y, w);
            y = SettingsPreferencesSection.drawSection(this, ctx, x, y, w);
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

    private void drawToast(DrawContext ctx, int x, int y, int w) {
        if (toast == null) return;
        long now = System.currentTimeMillis();
        if (now > toastUntilMs) {
            toast = null;
            return;
        }
        
        // 计算渐隐动画（最后500ms渐隐）
        long remaining = toastUntilMs - now;
        int alpha = 255;
        if (remaining < 500) {
            alpha = (int) (255 * remaining / 500.0);
        }
        
        int color = isToastError ? COLOR_TOAST_ERROR : COLOR_TOAST_SUCCESS;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        int finalColor = (alpha << 24) | (r << 16) | (g << 8) | b;
        
        ctx.drawTextWithShadow(client.textRenderer, Text.literal(toast), x, y, finalColor);
    }

    // =======================
    //   Orchestrator 地址输入框
    // =======================
    private void drawOrchestratorField(DrawContext ctx, int x, int y, int w) {
        // 标题行（高度按 INPUT_HEIGHT 对齐）
        Text label = Text.translatable("formacraft.settings.backend_url");
        drawSmallLabel(ctx, label, x, y);
        y += LABEL_OFFSET; // 到输入行
        orchestratorInput.render(ctx, x, y, w, INPUT_HEIGHT);
    }

    // =======================
    //   API KEY 输入框
    // =======================
    private void drawApiKeyField(DrawContext ctx, int x, int y, int w) {
        ensureWidgets();

        // 标题行：只画标题（按钮放到输入框下方一行）
        Text label = Text.translatable("formacraft.settings.api_key");
        drawSmallLabel(ctx, label, x, y);

        // 输入框（第二行，全宽）
        int inputY = y + LABEL_OFFSET;
        apiKeyInput.setPasswordMode(hideKey && !apiKeyInput.isFocused());
        apiKeyInput.render(ctx, x, inputY, w, INPUT_HEIGHT);

        // 按钮行（第三行：隐藏/粘贴在输入框下方，平均分配）
        int gap = BUTTON_GAP_SMALL;
        int btnY = y + LABEL_OFFSET * 2;
        int btnW1 = (w - gap) / 2;
        int btnW2 = Math.max(0, w - gap - btnW1); // 兜住余数，保证右侧对齐

        showHideButton.setPosition(x, btnY);
        showHideButton.setWidth(btnW1);
        showHideButton.visible = true;
        showHideButton.active = true;
        showHideButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0.0f);

        pasteButton.setPosition(x + btnW1 + gap, btnY);
        pasteButton.setWidth(btnW2);
        pasteButton.visible = true;
        pasteButton.active = true;
        pasteButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0.0f);
    }

    // =======================
    //   模型选择（输入 + 刷新列表 + 下拉候选）
    // =======================
    private void drawModelField(DrawContext ctx, int x, int y, int w) {
        drawSmallLabel(ctx, Text.translatable("formacraft.settings.model"), x, y);
        y += LABEL_OFFSET;

        // 第二行：模型输入框（允许手动输入；留空=自动）
        modelInput.render(ctx, x, y, w, INPUT_HEIGHT);

        // 轻提示：空且未聚焦时显示“自动”
        if (!modelInput.isFocused()) {
            String t = modelInput.getText() == null ? "" : modelInput.getText().trim();
            if (t.isEmpty()) {
                ctx.drawTextWithShadow(client.textRenderer, Text.literal("自动（留空）"), x + 4, y + 4, 0xFF777777);
            }
        }

        // 第三行：刷新/自动按钮
        y += LABEL_OFFSET;
        ensureWidgets();
        int gap = BUTTON_GAP_SMALL;
        int btnW1 = (w - gap) / 2;
        int btnW2 = Math.max(0, w - gap - btnW1);

        detectModelButton.setMessage(getDetectModelButtonText());
        detectModelButton.setPosition(x, y);
        detectModelButton.setWidth(btnW1);
        detectModelButton.visible = true;
        detectModelButton.active = !detectingModel;
        detectModelButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0.0f);

        autoModelButton.setPosition(x + btnW1 + gap, y);
        autoModelButton.setWidth(btnW2);
        autoModelButton.visible = true;
        autoModelButton.active = true;
        autoModelButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0.0f);

        // 下拉列表（展开渲染，不改变布局高度）
        if (modelDropdownOpen) {
            pendingModelDropdownOverlay = true;
            pendingModelDropdownX = x;
            pendingModelDropdownY = y - LABEL_OFFSET + INPUT_HEIGHT; // 紧贴输入框下方
            pendingModelDropdownW = w;
        } else {
            hideModelOptionButtons();
        }
    }

    private void renderModelDropdownOverlay(DrawContext ctx) {
        if (!modelDropdownOpen) {
            hideModelOptionButtons();
            return;
        }
        if (!pendingModelDropdownOverlay) {
            hideModelOptionButtons();
            return;
        }
        layoutModelOptionButtons(pendingModelDropdownX, pendingModelDropdownY, pendingModelDropdownW);
        for (ButtonWidget b : modelOptionButtons) {
            if (b.visible) b.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0.0f);
        }
    }

    // =======================
    //   LLM Provider & Base URL
    // =======================
    private void drawLlmProviderField(DrawContext ctx, int x, int y, int w) {
        ensureWidgets();
        drawSmallLabel(ctx, Text.literal("LLM Provider"), x, y);
        y += LABEL_OFFSET;

        llmProviderButton.setMessage(getLlmProviderButtonText());
        llmProviderButton.setPosition(x, y);
        llmProviderButton.setWidth(w);
        llmProviderButton.visible = true;
        llmProviderButton.active = true;
        llmProviderButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0.0f);
    }

    private void drawLlmBaseUrlField(DrawContext ctx, int x, int y, int w) {
        drawSmallLabel(ctx, Text.literal("LLM Base URL"), x, y);
        y += LABEL_OFFSET;

        ensureWidgets();
        llmBaseUrlPresetButton.setMessage(getBaseUrlPresetButtonText());
        llmBaseUrlPresetButton.setPosition(x, y);
        llmBaseUrlPresetButton.setWidth(w);
        llmBaseUrlPresetButton.visible = true;
        llmBaseUrlPresetButton.active = true;
        llmBaseUrlPresetButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0.0f);

        // 第三行：自定义输入 or 提示文本（非自定义禁止输入，避免手滑改错）
        y += LABEL_OFFSET;
        BaseUrlPreset p = getSelectedBaseUrlPreset();
        if (p != null && p.url == null) {
            llmBaseUrlInput.render(ctx, x, y, w, INPUT_HEIGHT);
        } else {
            // 预设模式下不再显示 URL（tooltip 已包含），仅在“自动”时给一个轻提示
            if (p == null || p.url.isBlank()) {
                ctx.drawTextWithShadow(client.textRenderer, Text.literal("自动：由 Provider/后端决定"), x, y + 4, COLOR_GRAY);
            }
            llmBaseUrlInput.setFocused(false);
        }

        // 下拉列表（展开渲染，不改变布局高度）
        if (baseUrlPresetDropdownOpen) {
            // 注意：不要在这里直接绘制（会被后续控件盖住）。改为记录 overlay 位置，最后统一绘制。
            pendingBaseUrlDropdownOverlay = true;
            pendingBaseUrlDropdownX = x;
            // 下拉应紧贴“预设按钮”下方（而不是在第三行下面），否则看起来会整体下移一行
            int presetBtnTopY = y - LABEL_OFFSET; // y 当前是第三行的 top
            pendingBaseUrlDropdownY = presetBtnTopY + BUTTON_HEIGHT;
            pendingBaseUrlDropdownW = w;
        } else {
            hideBaseUrlPresetButtons();
        }
    }

    // =======================
    //   Debug: show backend warnings
    // =======================
    private void drawDebugWarningsField(DrawContext ctx, int x, int y, int w) {
        ensureWidgets();
        drawSmallLabel(ctx, Text.literal("Debug Warnings"), x, y);
        y += LABEL_OFFSET;

        debugWarningsButton.setMessage(getDebugWarningsButtonText());
        debugWarningsButton.setPosition(x, y);
        debugWarningsButton.setWidth(w);
        debugWarningsButton.visible = true;
        debugWarningsButton.active = true;
        debugWarningsButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0.0f);
    }

    private void renderBaseUrlDropdownOverlay(DrawContext ctx) {
        if (!baseUrlPresetDropdownOpen) {
            hideBaseUrlPresetButtons();
            return;
        }
        if (!pendingBaseUrlDropdownOverlay) {
            // 保险：如果本帧没有布局信息，至少隐藏，避免残留
            hideBaseUrlPresetButtons();
            return;
        }

        layoutBaseUrlPresetButtons(pendingBaseUrlDropdownX, pendingBaseUrlDropdownY, pendingBaseUrlDropdownW);
        for (ButtonWidget b : llmBaseUrlPresetOptionButtons) {
            if (b.visible) {
                b.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0.0f);
            }
        }
    }

    // =======================
    //   温度滑动条
    // =======================
    private void drawTemperatureSlider(DrawContext ctx, int x, int y, int w) {
        // 使用缓存的文本（性能优化）
        if (cachedTemperatureText == null) {
            updateCachedTemperatureText();
        }
        drawSmallLabel(ctx, Text.translatable("formacraft.settings.temperature", cachedTemperatureText), x, y);
        y += LABEL_OFFSET;
        ensureWidgets();
        temperatureSlider.setPosition(x, y);
        temperatureSlider.setWidth(w);
        temperatureSlider.visible = true;
        temperatureSlider.active = true;
        temperatureSlider.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0.0f);
    }

    // =======================
    //   操作距离（光标交互距离）
    // =======================
    private void drawInteractionReachSlider(DrawContext ctx, int x, int y, int w) {
        drawSmallLabel(ctx, Text.translatable("formacraft.settings.interaction_reach", draftInteractionReach), x, y);
        y += LABEL_OFFSET;
        ensureWidgets();
        interactionReachSlider.setPosition(x, y);
        interactionReachSlider.setWidth(w);
        interactionReachSlider.visible = true;
        interactionReachSlider.active = true;
        interactionReachSlider.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0.0f);
    }

    // =======================
    //   字体大小
    // =======================
    private void drawFontSizeSlider(DrawContext ctx, int x, int y, int w) {
        drawSmallLabel(ctx, Text.translatable("formacraft.settings.font_size", draftFontSize), x, y);
        y += LABEL_OFFSET;
        ensureWidgets();
        fontSizeSlider.setPosition(x, y);
        fontSizeSlider.setWidth(w);
        fontSizeSlider.visible = true;
        fontSizeSlider.active = true;
        fontSizeSlider.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0.0f);
    }

    // =======================
    //   保存按钮
    // =======================
    private void drawButtonsRow(DrawContext ctx, int x, int y, int w) {
        ensureWidgets();
        int btnW1 = (w - BUTTON_GAP * 2) / 3;
        int btnW3 = Math.max(0, w - (btnW1 + btnW1 + BUTTON_GAP * 2)); // 兜住余数，保证右侧对齐
        int cancelX = x + btnW1 + BUTTON_GAP;
        int resetX = cancelX + btnW1 + BUTTON_GAP;

        saveButton.setPosition(x, y);
        saveButton.setWidth(btnW1);
        saveButton.visible = true;
        saveButton.active = true;
        saveButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0.0f);

        cancelButton.setPosition(cancelX, y);
        cancelButton.setWidth(btnW1);
        cancelButton.visible = true;
        cancelButton.active = true;
        cancelButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0.0f);

        resetButton.setPosition(resetX, y);
        resetButton.setWidth(btnW3);
        resetButton.visible = true;
        resetButton.active = true;
        resetButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0.0f);
    }

    /**
     * 获取缩放后的鼠标X坐标（性能优化：避免重复计算）
     */
    private double getScaledMouseX() {
        return client.mouse.getX() / client.getWindow().getScaleFactor();
    }

    /**
     * 获取缩放后的鼠标Y坐标（性能优化：避免重复计算）
     */
    private double getScaledMouseY() {
        return client.mouse.getY() / client.getWindow().getScaleFactor();
    }

    /**
     * 更新缓存的温度文本（性能优化）
     */
    private void updateCachedTemperatureText() {
        cachedTemperatureText = String.format("%.2f", draftTemperature);
    }

    /**
     * 统一“小标题”绘制：在 16px 行高内垂直居中（视觉间距一致）
     */
    private void drawSmallLabel(DrawContext ctx, Text label, int x, int y) {
        int labelY = y + (INPUT_HEIGHT - client.textRenderer.fontHeight) / 2;
        ctx.drawTextWithShadow(client.textRenderer, label, x, labelY, COLOR_GRAY);
    }

    // =======================
    //   鼠标交互
    // =======================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true; // 处理顶部 Tab 切换

        if (button != 0) return false;

        ensureWidgets();
        int x = cachedContentX >= 0 ? cachedContentX : (panelX + CONTENT_PADDING);
        int y = getContentY() + CONTENT_PADDING - scrollY;
        int w = panelWidth - CONTENT_PADDING * 2;

        boolean clickedOutside = !isMouseOver(mouseX, mouseY);
        // 防御：如果点击在面板外，不应继续命中任何控件（否则会“隔空点按钮/输入框”）
        if (clickedOutside) {
            orchestratorInput.setFocused(false);
            apiKeyInput.setFocused(false);
            llmBaseUrlInput.setFocused(false);
            modelInput.setFocused(false);
            modelDropdownOpen = false;
            hideModelOptionButtons();
            return false;
        }

        // drawContents 里先画标题，再 y += TITLE_HEIGHT
        y += TITLE_HEIGHT;

        // =========== Orchestrator 区域 ============
        int orchLabelY = y;
        int orchY = orchLabelY + LABEL_OFFSET;
        if (orchestratorInput.mouseClicked(mouseX, mouseY, x, orchY, w, INPUT_HEIGHT)) {
            apiKeyInput.setFocused(false);
            llmBaseUrlInput.setFocused(false);
            modelInput.setFocused(false);
            modelDropdownOpen = false;
            hideModelOptionButtons();
            return true;
        }

        // =========== API Key 区域 ============
        y += FIELD_SPACING;
        int apiLabelY = y;
        int apiY = apiLabelY + LABEL_OFFSET;
        int apiBtnY = apiLabelY + LABEL_OFFSET * 2;
        int gap = BUTTON_GAP_SMALL;
        int apiBtnW1 = (w - gap) / 2;
        int apiBtnW2 = Math.max(0, w - gap - apiBtnW1);
        int pasteX = x + apiBtnW1 + gap;

        Click click = new Click(mouseX, mouseY, new MouseInput(button, 0));

        // Show/Hide（原版按钮）
        showHideButton.setPosition(x, apiBtnY);
        showHideButton.setWidth(apiBtnW1);
        if (showHideButton.mouseClicked(click, false)) {
            modelInput.setFocused(false);
            modelDropdownOpen = false;
            hideModelOptionButtons();
            return true;
        }

        // Paste（原版按钮）
        pasteButton.setPosition(pasteX, apiBtnY);
        pasteButton.setWidth(apiBtnW2);
        if (pasteButton.mouseClicked(click, false)) {
            modelInput.setFocused(false);
            modelDropdownOpen = false;
            hideModelOptionButtons();
            return true;
        }

        // 点击 API key 输入框
        if (apiKeyInput.mouseClicked(mouseX, mouseY, x, apiY, w, INPUT_HEIGHT)) {
            orchestratorInput.setFocused(false);
            llmBaseUrlInput.setFocused(false);
            modelInput.setFocused(false);
            modelDropdownOpen = false;
            hideModelOptionButtons();
            return true;
        }

        // =========== LLM Provider ============
        // API Key 三行：标题 + 输入框 + 按钮行
        y += FIELD_SPACING + LABEL_OFFSET;
        int providerLabelY = y;
        int providerY = providerLabelY + LABEL_OFFSET;

        llmProviderButton.setPosition(x, providerY);
        llmProviderButton.setWidth(w);
        if (llmProviderButton.mouseClicked(click, false)) {
            orchestratorInput.setFocused(false);
            apiKeyInput.setFocused(false);
            llmBaseUrlInput.setFocused(false);
            modelInput.setFocused(false);
            modelDropdownOpen = false;
            hideModelOptionButtons();
            return true;
        }

        // =========== LLM Base URL ============
        y += FIELD_SPACING;
        int llmBaseUrlLabelY = y;
        int llmBaseUrlPresetY = llmBaseUrlLabelY + LABEL_OFFSET;
        int llmBaseUrlThirdLineY = llmBaseUrlLabelY + LABEL_OFFSET * 2;

        // 预设按钮（第二行）
        llmBaseUrlPresetButton.setPosition(x, llmBaseUrlPresetY);
        llmBaseUrlPresetButton.setWidth(w);
        if (llmBaseUrlPresetButton.mouseClicked(click, false)) {
            orchestratorInput.setFocused(false);
            apiKeyInput.setFocused(false);
            llmBaseUrlInput.setFocused(false);
            modelInput.setFocused(false);
            modelDropdownOpen = false;
            hideModelOptionButtons();
            return true;
        }

        // 下拉展开：命中选项
        if (baseUrlPresetDropdownOpen) {
            // 展开应紧贴预设按钮下方（与渲染 overlay 一致）
            int listY0 = llmBaseUrlPresetY + BUTTON_HEIGHT;
            layoutBaseUrlPresetButtons(x, listY0, w);

            boolean clickedAny = false;
            for (ButtonWidget opt : llmBaseUrlPresetOptionButtons) {
                if (opt != null && opt.visible && opt.mouseClicked(click, false)) {
                    clickedAny = true;
                    break;
                }
            }
            if (clickedAny) {
                return true;
            }

            int listH = BASE_URL_PRESETS.size() * BUTTON_HEIGHT;
            boolean insideList = (mouseX >= x && mouseX <= x + w && mouseY >= listY0 && mouseY <= listY0 + listH);
            if (!insideList) {
                // 点击列表外：收起（并吞掉点击，避免“穿透”点到下面控件）
                baseUrlPresetDropdownOpen = false;
                hideBaseUrlPresetButtons();
                modelInput.setFocused(false);
                modelDropdownOpen = false;
                hideModelOptionButtons();
                return true;
            }

            // 点在列表区域但没点到按钮：吞掉（避免误触其它控件）
            modelInput.setFocused(false);
            modelDropdownOpen = false;
            hideModelOptionButtons();
            return true;
        }

        // 第三行：只有自定义时才允许点输入框
        BaseUrlPreset p = getSelectedBaseUrlPreset();
        if (p != null && p.url == null) {
            if (llmBaseUrlInput.mouseClicked(mouseX, mouseY, x, llmBaseUrlThirdLineY, w, INPUT_HEIGHT)) {
                orchestratorInput.setFocused(false);
                apiKeyInput.setFocused(false);
                modelInput.setFocused(false);
                modelDropdownOpen = false;
                hideModelOptionButtons();
                return true;
            }
        } else {
            llmBaseUrlInput.setFocused(false);
        }

        // =========== Model（输入 + 刷新 + 自动） ============
        // Base URL 是三行（FIELD_SPACING + LABEL_OFFSET）
        y += FIELD_SPACING + LABEL_OFFSET;
        int modelLabelY = y;
        int modelInputY = modelLabelY + LABEL_OFFSET;
        int modelBtnY = modelLabelY + LABEL_OFFSET * 2;

        // 下拉展开：命中选项（优先处理，避免“穿透”）
        if (modelDropdownOpen) {
            int listY0 = modelInputY + INPUT_HEIGHT;
            layoutModelOptionButtons(x, listY0, w);

            boolean clickedAny = false;
            int visibleCount = 0;
            for (ButtonWidget opt : modelOptionButtons) {
                if (opt != null && opt.visible) {
                    visibleCount++;
                    if (opt.mouseClicked(click, false)) {
                        clickedAny = true;
                        break;
                    }
                }
            }
            if (clickedAny) return true;

            int listH = visibleCount * BUTTON_HEIGHT;
            boolean insideList = (mouseX >= x && mouseX <= x + w && mouseY >= listY0 && mouseY <= listY0 + listH);
            boolean insideInput = (mouseX >= x && mouseX <= x + w && mouseY >= modelInputY && mouseY <= modelInputY + INPUT_HEIGHT);
            if (!insideList && !insideInput) {
                // 点击列表外：收起并吞掉点击，避免“穿透”
                modelDropdownOpen = false;
                hideModelOptionButtons();
                return true;
            }
            // 点在列表区域但没点到按钮：吞掉
            if (insideList) return true;
        }

        // 点击模型输入框
        if (modelInput.mouseClicked(mouseX, mouseY, x, modelInputY, w, INPUT_HEIGHT)) {
            orchestratorInput.setFocused(false);
            apiKeyInput.setFocused(false);
            llmBaseUrlInput.setFocused(false);
            // 输入框聚焦时，如果有候选则展开
            modelDropdownOpen = !availableModels.isEmpty();
            draftModel = modelInput.getText() == null ? "" : modelInput.getText().trim();
            return true;
        }

        int gap2 = BUTTON_GAP_SMALL;
        int modelBtnW1 = (w - gap2) / 2;
        int modelBtnW2 = Math.max(0, w - gap2 - modelBtnW1);

        detectModelButton.setPosition(x, modelBtnY);
        detectModelButton.setWidth(modelBtnW1);
        if (detectModelButton.mouseClicked(click, false)) {
            orchestratorInput.setFocused(false);
            apiKeyInput.setFocused(false);
            llmBaseUrlInput.setFocused(false);
            modelInput.setFocused(false);
            modelDropdownOpen = false;
            hideModelOptionButtons();
            return true;
        }

        autoModelButton.setPosition(x + modelBtnW1 + gap2, modelBtnY);
        autoModelButton.setWidth(modelBtnW2);
        if (autoModelButton.mouseClicked(click, false)) {
            orchestratorInput.setFocused(false);
            apiKeyInput.setFocused(false);
            llmBaseUrlInput.setFocused(false);
            modelInput.setFocused(false);
            modelDropdownOpen = false;
            hideModelOptionButtons();
            return true;
        }

        // =========== 滑动条交互 ============
        // 注意：这里必须与 drawContents 的布局一致
        // Model 为三行（FIELD_SPACING + LABEL_OFFSET）
        y += FIELD_SPACING + LABEL_OFFSET;

        // =========== Debug Warnings（toggle） ============
        int dbgBtnY = y + LABEL_OFFSET;
        debugWarningsButton.setPosition(x, dbgBtnY);
        debugWarningsButton.setWidth(w);
        if (debugWarningsButton.mouseClicked(click, false)) {
            orchestratorInput.setFocused(false);
            apiKeyInput.setFocused(false);
            llmBaseUrlInput.setFocused(false);
            modelInput.setFocused(false);
            modelDropdownOpen = false;
            hideModelOptionButtons();
            return true;
        }

        // Debug Warnings 是两行（标题+按钮）
        y += FIELD_SPACING;

        int reachSliderY = y + LABEL_OFFSET;
        interactionReachSlider.setPosition(x, reachSliderY);
        interactionReachSlider.setWidth(w);
        if (interactionReachSlider.mouseClicked(click, false)) {
            orchestratorInput.setFocused(false);
            apiKeyInput.setFocused(false);
            activeSlider = interactionReachSlider;
            return true;
        }

        y += FIELD_SPACING;
        int tempSliderY = y + LABEL_OFFSET;
        temperatureSlider.setPosition(x, tempSliderY);
        temperatureSlider.setWidth(w);
        if (temperatureSlider.mouseClicked(click, false)) {
            orchestratorInput.setFocused(false);
            apiKeyInput.setFocused(false);
            activeSlider = temperatureSlider;
            return true;
        }

        y += FIELD_SPACING;
        int fontSliderY = y + LABEL_OFFSET;
        fontSizeSlider.setPosition(x, fontSliderY);
        fontSizeSlider.setWidth(w);
        if (fontSizeSlider.mouseClicked(click, false)) {
            orchestratorInput.setFocused(false);
            apiKeyInput.setFocused(false);
            activeSlider = fontSizeSlider;
            return true;
        }

        // =========== 按钮行（Save/Cancel/Reset） ============
        y += FIELD_SPACING;
        int btnY = y;
        int btnW1 = (w - BUTTON_GAP * 2) / 3;
        int btnW3 = Math.max(0, w - (btnW1 + btnW1 + BUTTON_GAP * 2));
        int cancelX = x + btnW1 + BUTTON_GAP;
        int resetX = cancelX + btnW1 + BUTTON_GAP;

        saveButton.setPosition(x, btnY);
        saveButton.setWidth(btnW1);
        if (saveButton.mouseClicked(click, false)) return true;

        cancelButton.setPosition(cancelX, btnY);
        cancelButton.setWidth(btnW1);
        if (cancelButton.mouseClicked(click, false)) return true;

        resetButton.setPosition(resetX, btnY);
        resetButton.setWidth(btnW3);
        return resetButton.mouseClicked(click, false);

        // 点击空白区域：取消焦点
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        ensureWidgets();
        if (button != 0) return false;
        Click click = new Click(mouseX, mouseY, new MouseInput(button, 0));
        // 只允许拖动一个滑条；且仅当鼠标位于滑条上时才更新（用户期望）
        if (activeSlider == null) return false;
        if (!activeSlider.isMouseOver(mouseX, mouseY)) return false;
        return activeSlider.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        ensureWidgets();
        if (button != 0) return false;
        Click click = new Click(mouseX, mouseY, new MouseInput(button, 0));
        boolean handled = false;
        if (activeSlider != null) {
            handled = activeSlider.mouseReleased(click);
            activeSlider = null;
        } else {
            // 兜底：如果未记录 activeSlider，也尝试释放两者，防止残留拖拽状态
            if (temperatureSlider != null) handled |= temperatureSlider.mouseReleased(click);
            if (fontSizeSlider != null) handled |= fontSizeSlider.mouseReleased(click);
            if (interactionReachSlider != null) handled |= interactionReachSlider.mouseReleased(click);
        }
        return handled;
    }

    @Override
    public void mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!isMouseOver(mouseX, mouseY)) return;

        // 输入框优先：在输入框上滚动时接管（水平滚动查看被截断内容）
        int x = cachedContentX >= 0 ? cachedContentX : (panelX + CONTENT_PADDING);
        int y = getContentY() + CONTENT_PADDING - scrollY;
        int w = panelWidth - CONTENT_PADDING * 2;

        // drawContents 里先画标题，再 y += TITLE_HEIGHT
        y += TITLE_HEIGHT;

        // Orchestrator 输入框（第二行）
        int orchY = y + LABEL_OFFSET;
        if (orchestratorInput.mouseScrolled(mouseX, mouseY, amount, x, orchY, w, INPUT_HEIGHT)) {
            return;
        }

        // API Key 输入框（第二行；该字段为三行，但输入框仍在标题行下方一行）
        y += FIELD_SPACING;
        int apiY = y + LABEL_OFFSET;
        if (apiKeyInput.mouseScrolled(mouseX, mouseY, amount, x, apiY, w, INPUT_HEIGHT)) {
            return;
        }

        // LLM Base URL：仅“自定义”时允许在输入框上滚动（水平滚动查看被截断内容）
        y += FIELD_SPACING + LABEL_OFFSET; // 跳到 Provider 区块起点
        y += FIELD_SPACING;                // 跳过 Provider（label+button）
        BaseUrlPreset p = getSelectedBaseUrlPreset();
        if (p != null && p.url == null) {
            int baseUrlY = y + LABEL_OFFSET * 2; // BaseURL 第三行
            if (llmBaseUrlInput.mouseScrolled(mouseX, mouseY, amount, x, baseUrlY, w, INPUT_HEIGHT)) {
                return;
            }
        }

        // Model 输入框（允许水平滚动查看被截断内容）
        y += FIELD_SPACING + LABEL_OFFSET; // 跳到 Model 区块起点（BaseURL 是三行）
        int modelY = y + LABEL_OFFSET;
        if (modelInput.mouseScrolled(mouseX, mouseY, amount, x, modelY, w, INPUT_HEIGHT)) {
            return;
        }

        // 否则：滚动面板内容
        int step = 12;
        scrollY = (int) Math.round(scrollY - amount * step);
        if (scrollY < 0) scrollY = 0;
        if (scrollY > maxScrollY) scrollY = maxScrollY;
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
        if (temperatureSlider != null && temperatureSlider.isMouseOver(mouseX, mouseY)) {
            drawTooltipCompat(ctx, java.util.Collections.singletonList(Text.translatable("formacraft.settings.tooltip.temperature")), (int) mouseX, (int) mouseY);
            return true;
        }
        if (fontSizeSlider != null && fontSizeSlider.isMouseOver(mouseX, mouseY)) {
            drawTooltipCompat(ctx, java.util.Collections.singletonList(Text.translatable("formacraft.settings.tooltip.font_size")), (int) mouseX, (int) mouseY);
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

    private void ensureWidgets() {
        if (showHideButton == null) {
            initWidgets();
        }
    }

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
        temperatureSlider = new TemperatureSlider(0, 0, 0, INPUT_HEIGHT, Text.empty(), clamp01(draftTemperature));
        fontSizeSlider = new FontSizeSlider(0, 0, 0, INPUT_HEIGHT, Text.empty(), fontSizeToValue(draftFontSize));
        interactionReachSlider = new InteractionReachSlider(0, 0, 0, INPUT_HEIGHT, Text.empty(), reachToValue(draftInteractionReach));
        temperatureSlider.setTooltip(Tooltip.of(Text.translatable("formacraft.settings.tooltip.temperature")));
        fontSizeSlider.setTooltip(Tooltip.of(Text.translatable("formacraft.settings.tooltip.font_size")));
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
        showToast("DebugWarnings=" + (draftShowDebugWarnings ? "ON" : "OFF"), false);
    }

    private void hideModelOptionButtons() {
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

    private void layoutModelOptionButtons(int x, int y0, int w) {
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

    private void hideBaseUrlPresetButtons() {
        if (llmBaseUrlPresetOptionButtons == null) return;
        for (ButtonWidget b : llmBaseUrlPresetOptionButtons) {
            b.visible = false;
        }
    }

    private void layoutBaseUrlPresetButtons(int x, int y0, int w) {
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
        String cur = (draftLlmProvider == null || draftLlmProvider.isBlank()) ? "auto" : draftLlmProvider.trim().toLowerCase();
        String[] order = new String[]{"auto", "deepseek", "openai", "openai_compat", "ollama"};
        int idx = 0;
        for (int i = 0; i < order.length; i++) {
            if (order[i].equals(cur)) {
                idx = i;
                break;
            }
        }
        String next = order[(idx + 1) % order.length];
        draftLlmProvider = next;

        // 友好默认：切到指定 Provider 时，自动填 baseUrl（用户可手动改）
        if ("deepseek".equals(next) && (draftLlmBaseUrl == null || draftLlmBaseUrl.isBlank())) {
            draftLlmBaseUrl = "https://api.deepseek.com/v1";
        }
        if ("openai".equals(next) && (draftLlmBaseUrl == null || draftLlmBaseUrl.isBlank())) {
            draftLlmBaseUrl = "https://api.openai.com/v1";
        }
        llmBaseUrlInput.setText(draftLlmBaseUrl != null ? draftLlmBaseUrl : "");
        syncBaseUrlPresetFromValue(draftLlmBaseUrl);

        if (llmProviderButton != null) llmProviderButton.setMessage(getLlmProviderButtonText());
        // Provider 切换后，旧的模型列表很可能不适用：清空缓存，等用户点击“刷新模型列表”
        availableModels = new ArrayList<>();
        availableModelsUpdatedAtMs = 0L;
        modelDropdownOpen = false;
        hideModelOptionButtons();
        showToast("LLM Provider: " + next, false);
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
            // custom: keep input as-is; allow editing
            llmBaseUrlInput.setFocused(true);
            currentFocusIndex = FOCUS_LLM_BASEURL;
        } else {
            draftLlmBaseUrl = p.url;
            llmBaseUrlInput.setText(p.url);
            llmBaseUrlInput.setFocused(false);
        }

        if (llmBaseUrlPresetButton != null) llmBaseUrlPresetButton.setMessage(getBaseUrlPresetButtonText());
        // Base URL 切换后，模型列表很可能不适用：清空缓存，等用户点击“刷新模型列表”
        availableModels = new ArrayList<>();
        availableModelsUpdatedAtMs = 0L;
        modelDropdownOpen = false;
        hideModelOptionButtons();
    }

    private void syncBaseUrlPresetFromValue(String baseUrl) {
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

    private void toggleHideKey() {
        hideKey = !hideKey;
        apiKeyInput.setPasswordMode(hideKey && !apiKeyInput.isFocused());
        if (showHideButton != null) {
            showHideButton.setMessage(getShowHideText());
        }
    }

    private Text getShowHideText() {
        return hideKey
                ? Text.translatable("formacraft.settings.show")
                : Text.translatable("formacraft.settings.hide");
    }

    private void syncWidgetStateFromDraft() {
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
        if (fontSizeSlider != null) {
            fontSizeSlider.setCustomValue(fontSizeToValue(draftFontSize));
        }
    }

    private static double fontSizeToValue(int fontSize) {
        int clamped = Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, fontSize));
        return (clamped - MIN_FONT_SIZE) / (double) (MAX_FONT_SIZE - MIN_FONT_SIZE);
    }

    private static int valueToFontSize(double value) {
        double v = Math.max(0.0, Math.min(1.0, value));
        return MIN_FONT_SIZE + (int) Math.round(v * (MAX_FONT_SIZE - MIN_FONT_SIZE));
    }

    private static double reachToValue(int reach) {
        int clamped = Math.max(MIN_INTERACTION_REACH, Math.min(MAX_INTERACTION_REACH, reach));
        return (clamped - MIN_INTERACTION_REACH) / (double) (MAX_INTERACTION_REACH - MIN_INTERACTION_REACH);
    }

    private static int valueToReach(double value) {
        double v = Math.max(0.0, Math.min(1.0, value));
        return MIN_INTERACTION_REACH + (int) Math.round(v * (MAX_INTERACTION_REACH - MIN_INTERACTION_REACH));
    }

    private class TemperatureSlider extends SliderWidget {
        public TemperatureSlider(int x, int y, int width, int height, Text message, double value) {
            super(x, y, width, height, message, value);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            // 在滑块内部只显示数值，标题仍由上方 label 绘制
            setMessage(Text.literal(cachedTemperatureText != null ? cachedTemperatureText : ""));
        }

        @Override
        protected void applyValue() {
            draftTemperature = clamp01((float) this.value);
            updateCachedTemperatureText();
            updateMessage();
        }

        public void setCustomValue(double value) {
            this.value = Math.max(0.0, Math.min(1.0, value));
            applyValue();
        }
    }

    private class FontSizeSlider extends SliderWidget {
        public FontSizeSlider(int x, int y, int width, int height, Text message, double value) {
            super(x, y, width, height, message, value);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.valueOf(draftFontSize)));
        }

        @Override
        protected void applyValue() {
            draftFontSize = clampInt(valueToFontSize(this.value));
            updateMessage();
        }

        public void setCustomValue(double value) {
            this.value = Math.max(0.0, Math.min(1.0, value));
            applyValue();
        }
    }

    private class InteractionReachSlider extends SliderWidget {
        public InteractionReachSlider(int x, int y, int width, int height, Text message, double value) {
            super(x, y, width, height, message, value);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.valueOf(draftInteractionReach)));
        }

        @Override
        protected void applyValue() {
            draftInteractionReach = clampReach(valueToReach(this.value));
            updateMessage();
        }

        public void setCustomValue(double value) {
            this.value = Math.max(0.0, Math.min(1.0, value));
            applyValue();
        }
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
     * 验证API Key格式（基础检查）
     */
    private static boolean isValidApiKey(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return false;
        }
        // 基础检查：长度和字符
        String trimmed = apiKey.trim();
        return trimmed.length() >= 10 && trimmed.length() <= 256;
        // 可以添加更多格式检查，例如OpenAI key以"sk-"开头
        // if (trimmed.startsWith("sk-")) { ... }
    }

    /**
     * 保存设置（带验证）
     */
    private boolean saveSettings() {
        String apiKey = apiKeyInput.getText();
        String endpoint = sanitizeEndpoint(orchestratorInput.getText());
        String llmBaseUrlRaw = llmBaseUrlInput.getText() != null ? llmBaseUrlInput.getText().trim() : "";
        String llmBaseUrl = SettingsModelCatalog.sanitizeLlmBaseUrlOrNull(llmBaseUrlRaw);
        if (llmBaseUrl == null && !llmBaseUrlRaw.isBlank()) {
            showToast("LLM Base URL 无效：必须以 http:// 或 https:// 开头", true);
            return false;
        }
        String provider = (draftLlmProvider == null || draftLlmProvider.isBlank()) ? "auto" : draftLlmProvider.trim();
        // 模型以输入框为准（留空=自动）
        draftModel = modelInput.getText() == null ? "" : modelInput.getText().trim();

        // 验证 API Key：DeepSeek/OpenAI 等通常需要；本地 ollama 可不填
        boolean requiresKey = true;
        String p = provider.toLowerCase();
        if ("ollama".equals(p)) requiresKey = false;
        if (!requiresKey && (apiKey == null || apiKey.trim().isEmpty())) {
            // ok
        } else if (requiresKey && !isValidApiKey(apiKey)) {
            showToast(Text.translatable("formacraft.settings.error.api_key_empty").getString(), true);
            return false;
        }

        try {
            SettingsConfig.INSTANCE.apiKey = apiKey;
            SettingsConfig.INSTANCE.orchestratorEndpoint = endpoint;
            SettingsConfig.INSTANCE.model = draftModel != null ? draftModel.trim() : "";
            SettingsConfig.INSTANCE.llmProvider = provider;
            SettingsConfig.INSTANCE.llmBaseUrl = llmBaseUrl;
            SettingsConfig.INSTANCE.showDebugWarnings = draftShowDebugWarnings;
            SettingsConfig.INSTANCE.temperature = clamp01(draftTemperature);
            SettingsConfig.INSTANCE.fontSize = clampInt(draftFontSize);
            SettingsConfig.INSTANCE.interactionReach = clampReach(draftInteractionReach);
            SettingsConfig.save();
            loadFromConfig(); // 重新加载以确保同步
            showToast(Text.translatable("formacraft.settings.saved").getString(), false);
            return true;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "unknown" : e.getMessage();
            showToast(Text.translatable("formacraft.settings.error.save_failed", msg).getString(), true);
            return false;
        }
    }

    private void showToast(String msg, boolean isError) {
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
    public int draftFontSize() {
        return draftFontSize;
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
    public SliderWidget temperatureSlider() {
        return temperatureSlider;
    }

    @Override
    public SliderWidget interactionReachSlider() {
        return interactionReachSlider;
    }

    @Override
    public SliderWidget fontSizeSlider() {
        return fontSizeSlider;
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
}
