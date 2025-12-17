package com.formacraft.client.ui.panel;

import com.formacraft.config.SettingsConfig;
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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * FormaCraft 设置面板（HUD 左侧栏）
 * 
 * Settings panel for FormaCraft HUD overlay.
 * Handles configuration for API keys, model selection, temperature, and font size.
 */
public class SettingsPanel extends BasePanel {

    // ======================= 常量定义 =======================
    private static final int CONTENT_PADDING = 10;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 6;
    private static final int INPUT_HEIGHT = 16;
    private static final int LABEL_OFFSET = 12;
    private static final int FIELD_SPACING = 32;
    private static final int TITLE_HEIGHT = 20;
    private static final int BUTTON_ROW_HEIGHT = 24;
    
    // 颜色常量
    private static final int COLOR_WHITE = 0xFFFFFF;
    private static final int COLOR_GRAY = 0xAAAAAA;
    private static final int COLOR_TOAST_SUCCESS = 0x88FF88;
    private static final int COLOR_TOAST_ERROR = 0xFF8888;
    
    // 字体大小范围
    private static final int MIN_FONT_SIZE = 8;
    private static final int MAX_FONT_SIZE = 26;
    
    // 按钮尺寸
    private static final int SHOW_HIDE_BUTTON_WIDTH = 40;
    private static final int PASTE_BUTTON_WIDTH = 44;
    private static final int BUTTON_GAP_SMALL = 4;
    
    // 下拉菜单
    // 旧的自绘下拉选项高度常量已移除（现使用原版 ButtonWidget 高度）
    
    // Toast 持续时间（毫秒）
    private static final long TOAST_DURATION_MS = 2500L;

    private final MinecraftClient client = MinecraftClient.getInstance();

    // 输入组件（HUD 模式，不依赖 Screen）
    private final HudTextInput orchestratorInput = new HudTextInput();
    private final HudTextInput apiKeyInput = new HudTextInput();
    // 默认显示明文（用户可以手动点 Hide 隐藏）
    private boolean hideKey = false;

    // 下拉列表状态
    private boolean modelDropdownOpen = false;
    private final List<String> modelOptions = Arrays.asList(
            "gpt-4o",
            "gpt-4o-mini",
            "gpt-4o-micro",
            "llama3-8b",
            "llama3-70b",
            "deepseek-chat"
    );

    // 草稿态（UI）——只有 Save 时才写入 SettingsConfig
    private String draftModel = "gpt-4o";
    private float draftTemperature = 0.7f;
    private int draftFontSize = 14;

    // 简易提示（保存成功/失败）
    private String toast = null;
    private long toastUntilMs = 0L;
    private boolean isToastError = false;

    // 焦点管理（用于Tab切换）
    private int currentFocusIndex = 0; // 0=orchestrator, 1=apiKey
    private static final int FOCUS_ORCHESTRATOR = 0;
    private static final int FOCUS_API_KEY = 1;

    // 原版风格控件（参考 Pushdozer：ButtonWidget / SliderWidget）
    private ButtonWidget showHideButton;
    private ButtonWidget pasteButton;
    private ButtonWidget saveButton;
    private ButtonWidget cancelButton;
    private ButtonWidget resetButton;
    private ButtonWidget modelButton;
    private final List<ButtonWidget> modelOptionButtons = new ArrayList<>();
    private TemperatureSlider temperatureSlider;
    private FontSizeSlider fontSizeSlider;
    private SliderWidget activeSlider = null; // 只允许同时操作一个滑条

    // 缓存的计算值（性能优化）
    private int cachedContentX = -1;
    private int cachedContentWidth = -1;
    private String cachedTemperatureText = null;

    public SettingsPanel() {
        loadFromConfig();
        initWidgets();
    }

