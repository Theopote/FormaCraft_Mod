package com.formacraft.client.ui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import org.lwjgl.glfw.GLFW;

/**
 * HUD 可用单行文本输入组件（不依赖 Screen/TextFieldWidget）
 * - 光标闪烁
 * - 光标移动（← → Home End）
 * - 选区（Shift + ← → / Ctrl+A）
 * - Ctrl+V 粘贴
 * - Backspace / Delete
 * - 最大长度限制
 * - 可隐藏文本（密码模式）
 * - 由 InputRouter / BasePanel 驱动：鼠标/键盘事件由面板转发
 */
public class HudTextInput {

    private final MinecraftClient client = MinecraftClient.getInstance();

    // 文本内容
    private final StringBuilder text = new StringBuilder();

    // 光标与选区
    private int cursor = 0;
    private int selectionStart = -1; // -1 表示无选区

    // 状态
    private boolean focused = false;
    private boolean passwordMode = false;

    // 限制
    private int maxLength = 512;

    // 光标闪烁
    private long lastBlinkMs = 0;
    private boolean cursorVisible = true;

    // 横向滚动（像素）
    private int scrollX = 0;

    // -------------------------
    // 基础配置
    // -------------------------
    public void setText(String value) {
        text.setLength(0);
        if (value != null) {
            text.append(value);
        }
        cursor = text.length();
        clearSelection();
        scrollX = 0;
    }

    public String getText() {
        return text.toString();
    }

    public void setFocused(boolean focused) {
        this.focused = focused;
        if (!focused) clearSelection();
    }

    public boolean isFocused() {
        return focused;
    }

    public void setPasswordMode(boolean passwordMode) {
        this.passwordMode = passwordMode;
    }

    public void setMaxLength(int maxLength) {
        this.maxLength = Math.max(0, maxLength);
        if (text.length() > this.maxLength) {
            text.setLength(this.maxLength);
            cursor = Math.min(cursor, text.length());
            clearSelection();
        }
    }

    // -------------------------
    // 渲染
    // -------------------------
    public void render(DrawContext ctx, int x, int y, int w, int h) {
        // 背景
        int bg = focused ? 0xFF333333 : 0xFF222222;
        ctx.fill(x, y, x + w, y + h, bg);

        String display = passwordMode ? "*".repeat(text.length()) : text.toString();

        int textX = x + 4;
        int textY = y + (h - client.textRenderer.fontHeight) / 2;

        // 保证光标可见：根据当前 cursor 计算像素位置并调整 scrollX
        int cursorPx = client.textRenderer.getWidth(display.substring(0, Math.min(cursor, display.length())));
        int innerW = Math.max(0, w - 8);
        if (cursorPx - scrollX > innerW) {
            scrollX = cursorPx - innerW;
        } else if (cursorPx - scrollX < 0) {
            scrollX = cursorPx;
        }
        if (scrollX < 0) scrollX = 0;

        int drawX = textX - scrollX;

        // 选区
        if (hasSelection()) {
            int a = Math.min(cursor, selectionStart);
            int b = Math.max(cursor, selectionStart);
            int selX1 = drawX + client.textRenderer.getWidth(display.substring(0, a));
            int selX2 = drawX + client.textRenderer.getWidth(display.substring(0, b));
            ctx.fill(selX1, y + 2, selX2, y + h - 2, 0xFF5555AA);
        }

        // 文本
        // 使用阴影增强可读性（HUD/世界背景上更稳定）
        ctx.drawText(client.textRenderer, display, drawX, textY, 0xFFFFFF, true);

        // 光标
        if (focused && isCursorVisible()) {
            int cx = drawX + client.textRenderer.getWidth(display.substring(0, cursor));
            ctx.fill(cx, y + 3, cx + 1, y + h - 3, 0xFFFFFFFF);
        }
    }

    private boolean isCursorVisible() {
        long now = System.currentTimeMillis();
        if (now - lastBlinkMs > 500) {
            cursorVisible = !cursorVisible;
            lastBlinkMs = now;
        }
        return cursorVisible;
    }

