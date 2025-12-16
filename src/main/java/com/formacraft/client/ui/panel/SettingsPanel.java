package com.formacraft.client.ui.panel;

import com.formacraft.config.SettingsConfig;
import com.formacraft.client.ui.widget.HudTextInput;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;
import java.util.List;

/**
 * FormaCraft 设置面板（HUD 左侧栏）
 */
public class SettingsPanel extends BasePanel {

    private final MinecraftClient client = MinecraftClient.getInstance();

    // 输入组件（HUD 模式，不依赖 Screen）
    private final HudTextInput orchestratorInput = new HudTextInput();
    private final HudTextInput apiKeyInput = new HudTextInput();
    private boolean hideKey = true;

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

    public SettingsPanel() {
        loadFromConfig();
    }

    private void loadFromConfig() {
        SettingsConfig.load();
        SettingsConfig cfg = SettingsConfig.INSTANCE;
        this.apiKeyInput.setText(cfg.apiKey != null ? cfg.apiKey : "");
        this.orchestratorInput.setText(cfg.orchestratorEndpoint != null ? cfg.orchestratorEndpoint : "http://localhost:8000");
        this.apiKeyInput.setPasswordMode(hideKey);
        this.apiKeyInput.setMaxLength(256);
        this.orchestratorInput.setMaxLength(256);

        // 同步草稿态
        this.draftModel = (cfg.model != null && !cfg.model.isBlank()) ? cfg.model : "gpt-4o";
        this.draftTemperature = clamp01(cfg.temperature);
        this.draftFontSize = clampInt(cfg.fontSize, 8, 26);
    }

    private static float clamp01(float v) {
        if (v < 0.0f) return 0.0f;
        if (v > 1.0f) return 1.0f;
        return v;
    }

    private static int clampInt(int v, int min, int max) {
        if (v < min) return min;
        return Math.min(v, max);
    }

    @Override
    protected void drawContents(DrawContext ctx) {
        int x = panelX + 10;
        int y = getContentY() + 10;
        int w = panelWidth - 20;

        ctx.drawText(client.textRenderer,
                Text.literal("FormaCraft Settings"),
                x, y, 0xFFFFFF, false);
        y += 20;

        drawOrchestratorField(ctx, x, y, w);
        y += 32;

        drawApiKeyField(ctx, x, y, w);
        y += 32;

        drawModelSelector(ctx, x, y, w);
        y += modelDropdownOpen ? (modelOptions.size() * 14 + 24) : 32;

        drawTemperatureSlider(ctx, x, y, w);
        y += 28;

        drawFontSizeSlider(ctx, x, y, w);
        y += 28;

        drawButtonsRow(ctx, x, y, w);
        y += 24;

        drawToast(ctx, x, y, w);
    }

    private void drawToast(DrawContext ctx, int x, int y, int w) {
        if (toast == null) return;
        if (System.currentTimeMillis() > toastUntilMs) {
            toast = null;
            return;
        }
        ctx.drawText(client.textRenderer, Text.literal(toast), x, y, 0x88FF88, false);
    }

    // =======================
    //   Orchestrator 地址输入框
    // =======================
    private void drawOrchestratorField(DrawContext ctx, int x, int y, int w) {
        ctx.drawText(client.textRenderer, Text.literal("Backend URL:"), x, y, 0xAAAAAA, false);
        y += 12;
        orchestratorInput.render(ctx, x, y, w, 16);
    }

    // =======================
    //   API KEY 输入框
    // =======================
    private void drawApiKeyField(DrawContext ctx, int x, int y, int w) {
        ctx.drawText(client.textRenderer, Text.literal("API Key:"), x, y, 0xAAAAAA, false);
        y += 12;

        int boxHeight = 16;
        int btnW = 40;
        int gap = 4;
        int inputW = Math.max(0, w - btnW - gap);

        // 输入框（右侧留出 Show/Hide 按钮区域）
        apiKeyInput.setPasswordMode(hideKey);
        apiKeyInput.render(ctx, x, y, inputW, boxHeight);

        // Show/Hide 按钮
        int btnX = x + inputW + gap;
        ctx.fill(btnX, y, btnX + btnW, y + boxHeight, 0xFF444444);
        ctx.drawCenteredTextWithShadow(client.textRenderer,
                hideKey ? Text.literal("Show") : Text.literal("Hide"),
                btnX + btnW / 2, y + 4, 0xFFFFFF);
    }

    // =======================
    //   模型选择（下拉菜单）
    // =======================
    private void drawModelSelector(DrawContext ctx, int x, int y, int w) {
        ctx.drawText(client.textRenderer, Text.literal("Model:"), x, y, 0xAAAAAA, false);
        y += 12;

        // 主按钮
        int boxColor = modelDropdownOpen ? 0xFF444444 : 0xFF222222;
        ctx.fill(x, y, x + w, y + 16, boxColor);
        ctx.drawText(client.textRenderer,
                draftModel, x + 4, y + 4, 0xFFFFFF, false);

        // 下拉箭头
        ctx.drawText(client.textRenderer, Text.literal("v"), x + w - 10, y + 4, 0xCCCCCC, false);

        if (modelDropdownOpen) {
            int optY = y + 16;
            for (String opt : modelOptions) {
                int optColor = opt.equals(draftModel) ? 0xFF555555 : 0xFF333333;
                ctx.fill(x, optY, x + w, optY + 14, optColor);
                ctx.drawText(client.textRenderer, opt, x + 4, optY + 3, 0xFFFFFF, false);
                optY += 14;
            }
        }
    }

