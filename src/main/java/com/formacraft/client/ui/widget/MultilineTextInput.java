package com.formacraft.client.ui.widget;

import com.formacraft.config.SettingsConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 高级多行输入框：
 *
 * - 支持光标位置、闪烁
 * - 支持选区
 * - 支持多行编辑
 * - 支持复制粘贴（使用 Minecraft 剪贴板）
 * - 支持方向键导航
 * - 支持动态字体大小
 *
 * 可在 HUD Overlay 使用，不依赖 Screen。
 */
public class MultilineTextInput {

    private final MinecraftClient client = MinecraftClient.getInstance();

    // 输入框位置大小
    private int x, y, width, height;

    // 文本行
    private final List<String> lines = new ArrayList<>();

    // 光标位置 (行号 + 列号)
    private int cursorLine = 0;
    private int cursorColumn = 0;

    // 选区（如果相同则无选区）
    private int selLineStart = 0;
    private int selColStart = 0;
    private int selLineEnd = 0;
    private int selColEnd = 0;

    // 光标闪烁计时
    private int blinkTicks = 0;
    private boolean cursorVisible = true;

    // 是否拥有输入焦点
    private boolean focused = true;

    // 限制（聊天输入用，避免粘贴超大文本卡 UI）
    private int maxChars = 2048; // 总字符数（含换行）
    private int maxLines = 12;   // 最大行数（手动换行/粘贴可能增加行数）

    // 内部滚动：当前可视区起始行（保证光标可见）
    private int viewLineStart = 0;

    public MultilineTextInput(int x, int y, int w, int h) {
        setBounds(x, y, w, h);
        clear();
    }

    public int getHeight() {
        float fontScale = SettingsConfig.INSTANCE.fontSize / 10.0f * 0.85f;  // 减小到85%
        int baseFontHeight = client.textRenderer.fontHeight;
        int lineHeight = (int)(baseFontHeight * fontScale + 1);  // 更紧凑的行距
        int maxLinesVisible = Math.max(1, (height - 8) / lineHeight);
        return Math.max(height, maxLinesVisible * lineHeight + 8);
    }

    public void setBounds(int nx, int ny, int w, int h) {
        this.x = nx;
        this.y = ny;
        this.width = w;
        this.height = h;
    }

    public void setFocused(boolean focused) {
        this.focused = focused;
        if (focused) {
            cursorVisible = true;
            blinkTicks = 0;
        }
    }

    public void setMaxChars(int maxChars) {
        this.maxChars = Math.max(0, maxChars);
    }

    public void setMaxLines(int maxLines) {
        this.maxLines = Math.max(1, maxLines);
    }

    public int getLineCount() {
        return lines.size();
    }

    public boolean isCursorAtFirstLine() {
        return cursorLine <= 0;
    }

    public boolean isCursorAtLastLine() {
        return cursorLine >= lines.size() - 1;
    }

    public void setText(String value) {
        lines.clear();
        if (value == null) value = "";

        // 先按行切分，再应用 maxLines / maxChars 限制
        String[] parts = value.split("\n", -1);
        int remainingLines = maxLines > 0 ? maxLines : Integer.MAX_VALUE;
        int remainingChars = maxChars > 0 ? maxChars : Integer.MAX_VALUE;

        for (String p : parts) {
            if (remainingLines <= 0) break;
            if (p == null) p = "";

            // 每行尽量塞满，但要考虑总字符数限制
            int allow = Math.min(p.length(), remainingChars);
            lines.add(p.substring(0, allow));
            remainingChars -= allow;
            remainingLines--;

            // 行与行之间的 '\n'
            if (remainingLines > 0 && remainingChars > 0) {
                remainingChars -= 1;
            }
            if (remainingChars <= 0) break;
        }
        if (lines.isEmpty()) lines.add("");

        cursorLine = lines.size() - 1;
        cursorColumn = lines.get(cursorLine).length();
        clearSelection();
        viewLineStart = 0;
    }

    public boolean isFocused() {
        return focused;
    }

    // =========================================================
    // 文本内容
    // =========================================================

    public String getText() {
        return String.join("\n", lines);
    }

    public void clear() {
        lines.clear();
        lines.add("");
        cursorLine = 0;
        cursorColumn = 0;
        clearSelection();
        viewLineStart = 0;
    }

