package com.formacraft.client.ui.panel;

import com.formacraft.config.SettingsConfig;
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

    // 输入状态
    private String apiKeyInput = "";
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


    // 输入焦点
    private enum FocusField {
        API_KEY,
        NONE
    }

    private FocusField focused = FocusField.NONE;

    public SettingsPanel() {
        loadFromConfig();
    }

    private void loadFromConfig() {
        SettingsConfig.load();
        SettingsConfig cfg = SettingsConfig.INSTANCE;
        this.apiKeyInput = cfg.apiKey != null ? cfg.apiKey : "";
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

        drawApiKeyField(ctx, x, y, w);
        y += 32;

        drawModelSelector(ctx, x, y, w);
        y += modelDropdownOpen ? (modelOptions.size() * 14 + 24) : 32;

        drawTemperatureSlider(ctx, x, y, w);
        y += 28;

        drawFontSizeSlider(ctx, x, y, w);
        y += 28;

        drawSaveButton(ctx, x, y, w);
    }

    // =======================
    //   API KEY 输入框
    // =======================
    private void drawApiKeyField(DrawContext ctx, int x, int y, int w) {
        ctx.drawText(client.textRenderer, Text.literal("API Key:"), x, y, 0xAAAAAA, false);
        y += 12;

        int boxHeight = 16;
        int boxColor = (focused == FocusField.API_KEY) ? 0xFF333333 : 0xFF222222;
        ctx.fill(x, y, x + w, y + boxHeight, boxColor);

        String shown;
        if (hideKey) {
            // 生成星号字符串
            int len = Math.max(0, apiKeyInput.length());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) {
                sb.append("*");
            }
            shown = sb.toString();
        } else {
            shown = apiKeyInput;
        }
        if (shown.isEmpty()) {
            shown = "Enter API Key...";
            ctx.drawText(client.textRenderer, shown, x + 4, y + 4, 0x888888, false);
        } else {
            ctx.drawText(client.textRenderer, shown, x + 4, y + 4, 0xFFFFFF, false);
        }

        // 显示/隐藏按钮
        int btnW = 40;
        int btnX = x + w - btnW;
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
                SettingsConfig.INSTANCE.model, x + 4, y + 4, 0xFFFFFF, false);

        // 下拉箭头
        ctx.drawText(client.textRenderer, Text.literal("▼"), x + w - 12, y + 4, 0xCCCCCC, false);

        if (modelDropdownOpen) {
            int optY = y + 16;
            for (String opt : modelOptions) {
                int optColor = opt.equals(SettingsConfig.INSTANCE.model) ? 0xFF555555 : 0xFF333333;
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
                Text.literal("Temperature: " + String.format("%.2f", SettingsConfig.INSTANCE.temperature)),
                x, y, 0xAAAAAA, false);
        y += 12;

        float temp = SettingsConfig.INSTANCE.temperature;
        int sliderW = w;
        int barY = y + 7;

        // 背景条
        ctx.fill(x, barY, x + sliderW, barY + 4, 0x55222222);

        // 指示器（0.0 → 1.0）
        int knobX = x + (int) (temp * sliderW);
        knobX = Math.max(x + 2, Math.min(x + sliderW - 2, knobX));
        ctx.fill(knobX - 2, barY - 3, knobX + 2, barY + 7, 0xFFAAAAAA);
    }

    // =======================
    //   字体大小
    // =======================
    private void drawFontSizeSlider(DrawContext ctx, int x, int y, int w) {
        ctx.drawText(client.textRenderer,
                Text.literal("Font Size: " + SettingsConfig.INSTANCE.fontSize),
                x, y, 0xAAAAAA, false);
        y += 12;

        int sliderW = w;
        int barY = y + 7;

        ctx.fill(x, barY, x + sliderW, barY + 4, 0x55222222);

        float t = (SettingsConfig.INSTANCE.fontSize - 8) / 18.0f; // 字号 8~26
        t = Math.max(0.0f, Math.min(1.0f, t));
        int knobX = x + (int) (t * sliderW);
        knobX = Math.max(x + 2, Math.min(x + sliderW - 2, knobX));

        ctx.fill(knobX - 2, barY - 3, knobX + 2, barY + 7, 0xFFAAAAAA);
    }

    // =======================
    //   保存按钮
    // =======================
    private void drawSaveButton(DrawContext ctx, int x, int y, int w) {
        int h = 20;
        ctx.fill(x, y, x + w, y + h, 0xFF338833);
        ctx.drawCenteredTextWithShadow(client.textRenderer,
                Text.literal("Save Settings"),
                x + w / 2, y + 6, 0xFFFFFFFF);
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

        // =========== API Key 区域 ============
        int apiY = y + 12;
        int apiBoxH = 16;
        int hideBtnW = 40;
        int hideBtnX = x + w - hideBtnW;

        if (mouseX >= hideBtnX && mouseX <= hideBtnX + hideBtnW &&
                mouseY >= apiY && mouseY <= apiY + apiBoxH) {
            hideKey = !hideKey;
            return true;
        }

        // 点击 API key 输入框本体
        if (mouseX >= x && mouseX <= x + w &&
                mouseY >= apiY && mouseY <= apiY + apiBoxH) {
            // 点击输入框，设置输入焦点
            focused = FocusField.API_KEY;
            return true;
        }

        // 点击其他地方，取消焦点
        if (mouseX < panelX || mouseX > panelX + panelWidth ||
            mouseY < getContentY() || mouseY > panelY + panelHeight) {
            focused = FocusField.NONE;
        }

        // =========== 模型选择器 ============
        int modelY = apiY + 32;
        if (mouseX >= x && mouseX <= x + w &&
            mouseY >= modelY && mouseY <= modelY + 16) {
            modelDropdownOpen = !modelDropdownOpen;
            return true;
        }

        if (modelDropdownOpen) {
            int optY = modelY + 16;
            for (String opt : modelOptions) {
                if (mouseX >= x && mouseX <= x + w &&
                    mouseY >= optY && mouseY <= optY + 14) {
                    SettingsConfig.INSTANCE.model = opt;
                    modelDropdownOpen = false;
                    return true;
                }
                optY += 14;
            }
        }

        // =========== 滑动条交互 ============
        int tempY = modelY + (modelDropdownOpen ? modelOptions.size() * 14 + 24 : 32);
        if (mouseX >= x && mouseX <= x + w &&
            mouseY >= tempY + 12 && mouseY <= tempY + 22) {
            float t = (float) ((mouseX - x) / (double) w);
            SettingsConfig.INSTANCE.temperature = Math.max(0.0f, Math.min(1.0f, t));
            return true;
        }

        int fontY = tempY + 28;
        if (mouseX >= x && mouseX <= x + w &&
            mouseY >= fontY + 12 && mouseY <= fontY + 22) {
            float t = (float) ((mouseX - x) / (double) w);
            SettingsConfig.INSTANCE.fontSize = 8 + (int) (t * 18);
            SettingsConfig.INSTANCE.fontSize = Math.max(8, Math.min(26, SettingsConfig.INSTANCE.fontSize));
            return true;
        }

        // =========== 保存按钮 ============
        int saveY = fontY + 28;
        if (mouseX >= x && mouseX <= x + w &&
            mouseY >= saveY && mouseY <= saveY + 20) {
            SettingsConfig.INSTANCE.apiKey = apiKeyInput;
            SettingsConfig.save();
            return true;
        }

        return false;
    }

    // =======================
    //   键盘输入
    // =======================

    @Override
    public void charTyped(char chr) {
        if (focused == FocusField.API_KEY) {
            if (chr >= 32 && chr <= 126) {
                apiKeyInput = apiKeyInput + chr;
                SettingsConfig.INSTANCE.apiKey = apiKeyInput;
            }
        }
    }

    @Override
    public void keyPressed(int keyCode) {
        if (focused == FocusField.API_KEY) {
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE || keyCode == 259) {
                if (apiKeyInput.length() > 0) {
                    apiKeyInput = apiKeyInput.substring(0, apiKeyInput.length() - 1);
                    SettingsConfig.INSTANCE.apiKey = apiKeyInput;
                }
            }
            // ESC 取消焦点
            if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == 256) {
                focused = FocusField.NONE;
            }
        }
    }
}