    // -------------------------
    // 鼠标
    // -------------------------
    public boolean mouseClicked(double mouseX, double mouseY, int x, int y, int w, int h) {
        if (mouseX < x || mouseX > x + w || mouseY < y || mouseY > y + h) {
            setFocused(false);
            return false;
        }

        setFocused(true);
        int clickX = (int) mouseX - (x + 4) + scrollX;

        cursor = calculateCursorFromX(clickX);
        clearSelection();
        return true;
    }

    private int calculateCursorFromX(int px) {
        int cur = 0;
        for (int i = 1; i <= text.length(); i++) {
            int w = client.textRenderer.getWidth(text.substring(0, i));
            if (px < w) break;
            cur = i;
        }
        return cur;
    }

    // -------------------------
    // 键盘
    // -------------------------
    public boolean keyPressed(int keyCode, int modifiers) {
        if (!focused) return false;

        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;

        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT -> moveCursor(-1, shift);
            case GLFW.GLFW_KEY_RIGHT -> moveCursor(1, shift);
            case GLFW.GLFW_KEY_HOME -> setCursor(0, shift);
            case GLFW.GLFW_KEY_END -> setCursor(text.length(), shift);

            case GLFW.GLFW_KEY_BACKSPACE -> deleteBack();
            case GLFW.GLFW_KEY_DELETE -> deleteForward();

            case GLFW.GLFW_KEY_A -> {
                if (ctrl) selectAll();
            }
            case GLFW.GLFW_KEY_V -> {
                if (ctrl) pasteFromClipboard();
            }
            case GLFW.GLFW_KEY_ESCAPE -> setFocused(false);
            default -> {
                // no-op
            }
        }

        return true;
    }

    public boolean charTyped(char chr) {
        if (!focused) return false;
        if (chr < 32 || chr > 126) return false;

        insert(String.valueOf(chr));
        return true;
    }

    // -------------------------
    // 文本操作
    // -------------------------
    private void insert(String s) {
        if (s == null || s.isEmpty()) return;
        if (text.length() + s.length() > maxLength) return;

        deleteSelection();
        text.insert(cursor, s);
        cursor += s.length();
    }

    private void deleteBack() {
        if (deleteSelection()) return;
        if (cursor > 0) {
            text.deleteCharAt(cursor - 1);
            cursor--;
        }
    }

    private void deleteForward() {
        if (deleteSelection()) return;
        if (cursor < text.length()) {
            text.deleteCharAt(cursor);
        }
    }

    private boolean deleteSelection() {
        if (!hasSelection()) return false;
        int a = Math.min(cursor, selectionStart);
        int b = Math.max(cursor, selectionStart);
        text.delete(a, b);
        cursor = a;
        clearSelection();
        return true;
    }

    // -------------------------
    // 光标 & 选区
    // -------------------------
    private void moveCursor(int delta, boolean shift) {
        int next = Math.max(0, Math.min(text.length(), cursor + delta));
        if (shift) {
            if (!hasSelection()) selectionStart = cursor;
        } else {
            clearSelection();
        }
        cursor = next;
    }

    private void setCursor(int pos, boolean shift) {
        int next = Math.max(0, Math.min(text.length(), pos));
        if (shift) {
            if (!hasSelection()) selectionStart = cursor;
        } else {
            clearSelection();
        }
        cursor = next;
    }

    private void selectAll() {
        selectionStart = 0;
        cursor = text.length();
    }

    private boolean hasSelection() {
        return selectionStart != -1 && selectionStart != cursor;
    }

    private void clearSelection() {
        selectionStart = -1;
    }

    // -------------------------
    // 剪贴板（使用 MC 自身剪贴板，避免 AWT 依赖）
    // -------------------------
    private void pasteFromClipboard() {
        try {
            String clip = client != null && client.keyboard != null ? client.keyboard.getClipboard() : null;
            if (clip != null && !clip.isEmpty()) {
                insert(clip.replaceAll("[\\r\\n]", ""));
            }
        } catch (Exception ignored) {
        }
    }

    /** 允许外部（例如 SettingsPanel 的 Paste 按钮）触发一次粘贴 */
    public void paste() {
        if (!focused) setFocused(true);
        pasteFromClipboard();
    }
}