    private int totalChars() {
        int sum = 0;
        for (String line : lines) sum += line.length();
        if (!lines.isEmpty()) sum += (lines.size() - 1); // '\n'
        return sum;
    }

    // =========================================================
    // 光标 & 选区逻辑
    // =========================================================

    private void clearSelection() {
        selLineStart = selLineEnd = cursorLine;
        selColStart = selColEnd = cursorColumn;
    }

    private boolean hasSelection() {
        return !(selLineStart == selLineEnd && selColStart == selColEnd);
    }

    private void deleteSelection() {
        if (!hasSelection()) return;

        int sl = selLineStart, sc = selColStart;
        int el = selLineEnd, ec = selColEnd;

        // 确保 (start <= end)
        if (sl > el || (sl == el && sc > ec)) {
            int t1 = sl, t2 = sc;
            sl = el; sc = ec;
            el = t1; ec = t2;
        }

        if (sl == el) {
            // 同一行删除
            String line = lines.get(sl);
            lines.set(sl, line.substring(0, sc) + line.substring(ec));
        } else {
            // 跨行删除
            String first = lines.get(sl).substring(0, sc);
            String last = lines.get(el).substring(ec);
            lines.set(sl, first + last);

            // 移除中间行
            for (int i = el; i > sl; i--) {
                lines.remove(i);
            }
        }

        cursorLine = sl;
        cursorColumn = sc;

        clearSelection();
    }

    // =========================================================
    // 输入字符
    // =========================================================

    public void charTyped(char chr) {
        if (!focused) return;

        if (chr == '\r' || chr == '\n') return; // 不自动换行（手动换行用 Shift+Enter）
        if (maxChars > 0 && totalChars() >= maxChars) return;

        if (hasSelection()) deleteSelection();

        String cur = lines.get(cursorLine);
        String before = cur.substring(0, cursorColumn);
        String after = cur.substring(cursorColumn);
        String newText = before + chr + after;

        // 检查是否需要自动换行（考虑字体缩放）
        float fontScale = SettingsConfig.INSTANCE.fontSize / 10.0f * 0.85f;
        int availableWidth = width - 8; // 减去左右边距（每边4像素）
        int textWidth = (int)(client.textRenderer.getWidth(newText) * fontScale);
        
        if (textWidth > availableWidth && cursorColumn > 0) {
            // 行数限制：无法再增长时直接拒绝插入，避免无限扩展
            if (maxLines > 0 && lines.size() >= maxLines) {
                return;
            }
            // 需要自动换行：找到合适的换行位置（从光标位置往前找空格或标点）
            int wrapPos = findWrapPosition(before + chr, availableWidth, fontScale);
            if (wrapPos > 0) {
                // 在 wrapPos 位置换行
                String line1 = (before + chr).substring(0, wrapPos);
                String line2 = (before + chr).substring(wrapPos) + after;
                
                lines.set(cursorLine, line1);
                lines.add(cursorLine + 1, line2);
                cursorLine++;
                cursorColumn = line2.length() - after.length();
            } else {
                // 找不到合适的换行位置，在当前光标位置换行
                lines.set(cursorLine, before);
                lines.add(cursorLine + 1, chr + after);
                cursorLine++;
                cursorColumn = 1;
            }
        } else {
            // 不需要换行，正常插入字符
            lines.set(cursorLine, newText);
            cursorColumn++;
        }

        clearSelection();
    }
    
    /**
     * 找到合适的换行位置（优先在空格或标点处换行，考虑字体缩放）
     */
    private int findWrapPosition(String text, int maxWidth, float fontScale) {
        int textWidth = (int)(client.textRenderer.getWidth(text) * fontScale);
        if (textWidth <= maxWidth) {
            return text.length(); // 不需要换行
        }
        
        // 从后往前找空格或标点（从光标位置往前找，最多往前找20个字符）
        int searchStart = Math.max(0, text.length() - 20);
        for (int i = text.length() - 1; i >= searchStart; i--) {
            char c = text.charAt(i);
            if (c == ' ' || c == '，' || c == '。' || c == '、' || c == '；' || 
                c == ',' || c == '.' || c == ';' || c == ':' || c == '!' || c == '?') {
                // 检查这个位置之前的文本是否在宽度内（考虑字体缩放）
                String before = text.substring(0, i + 1);
                int beforeWidth = (int)(client.textRenderer.getWidth(before) * fontScale);
                if (beforeWidth <= maxWidth) {
                    return i + 1; // 在这个字符之后换行
                }
            }
        }
        
        // 如果找不到合适的换行位置，返回0（强制在当前光标位置换行）
        return 0;
    }