    // =======================
    //   温度滑动条
    // =======================
    private void drawTemperatureSlider(DrawContext ctx, int x, int y, int w) {
        ctx.drawText(client.textRenderer, 
                Text.literal("Temperature: " + String.format("%.2f", draftTemperature)),
                x, y, 0xAAAAAA, false);
        y += 12;

        float temp = draftTemperature;
        int barY = y + 7;

        // 背景条
        ctx.fill(x, barY, x + w, barY + 4, 0x55222222);

        // 指示器（0.0 → 1.0）
        int knobX = x + (int) (temp * w);
        knobX = Math.max(x + 2, Math.min(x + w - 2, knobX));
        ctx.fill(knobX - 2, barY - 3, knobX + 2, barY + 7, 0xFFAAAAAA);
    }

    // =======================
    //   字体大小
    // =======================
    private void drawFontSizeSlider(DrawContext ctx, int x, int y, int w) {
        ctx.drawText(client.textRenderer,
                Text.literal("Font Size: " + draftFontSize),
                x, y, 0xAAAAAA, false);
        y += 12;

        int barY = y + 7;

        ctx.fill(x, barY, x + w, barY + 4, 0x55222222);

        float t = (draftFontSize - 8) / 18.0f; // 字号 8~26
        t = Math.max(0.0f, Math.min(1.0f, t));
        int knobX = x + (int) (t * w);
        knobX = Math.max(x + 2, Math.min(x + w - 2, knobX));

        ctx.fill(knobX - 2, barY - 3, knobX + 2, barY + 7, 0xFFAAAAAA);
    }

    // =======================
    //   保存按钮
    // =======================
    private void drawButtonsRow(DrawContext ctx, int x, int y, int w) {
        int h = 20;
        int gap = 6;
        int btnW = (w - gap * 2) / 3;

        double mouseX = client.mouse.getX() / client.getWindow().getScaleFactor();
        double mouseY = client.mouse.getY() / client.getWindow().getScaleFactor();

        int cancelX = x + btnW + gap;
        int resetX = x + (btnW + gap) * 2;

        boolean saveHovered = mouseX >= x && mouseX <= x + btnW && mouseY >= y && mouseY <= y + h;
        boolean cancelHovered = mouseX >= cancelX && mouseX <= cancelX + btnW && mouseY >= y && mouseY <= y + h;
        boolean resetHovered = mouseX >= resetX && mouseX <= resetX + btnW && mouseY >= y && mouseY <= y + h;

        drawMinecraftButton(ctx, x, y, btnW, h, Text.literal("Save"), saveHovered);
        drawMinecraftButton(ctx, cancelX, y, btnW, h, Text.literal("Cancel"), cancelHovered);
        drawMinecraftButton(ctx, resetX, y, btnW, h, Text.literal("Reset"), resetHovered);
    }

    // =======================
    //   鼠标交互
    // =======================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true; // 处理顶部 Tab 切换

        if (button != 0) return false;

        int x = panelX + 10;
        int y = getContentY() + 10;
        int w = panelWidth - 20;

        // =========== Orchestrator 区域 ============
        int orchY = y + 12;
        if (orchestratorInput.mouseClicked(mouseX, mouseY, x, orchY, w, 16)) {
            apiKeyInput.setFocused(false);
            modelDropdownOpen = false;
            return true;
        }

        // =========== API Key 区域 ============
        int apiLabelY = y + 32;
        int apiY = apiLabelY + 12;
        int apiBoxH = 16;
        int hideBtnW = 40;
        int apiGap = 4;
        int inputW = Math.max(0, w - hideBtnW - apiGap);
        int hideBtnX = x + inputW + apiGap;

        if (mouseX >= hideBtnX && mouseX <= hideBtnX + hideBtnW &&
                mouseY >= apiY && mouseY <= apiY + apiBoxH) {
            hideKey = !hideKey;
            apiKeyInput.setPasswordMode(hideKey);
            return true;
        }

        // 点击 API key 输入框
        if (apiKeyInput.mouseClicked(mouseX, mouseY, x, apiY, inputW, apiBoxH)) {
            orchestratorInput.setFocused(false);
            modelDropdownOpen = false;
            return true;
        }

        // =========== 模型选择器 ============
        int modelLabelY = apiLabelY + 32;
        int modelY = modelLabelY + 12;
        if (mouseX >= x && mouseX <= x + w &&
            mouseY >= modelY && mouseY <= modelY + 16) {
            modelDropdownOpen = !modelDropdownOpen;
            orchestratorInput.setFocused(false);
            apiKeyInput.setFocused(false);
            return true;
        }