    private void loadFromConfig() {
        SettingsConfig.load();
        SettingsConfig cfg = SettingsConfig.INSTANCE;
        this.apiKeyInput.setText(cfg.apiKey != null ? cfg.apiKey : "");
        this.orchestratorInput.setText(cfg.orchestratorEndpoint != null ? cfg.orchestratorEndpoint : "http://localhost:8000");
        this.apiKeyInput.setPasswordMode(hideKey);
        this.apiKeyInput.setMaxLength(256);
        this.orchestratorInput.setMaxLength(256);

        // 同步草稿态，添加验证确保有效性
        this.draftModel = (cfg.model != null && !cfg.model.isBlank() && modelOptions.contains(cfg.model)) 
                ? cfg.model : "gpt-4o";
        this.draftTemperature = clamp01(cfg.temperature);
        this.draftFontSize = clampInt(cfg.fontSize);
        
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
        if (v < SettingsPanel.MIN_FONT_SIZE) return SettingsPanel.MIN_FONT_SIZE;
        return Math.min(v, SettingsPanel.MAX_FONT_SIZE);
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
        int y = getContentY() + CONTENT_PADDING;
        int w = panelWidth - CONTENT_PADDING * 2;

        // 给设置页加一层半透明底（否则标题/标签直接叠在世界上，容易“看不见”）
        ctx.fill(panelX + 1, getContentY(), panelX + panelWidth - 1, panelY + panelHeight - 1, 0x80101010);

        // 标题
        ctx.drawTextWithShadow(client.textRenderer,
                Text.translatable("formacraft.settings.title"),
                x, y, COLOR_WHITE);
        y += TITLE_HEIGHT;

        drawOrchestratorField(ctx, x, y, w);
        y += FIELD_SPACING;

        drawApiKeyField(ctx, x, y, w);
        y += FIELD_SPACING;

        drawModelSelector(ctx, x, y, w);
        // 模型选择：按钮本体 1 行 +（展开时）额外 N 行
        y += FIELD_SPACING + (modelDropdownOpen ? (modelOptions.size() * BUTTON_HEIGHT) : 0);

        drawTemperatureSlider(ctx, x, y, w);
        y += (LABEL_OFFSET + INPUT_HEIGHT);

        drawFontSizeSlider(ctx, x, y, w);
        y += (LABEL_OFFSET + INPUT_HEIGHT);

        drawButtonsRow(ctx, x, y, w);
        y += BUTTON_ROW_HEIGHT;

        drawToast(ctx, x, y, w);
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
        ctx.drawTextWithShadow(client.textRenderer, 
                Text.translatable("formacraft.settings.backend_url"),
                x, y, COLOR_GRAY);
        y += LABEL_OFFSET;
        orchestratorInput.render(ctx, x, y, w, INPUT_HEIGHT);
    }

    // =======================
    //   API KEY 输入框
    // =======================
    private void drawApiKeyField(DrawContext ctx, int x, int y, int w) {
        ctx.drawTextWithShadow(client.textRenderer, 
                Text.translatable("formacraft.settings.api_key"),
                x, y, COLOR_GRAY);
        y += LABEL_OFFSET;

        ensureWidgets();

        int inputW = Math.max(0, w - SHOW_HIDE_BUTTON_WIDTH - PASTE_BUTTON_WIDTH - BUTTON_GAP_SMALL * 2);

        // 输入框（右侧留出 Show/Hide + Paste 按钮区域）
        apiKeyInput.setPasswordMode(hideKey);
        apiKeyInput.render(ctx, x, y, inputW, INPUT_HEIGHT);

        // Show/Hide 按钮
        int btnX = x + inputW + BUTTON_GAP_SMALL;
        showHideButton.setPosition(btnX, y);
        showHideButton.setWidth(SHOW_HIDE_BUTTON_WIDTH);
        showHideButton.visible = true;
        showHideButton.active = true;
        showHideButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0.0f);