    public void insertNewLine() {
        if (!focused) return;

        if (hasSelection()) deleteSelection();
        if (maxChars > 0 && totalChars() + 1 > maxChars) return;
        if (maxLines > 0 && lines.size() >= maxLines) return;

        String cur = lines.get(cursorLine);
        String before = cur.substring(0, cursorColumn);
        String after = cur.substring(cursorColumn);

        lines.set(cursorLine, before);
        lines.add(cursorLine + 1, after);

        cursorLine++;
        cursorColumn = 0;

        clearSelection();
    }

    public void backspace() {
        if (!focused) return;

        if (hasSelection()) {
            deleteSelection();
            return;
        }

        if (cursorColumn > 0) {
            String cur = lines.get(cursorLine);
            String before = cur.substring(0, cursorColumn - 1);
            String after = cur.substring(cursorColumn);
            lines.set(cursorLine, before + after);

            cursorColumn--;
        } else if (cursorLine > 0) {
            // 合并上一行
            int prevLen = lines.get(cursorLine - 1).length();
            lines.set(cursorLine - 1, lines.get(cursorLine - 1) + lines.get(cursorLine));
            lines.remove(cursorLine);

            cursorLine--;
            cursorColumn = prevLen;
        }

        clearSelection();
    }

    // =========================================================
    // Ctrl+C / Ctrl+V / Ctrl+X
    // =========================================================