        if (modelDropdownOpen) {
            int optY = modelY + 16;
            for (String opt : modelOptions) {
                if (mouseX >= x && mouseX <= x + w &&
                    mouseY >= optY && mouseY <= optY + 14) {
                    draftModel = opt;
                    modelDropdownOpen = false;
                    return true;
                }
                optY += 14;
            }
        }

        // =========== 滑动条交互 ============
        int tempLabelY = modelLabelY + (modelDropdownOpen ? modelOptions.size() * 14 + 24 : 32);
        if (mouseX >= x && mouseX <= x + w &&
            mouseY >= tempLabelY + 12 && mouseY <= tempLabelY + 22) {
            float t = (float) ((mouseX - x) / (double) w);
            draftTemperature = Math.max(0.0f, Math.min(1.0f, t));
            orchestratorInput.setFocused(false);
            apiKeyInput.setFocused(false);
            return true;
        }

        int fontLabelY = tempLabelY + 28;
        if (mouseX >= x && mouseX <= x + w &&
            mouseY >= fontLabelY + 12 && mouseY <= fontLabelY + 22) {
            float t = (float) ((mouseX - x) / (double) w);
            draftFontSize = 8 + (int) (t * 18);
            draftFontSize = Math.max(8, Math.min(26, draftFontSize));
            orchestratorInput.setFocused(false);
            apiKeyInput.setFocused(false);
            return true;
        }

        // =========== 按钮行（Save/Cancel/Reset） ============
        int btnY = fontLabelY + 28;
        int h = 20;
        int btnGap = 6;
        int btnW = (w - btnGap * 2) / 3;
        int cancelX = x + btnW + btnGap;
        int resetX = x + (btnW + btnGap) * 2;

        // Save
        if (mouseX >= x && mouseX <= x + btnW &&
            mouseY >= btnY && mouseY <= btnY + h) {
            SettingsConfig.INSTANCE.apiKey = apiKeyInput.getText();
            SettingsConfig.INSTANCE.orchestratorEndpoint = sanitizeEndpoint(orchestratorInput.getText());
            SettingsConfig.INSTANCE.model = draftModel;
            SettingsConfig.INSTANCE.temperature = clamp01(draftTemperature);
            SettingsConfig.INSTANCE.fontSize = clampInt(draftFontSize, 8, 26);
            SettingsConfig.save();
            showToast("已保存设置（立即生效）");
            orchestratorInput.setFocused(false);
            apiKeyInput.setFocused(false);
            modelDropdownOpen = false;
            return true;
        }

        // Cancel
        if (mouseX >= cancelX && mouseX <= cancelX + btnW &&
            mouseY >= btnY && mouseY <= btnY + h) {
            loadFromConfig();
            orchestratorInput.setFocused(false);
            apiKeyInput.setFocused(false);
            modelDropdownOpen = false;
            showToast("已取消修改");
            return true;
        }

        // Reset
        if (mouseX >= resetX && mouseX <= resetX + btnW &&
            mouseY >= btnY && mouseY <= btnY + h) {
            // 关键：不要替换 INSTANCE 引用，只重置字段
            SettingsConfig.INSTANCE.resetToDefault();
            SettingsConfig.save();
            loadFromConfig(); // 同步 UI 临时输入状态
            orchestratorInput.setFocused(false);
            apiKeyInput.setFocused(false);
            modelDropdownOpen = false;
            showToast("已恢复默认设置");
            return true;
        }

        // 点击空白区域：取消焦点/收起下拉
        orchestratorInput.setFocused(false);
        apiKeyInput.setFocused(false);
        modelDropdownOpen = false;

        return false;
    }

    private static String sanitizeEndpoint(String endpoint) {
        if (endpoint == null) return "http://localhost:8000";
        String v = endpoint.trim();
        if (v.isEmpty()) return "http://localhost:8000";
        // 简单容错：如果用户只填了 localhost:8000，则补协议
        if (!v.startsWith("http://") && !v.startsWith("https://")) {
            v = "http://" + v;
        }
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    private void showToast(String msg) {
        this.toast = msg;
        this.toastUntilMs = System.currentTimeMillis() + 2500L;
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

        // Enter: 快速保存（可选，但很顺手）
        if (keyCode == GLFW.GLFW_KEY_ENTER) {
            SettingsConfig.INSTANCE.apiKey = apiKeyInput.getText();
            SettingsConfig.INSTANCE.orchestratorEndpoint = sanitizeEndpoint(orchestratorInput.getText());
            SettingsConfig.INSTANCE.model = draftModel;
            SettingsConfig.INSTANCE.temperature = clamp01(draftTemperature);
            SettingsConfig.INSTANCE.fontSize = clampInt(draftFontSize, 8, 26);
            SettingsConfig.save();
            showToast("已保存设置（立即生效）");
        }
    }
}