        // Paste 按钮
        int pasteX = btnX + SHOW_HIDE_BUTTON_WIDTH + BUTTON_GAP_SMALL;
        pasteButton.setPosition(pasteX, y);
        pasteButton.setWidth(PASTE_BUTTON_WIDTH);
        pasteButton.visible = true;
        pasteButton.active = true;
        pasteButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0.0f);
    }

    // =======================
    //   模型选择（下拉菜单）
    // =======================
    private void drawModelSelector(DrawContext ctx, int x, int y, int w) {
        ctx.drawTextWithShadow(client.textRenderer, 
                Text.translatable("formacraft.settings.model"),
                x, y, COLOR_GRAY);
        y += LABEL_OFFSET;

        ensureWidgets();
        // 主按钮（原版 ButtonWidget 渲染）
        modelButton.setPosition(x, y);
        modelButton.setWidth(w);
        modelButton.visible = true;
        modelButton.active = true;
        modelButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0.0f);

        // 下拉选项（原版 ButtonWidget 渲染）
        if (modelDropdownOpen) {
            int optY = y + BUTTON_HEIGHT;
            for (ButtonWidget optBtn : modelOptionButtons) {
                optBtn.setPosition(x, optY);
                optBtn.setWidth(w);
                optBtn.visible = true;
                optBtn.active = true;
                optBtn.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0.0f);
                optY += BUTTON_HEIGHT;
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
        ctx.drawTextWithShadow(client.textRenderer, 
                Text.translatable("formacraft.settings.temperature", cachedTemperatureText),
                x, y, COLOR_GRAY);
        y += LABEL_OFFSET;
        ensureWidgets();
        temperatureSlider.setPosition(x, y);
        temperatureSlider.setWidth(w);
        temperatureSlider.visible = true;
        temperatureSlider.active = true;
        temperatureSlider.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0.0f);
    }

    // =======================
    //   字体大小
    // =======================
    private void drawFontSizeSlider(DrawContext ctx, int x, int y, int w) {
        ctx.drawTextWithShadow(client.textRenderer,
                Text.translatable("formacraft.settings.font_size", draftFontSize),
                x, y, COLOR_GRAY);
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
        int btnW = (w - BUTTON_GAP * 2) / 3;
        int cancelX = x + btnW + BUTTON_GAP;
        int resetX = x + (btnW + BUTTON_GAP) * 2;

        saveButton.setPosition(x, y);
        saveButton.setWidth(btnW);
        saveButton.visible = true;
        saveButton.active = true;
        saveButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0.0f);

        cancelButton.setPosition(cancelX, y);
        cancelButton.setWidth(btnW);
        cancelButton.visible = true;
        cancelButton.active = true;
        cancelButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0.0f);

        resetButton.setPosition(resetX, y);
        resetButton.setWidth(btnW);
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

    // =======================
    //   鼠标交互
    // =======================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true; // 处理顶部 Tab 切换

        if (button != 0) return false;

        ensureWidgets();
        int x = cachedContentX >= 0 ? cachedContentX : (panelX + CONTENT_PADDING);
        int y = getContentY() + CONTENT_PADDING;
        int w = panelWidth - CONTENT_PADDING * 2;

        // 检查是否点击在面板外部（关闭下拉菜单）
        boolean clickedOutside = !isMouseOver(mouseX, mouseY);
        if (clickedOutside && modelDropdownOpen) {
            modelDropdownOpen = false;
            return false; // 不阻止游戏处理，因为点击在面板外
        }

        // drawContents 里先画标题，再 y += TITLE_HEIGHT
        y += TITLE_HEIGHT;

        // =========== Orchestrator 区域 ============
        int orchLabelY = y;
        int orchY = orchLabelY + LABEL_OFFSET;
        if (orchestratorInput.mouseClicked(mouseX, mouseY, x, orchY, w, INPUT_HEIGHT)) {
            apiKeyInput.setFocused(false);
            modelDropdownOpen = false;
            return true;
        }

        // =========== API Key 区域 ============
        y += FIELD_SPACING;
        int apiLabelY = y;
        int apiY = apiLabelY + LABEL_OFFSET;
        int inputW = Math.max(0, w - SHOW_HIDE_BUTTON_WIDTH - PASTE_BUTTON_WIDTH - BUTTON_GAP_SMALL * 2);
        int hideBtnX = x + inputW + BUTTON_GAP_SMALL;
        int pasteX = hideBtnX + SHOW_HIDE_BUTTON_WIDTH + BUTTON_GAP_SMALL;

        Click click = new Click(mouseX, mouseY, new MouseInput(button, 0));

        // Show/Hide（原版按钮）
        showHideButton.setPosition(hideBtnX, apiY);
        showHideButton.setWidth(SHOW_HIDE_BUTTON_WIDTH);
        if (showHideButton.mouseClicked(click, false)) {
            modelDropdownOpen = false;
            return true;
        }

        // Paste（原版按钮）
        pasteButton.setPosition(pasteX, apiY);
        pasteButton.setWidth(PASTE_BUTTON_WIDTH);
        if (pasteButton.mouseClicked(click, false)) {
            modelDropdownOpen = false;
            return true;
        }

        // 点击 API key 输入框
        if (apiKeyInput.mouseClicked(mouseX, mouseY, x, apiY, inputW, INPUT_HEIGHT)) {
            orchestratorInput.setFocused(false);
            modelDropdownOpen = false;
            return true;
        }

        // =========== 模型选择器 ============
        y += FIELD_SPACING;
        int modelLabelY = y;
        int modelY = modelLabelY + LABEL_OFFSET;

        modelButton.setPosition(x, modelY);
        modelButton.setWidth(w);
        if (modelButton.mouseClicked(click, false)) {
            orchestratorInput.setFocused(false);
            apiKeyInput.setFocused(false);
            return true;
        }

        if (modelDropdownOpen) {
            int optY = modelY + BUTTON_HEIGHT;
            for (ButtonWidget optBtn : modelOptionButtons) {
                optBtn.setPosition(x, optY);
                optBtn.setWidth(w);
                if (optBtn.mouseClicked(click, false)) {
                    return true;
                }
                optY += BUTTON_HEIGHT;
            }
        }

        // =========== 滑动条交互 ============
        // 注意：这里必须与 drawContents 的布局一致（下拉选项使用 BUTTON_HEIGHT，而不是 14px）
        y += FIELD_SPACING + (modelDropdownOpen ? (modelOptions.size() * BUTTON_HEIGHT) : 0);
        int tempSliderY = y + LABEL_OFFSET;
        temperatureSlider.setPosition(x, tempSliderY);
        temperatureSlider.setWidth(w);
        if (temperatureSlider.mouseClicked(click, false)) {
            orchestratorInput.setFocused(false);
            apiKeyInput.setFocused(false);
            modelDropdownOpen = false;
            activeSlider = temperatureSlider;
            return true;
        }

        y += (LABEL_OFFSET + INPUT_HEIGHT);
        int fontSliderY = y + LABEL_OFFSET;
        fontSizeSlider.setPosition(x, fontSliderY);
        fontSizeSlider.setWidth(w);
        if (fontSizeSlider.mouseClicked(click, false)) {
            orchestratorInput.setFocused(false);
            apiKeyInput.setFocused(false);
            modelDropdownOpen = false;
            activeSlider = fontSizeSlider;
            return true;
        }

        // =========== 按钮行（Save/Cancel/Reset） ============
        y += (LABEL_OFFSET + INPUT_HEIGHT);
        int btnY = y;
        int btnW = (w - BUTTON_GAP * 2) / 3;
        int cancelX = x + btnW + BUTTON_GAP;
        int resetX = x + (btnW + BUTTON_GAP) * 2;

        saveButton.setPosition(x, btnY);
        saveButton.setWidth(btnW);
        if (saveButton.mouseClicked(click, false)) return true;

        cancelButton.setPosition(cancelX, btnY);
        cancelButton.setWidth(btnW);
        if (cancelButton.mouseClicked(click, false)) return true;

        resetButton.setPosition(resetX, btnY);
        resetButton.setWidth(btnW);
        if (resetButton.mouseClicked(click, false)) return true;

        // 点击空白区域：取消焦点/收起下拉
        if (!clickedOutside) {
            // 在面板内但不在任何元素上，关闭下拉菜单
            if (modelDropdownOpen) {
                modelDropdownOpen = false;
                rebuildModelOptionButtons();
                return true;
            }
        } else {
            // 点击在面板外，关闭所有交互状态
            orchestratorInput.setFocused(false);
            apiKeyInput.setFocused(false);
            modelDropdownOpen = false;
            rebuildModelOptionButtons();
        }

        return false;
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
        }
        return handled;
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
        if (modelButton != null && modelButton.isMouseOver(mouseX, mouseY)) {
            drawTooltipCompat(ctx, java.util.Collections.singletonList(Text.translatable("formacraft.settings.tooltip.model")), (int) mouseX, (int) mouseY);
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
                    modelDropdownOpen = false;
                    apiKeyInput.paste();
                })
                .dimensions(0, 0, PASTE_BUTTON_WIDTH, INPUT_HEIGHT)
                .tooltip(Tooltip.of(Text.translatable("formacraft.settings.tooltip.paste")))
                .build();

        // Model dropdown (button + options)
        modelButton = ButtonWidget.builder(getModelButtonText(), b -> {
                    modelDropdownOpen = !modelDropdownOpen;
                    rebuildModelOptionButtons();
                })
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.translatable("formacraft.settings.tooltip.model")))
                .build();
        rebuildModelOptionButtons();

        // Sliders（用原版 SliderWidget 渲染）
        temperatureSlider = new TemperatureSlider(0, 0, 0, INPUT_HEIGHT, Text.empty(), clamp01(draftTemperature));
        fontSizeSlider = new FontSizeSlider(0, 0, 0, INPUT_HEIGHT, Text.empty(), fontSizeToValue(draftFontSize));
        temperatureSlider.setTooltip(Tooltip.of(Text.translatable("formacraft.settings.tooltip.temperature")));
        fontSizeSlider.setTooltip(Tooltip.of(Text.translatable("formacraft.settings.tooltip.font_size")));

        // Buttons row
        saveButton = ButtonWidget.builder(Text.translatable("formacraft.settings.save"), b -> {
                    if (saveSettings()) {
                        orchestratorInput.setFocused(false);
                        apiKeyInput.setFocused(false);
                        modelDropdownOpen = false;
                        rebuildModelOptionButtons();
                    }
                })
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.translatable("formacraft.settings.tooltip.save")))
                .build();
        cancelButton = ButtonWidget.builder(Text.translatable("formacraft.settings.cancel"), b -> {
                    loadFromConfig();
                    orchestratorInput.setFocused(false);
                    apiKeyInput.setFocused(false);
                    modelDropdownOpen = false;
                    rebuildModelOptionButtons();
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
                    modelDropdownOpen = false;
                    rebuildModelOptionButtons();
                    showToast(Text.translatable("formacraft.settings.reset_success").getString());
                })
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.translatable("formacraft.settings.tooltip.reset")))
                .build();

        syncWidgetStateFromDraft();
    }

    private Text getModelButtonText() {
        // 用一个简单的下拉符号提示（纯文本，不依赖自绘）
        return Text.literal(draftModel + " ▾");
    }

    private void rebuildModelOptionButtons() {
        modelOptionButtons.clear();
        if (!modelDropdownOpen) return;
        for (String opt : modelOptions) {
            ButtonWidget btn = ButtonWidget.builder(Text.literal(opt), b -> {
                        draftModel = opt;
                        modelDropdownOpen = false;
                        rebuildModelOptionButtons();
                    })
                    .dimensions(0, 0, 0, BUTTON_HEIGHT)
                    .build();
            modelOptionButtons.add(btn);
        }
        if (modelButton != null) {
            modelButton.setMessage(getModelButtonText());
        }
    }

    private void toggleHideKey() {
        hideKey = !hideKey;
        apiKeyInput.setPasswordMode(hideKey);
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
        if (modelButton != null) {
            modelButton.setMessage(getModelButtonText());
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

        // 验证API Key
        if (!isValidApiKey(apiKey)) {
            showToast(Text.translatable("formacraft.settings.error.api_key_empty").getString(), true);
            return false;
        }

        try {
            SettingsConfig.INSTANCE.apiKey = apiKey;
            SettingsConfig.INSTANCE.orchestratorEndpoint = endpoint;
            SettingsConfig.INSTANCE.model = Objects.requireNonNullElse(draftModel, "gpt-4o");
            SettingsConfig.INSTANCE.temperature = clamp01(draftTemperature);
            SettingsConfig.INSTANCE.fontSize = clampInt(draftFontSize);
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

        // Tab: 切换焦点
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
            if (shift) {
                currentFocusIndex = (currentFocusIndex == FOCUS_ORCHESTRATOR) ? FOCUS_API_KEY : FOCUS_ORCHESTRATOR;
            } else {
                currentFocusIndex = (currentFocusIndex == FOCUS_API_KEY) ? FOCUS_ORCHESTRATOR : FOCUS_API_KEY;
            }
            orchestratorInput.setFocused(currentFocusIndex == FOCUS_ORCHESTRATOR);
            apiKeyInput.setFocused(currentFocusIndex == FOCUS_API_KEY);
            return;
        }

        // Enter: 快速保存（可选，但很顺手）
        if (keyCode == GLFW.GLFW_KEY_ENTER) {
            saveSettings();
        }
    }

    @Override
    public boolean wantsKeyboardInput() {
        return apiKeyInput.isFocused() || orchestratorInput.isFocused();
    }
}