    private String getSelectedText() {
        int sl = selLineStart, sc = selColStart;
        int el = selLineEnd, ec = selColEnd;

        if (!hasSelection()) return "";

        if (sl > el || (sl == el && sc > ec)) {
            int t1 = sl, t2 = sc;
            sl = el; sc = ec;
            el = t1; ec = t2;
        }

        if (sl == el) {
            return lines.get(sl).substring(sc, ec);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(lines.get(sl).substring(sc)).append("\n");

        for (int i = sl + 1; i < el; i++) {
            sb.append(lines.get(i)).append("\n");
        }

        sb.append(lines.get(el).substring(0, ec));
        return sb.toString();
    }

    private void setClipboard(String s) {
        if (client != null && client.keyboard != null) {
            client.keyboard.setClipboard(s);
        }
    }

    private String getClipboard() {
        if (client != null && client.keyboard != null) {
            return client.keyboard.getClipboard();
        }
        return "";
    }

    public void keyPressed(int keyCode, int scancode, int modifiers) {
        if (!focused) return;

        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;

        // Copy
        if (ctrl && keyCode == GLFW.GLFW_KEY_C) {
            if (hasSelection()) {
                setClipboard(getSelectedText());
            }
            return;
        }

        // Cut
        if (ctrl && keyCode == GLFW.GLFW_KEY_X) {
            if (hasSelection()) {
                setClipboard(getSelectedText());
                deleteSelection();
            }
            return;
        }

        // Paste
        if (ctrl && keyCode == GLFW.GLFW_KEY_V) {
            if (hasSelection()) deleteSelection();
            pasteText(getClipboard());
            return;
        }

        // 方向键
        handleCursorMovement(keyCode, modifiers);

        // Delete
        if (keyCode == GLFW.GLFW_KEY_DELETE) {
            handleDelete();
        }
    }

    private void pasteText(String s) {
        if (s == null || s.isEmpty()) return;

        for (char c : s.toCharArray()) {
            if (maxChars > 0 && totalChars() >= maxChars) return;
            if (c == '\n' || c == '\r') {
                if (c == '\r') continue; // 跳过 \r，只处理 \n
                insertNewLine();
            } else {
                charTyped(c);
            }
        }
    }

    // =========================================================
    // 光标移动控制
    // =========================================================

    private void handleCursorMovement(int key, int modifiers) {
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;

        // 记录旧位置（用于选区）
        int oldLine = cursorLine;
        int oldCol = cursorColumn;

        switch (key) {
            case GLFW.GLFW_KEY_LEFT -> {
                if (cursorColumn > 0) {
                    cursorColumn--;
                } else if (cursorLine > 0) {
                    cursorLine--;
                    cursorColumn = lines.get(cursorLine).length();
                }
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                int len = lines.get(cursorLine).length();
                if (cursorColumn < len) {
                    cursorColumn++;
                } else if (cursorLine < lines.size() - 1) {
                    cursorLine++;
                    cursorColumn = 0;
                }
            }
            case GLFW.GLFW_KEY_UP -> {
                if (cursorLine > 0) {
                    cursorLine--;
                    cursorColumn = Math.min(cursorColumn, lines.get(cursorLine).length());
                }
            }
            case GLFW.GLFW_KEY_DOWN -> {
                if (cursorLine < lines.size() - 1) {
                    cursorLine++;
                    cursorColumn = Math.min(cursorColumn, lines.get(cursorLine).length());
                }
            }
            case GLFW.GLFW_KEY_HOME -> cursorColumn = 0;
            case GLFW.GLFW_KEY_END -> cursorColumn = lines.get(cursorLine).length();
        }

        // 更新选区
        if (!shift) {
            clearSelection();
        } else {
            // 如果之前没有选区，从旧位置开始
            if (!hasSelection()) {
                selLineStart = oldLine;
                selColStart = oldCol;
            }
            selLineEnd = cursorLine;
            selColEnd = cursorColumn;
        }
    }

    private void handleDelete() {
        if (hasSelection()) {
            deleteSelection();
            return;
        }

        String cur = lines.get(cursorLine);
        if (cursorColumn < cur.length()) {
            lines.set(cursorLine,
                    cur.substring(0, cursorColumn) + cur.substring(cursorColumn + 1));
        } else if (cursorLine < lines.size() - 1) {
            lines.set(cursorLine, cur + lines.get(cursorLine + 1));
            lines.remove(cursorLine + 1);
        }
    }

    // =========================================================
    // 渲染
    // =========================================================

    public void render(DrawContext ctx) {
        // 光标闪烁
        blinkTicks++;
        if (blinkTicks % 20 == 0) {
            cursorVisible = !cursorVisible;
        }

        // 背景（半透明，和对话展示区一致的风格）
        ctx.fill(x, y, x + width, y + height, 0x88111111);

        // 根据字体大小计算行高（减小字体大小）
        float fontScale = SettingsConfig.INSTANCE.fontSize / 10.0f * 0.85f;  // 减小到85%
        int baseFontHeight = client.textRenderer.fontHeight;
        int lineHeight = (int)(baseFontHeight * fontScale + 1);  // 更紧凑的行距

        int textY = y + 4;
        int maxLinesVisible = (height - 8) / lineHeight;
        maxLinesVisible = Math.max(1, maxLinesVisible);

        // 让光标始终可见：根据 cursorLine 调整 viewLineStart
        int maxStart = Math.max(0, lines.size() - maxLinesVisible);
        if (cursorLine < viewLineStart) {
            viewLineStart = cursorLine;
        } else if (cursorLine >= viewLineStart + maxLinesVisible) {
            viewLineStart = cursorLine - maxLinesVisible + 1;
        }
        if (viewLineStart > maxStart) viewLineStart = maxStart;
        if (viewLineStart < 0) viewLineStart = 0;

        int lineStart = viewLineStart;

        // 绘制行（带选区高亮）
        for (int i = lineStart; i < lines.size(); i++) {
            String line = lines.get(i);
            int drawY = textY + (i - lineStart) * lineHeight;

            // 检查是否需要绘制选区
            if (hasSelection() && i >= Math.min(selLineStart, selLineEnd) && 
                i <= Math.max(selLineStart, selLineEnd)) {
                drawLineWithSelection(ctx, line, i, drawY, lineHeight, fontScale);
            } else {
                // 使用 drawTextWithShadow 让文字更清晰可见，应用字体缩放
                drawScaledText(ctx, line, x + 4, drawY, 0xFFFFFFFF, fontScale);
            }
        }

        // 绘制光标
        if (cursorVisible && focused) {
            if (cursorLine >= lineStart && cursorLine < lines.size()) {
                int drawY = textY + (cursorLine - lineStart) * lineHeight;
                String before = lines.get(cursorLine).substring(0, cursorColumn);
                int cx = x + 4 + (int)(client.textRenderer.getWidth(before) * fontScale);
                ctx.fill(cx, drawY, cx + 1, drawY + lineHeight, 0xFFFFFFFF);
            }
        }
    }

    /**
     * 绘制缩放文字（简化版：直接绘制，不缩放）
     * 注意：Minecraft 的字体大小是固定的，我们通过调整行高来让文字看起来更紧凑
     */
    private void drawScaledText(DrawContext ctx, String text, int x, int y, int color, float scale) {
        // 直接绘制文字，不进行缩放（Minecraft 字体大小固定）
        // 缩放效果通过调整行高来实现
        ctx.drawTextWithShadow(client.textRenderer, text, x, y, color);
    }
    
    /**
     * 绘制带选区高亮的行
     */
    private void drawLineWithSelection(DrawContext ctx, String line, int lineIndex, int drawY, int lineHeight, float fontScale) {
        int sl = selLineStart, sc = selColStart;
        int el = selLineEnd, ec = selColEnd;

        // 确保 start <= end
        if (sl > el || (sl == el && sc > ec)) {
            int t1 = sl, t2 = sc;
            sl = el; sc = ec;
            el = t1; ec = t2;
        }

        int lineLen = line.length();

        // 应用字体缩放
        if (lineIndex == sl && lineIndex == el) {
            // 单行选区
            int startX = x + 4 + (int)(client.textRenderer.getWidth(line.substring(0, sc)) * fontScale);
            int endX = x + 4 + (int)(client.textRenderer.getWidth(line.substring(0, ec)) * fontScale);
            ctx.fill(startX, drawY, endX, drawY + lineHeight, 0x5533AAFF);
            
            // 绘制文本（分三段，应用缩放）
            if (sc > 0) {
                drawScaledText(ctx, line.substring(0, sc), x + 4, drawY, 0xFFFFFFFF, fontScale);
            }
            if (ec > sc) {
                int selStartX = x + 4 + (int)(client.textRenderer.getWidth(line.substring(0, sc)) * fontScale);
                drawScaledText(ctx, line.substring(sc, ec), selStartX, drawY, 0xFFFFFFFF, fontScale);
            }
            if (ec < lineLen) {
                int selEndX = x + 4 + (int)(client.textRenderer.getWidth(line.substring(0, ec)) * fontScale);
                drawScaledText(ctx, line.substring(ec), selEndX, drawY, 0xFFFFFFFF, fontScale);
            }
        } else if (lineIndex == sl) {
            // 选区开始行
            int startX = x + 4 + (int)(client.textRenderer.getWidth(line.substring(0, sc)) * fontScale);
            ctx.fill(startX, drawY, x + width - 4, drawY + lineHeight, 0x5533AAFF);
            
            if (sc > 0) {
                drawScaledText(ctx, line.substring(0, sc), x + 4, drawY, 0xFFFFFFFF, fontScale);
            }
            if (sc < lineLen) {
                int selStartX = x + 4 + (int)(client.textRenderer.getWidth(line.substring(0, sc)) * fontScale);
                drawScaledText(ctx, line.substring(sc), selStartX, drawY, 0xFFFFFFFF, fontScale);
            }
        } else if (lineIndex == el) {
            // 选区结束行
            int endX = x + 4 + (int)(client.textRenderer.getWidth(line.substring(0, ec)) * fontScale);
            ctx.fill(x + 4, drawY, endX, drawY + lineHeight, 0x5533AAFF);
            
            if (ec > 0) {
                drawScaledText(ctx, line.substring(0, ec), x + 4, drawY, 0xFFFFFFFF, fontScale);
            }
            if (ec < lineLen) {
                int selEndX = x + 4 + (int)(client.textRenderer.getWidth(line.substring(0, ec)) * fontScale);
                drawScaledText(ctx, line.substring(ec), selEndX, drawY, 0xFFFFFFFF, fontScale);
            }
        } else if (lineIndex > sl && lineIndex < el) {
            // 选区中间行（整行高亮）
            ctx.fill(x + 4, drawY, x + width - 4, drawY + lineHeight, 0x5533AAFF);
            drawScaledText(ctx, line, x + 4, drawY, 0xFFFFFFFF, fontScale);
        } else {
            // 无选区部分
            drawScaledText(ctx, line, x + 4, drawY, 0xFFFFFFFF, fontScale);
        }
    }
}
