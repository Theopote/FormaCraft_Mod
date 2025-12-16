package com.formacraft.client.ui.text;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.text.Style;
import net.minecraft.text.CharacterVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD 可用文本选择块（不依赖 Screen）
 * - 使用 TextRenderer.wrapLines 做换行，保证与 MC 字体宽度一致
 * - 鼠标拖拽选择、渲染半透明选区
 * - Ctrl+C 复制（由外部把 getSelectedText() 写入剪贴板）
 *
 * 说明：
 * - fullText 使用“原始文本”（包含 '\n'）
 * - wrap 只决定视觉换行，不会把换行符注入 fullText
 */
public class SelectableTextBlock {

    public record WrappedLine(String text, int startIndex) {}

    private final TextRenderer font;

    private int x, y, width;
    private int lineHeight;
    private int maxWidthPx;

    private String fullText = "";
    private List<WrappedLine> wrapped = new ArrayList<>();

    // 选区（fullText 的字符索引）
    private int selStart = -1;
    private int selEnd = -1;
    private boolean selecting = false;

    public SelectableTextBlock(MinecraftClient client) {
        this.font = client.textRenderer;
        this.lineHeight = font.fontHeight + 2;
        this.maxWidthPx = 200;
    }

    public void setBounds(int x, int y, int maxWidthPx, int lineHeight) {
        this.x = x;
        this.y = y;
        this.width = maxWidthPx;
        this.maxWidthPx = maxWidthPx;
        this.lineHeight = Math.max(1, lineHeight);
        rewrap();
    }

    public void setText(String text) {
        this.fullText = text != null ? text : "";
        // 保持现有选区（但要 clamp）
        int len = this.fullText.length();
        if (selStart > len) selStart = len;
        if (selEnd > len) selEnd = len;
        rewrap();
    }

    public boolean hasSelection() {
        return selStart >= 0 && selEnd >= 0 && selStart != selEnd;
    }

    public void clearSelection() {
        selStart = selEnd = -1;
        selecting = false;
    }

    public String getSelectedText() {
        if (!hasSelection()) return "";
        int a = Math.min(selStart, selEnd);
        int b = Math.max(selStart, selEnd);
        a = Math.max(0, Math.min(a, fullText.length()));
        b = Math.max(0, Math.min(b, fullText.length()));
        if (a >= b) return "";
        return fullText.substring(a, b);
    }

    public void render(DrawContext ctx, int color) {
        if (wrapped.isEmpty()) return;

        int cy = y;
        for (WrappedLine line : wrapped) {
            drawSelectionForLine(ctx, line, cy);
            ctx.drawText(font, Text.literal(line.text()), x, cy, color, false);
            cy += lineHeight;
        }
    }

    private void drawSelectionForLine(DrawContext ctx, WrappedLine line, int cy) {
        if (!hasSelection()) return;

        int lineStart = line.startIndex();
        int lineEnd = lineStart + line.text().length();

        int a = Math.min(selStart, selEnd);
        int b = Math.max(selStart, selEnd);

        if (b <= lineStart || a >= lineEnd) return;

        int from = Math.max(0, a - lineStart);
        int to = Math.min(line.text().length(), b - lineStart);

        int sx = x + font.getWidth(line.text().substring(0, from));
        int ex = x + font.getWidth(line.text().substring(0, to));
        ctx.fill(sx, cy, ex, cy + lineHeight, 0x553388FF);
    }

    // -------------------------
    // 鼠标交互（由外部传入 scaled 坐标）
    // -------------------------
    public boolean mousePressed(double mx, double my) {
        if (!isInside(mx, my)) return false;
        selecting = true;
        selStart = getCharIndex(mx, my);
        selEnd = selStart;
        return true;
    }

    public void mouseDragged(double mx, double my) {
        if (!selecting) return;
        selEnd = getCharIndex(mx, my);
    }

    public void mouseReleased() {
        selecting = false;
    }

    private boolean isInside(double mx, double my) {
        int h = wrapped.size() * lineHeight;
        return mx >= x && mx <= x + width &&
               my >= y && my <= y + h;
    }

    private int getCharIndex(double mx, double my) {
        if (wrapped.isEmpty()) return 0;
        int lineIdx = (int) ((my - y) / (double) lineHeight);
        lineIdx = Math.max(0, Math.min(wrapped.size() - 1, lineIdx));

        WrappedLine line = wrapped.get(lineIdx);
        String s = line.text();

        int px = (int) (mx - x);
        if (px <= 0) return line.startIndex();

        int curX = 0;
        for (int i = 0; i < s.length(); i++) {
            int cw = font.getWidth(s.substring(i, i + 1));
            if (px < curX + cw / 2) {
                return line.startIndex() + i;
            }
            curX += cw;
        }
        return line.startIndex() + s.length();
    }

    // -------------------------
    // 换行（使用 MC 内置 wrapLines，并建立 fullText 索引映射）
    // -------------------------
    private void rewrap() {
        this.wrapped = wrap(font, fullText, maxWidthPx);
    }

    public static List<WrappedLine> wrap(TextRenderer font, String fullText, int maxWidthPx) {
        List<WrappedLine> out = new ArrayList<>();
        if (fullText == null) fullText = "";
        if (maxWidthPx <= 0) maxWidthPx = 1;

        // 逐段处理 '\n'，并维护 fullText 中的全局索引
        int globalIndex = 0;
        String[] paragraphs = fullText.split("\n", -1);
        for (int p = 0; p < paragraphs.length; p++) {
            String para = paragraphs[p] != null ? paragraphs[p] : "";
            List<String> paraLines = wrapSingleLine(font, para, maxWidthPx);

            if (paraLines.isEmpty()) {
                out.add(new WrappedLine("", globalIndex));
            } else {
                int local = 0;
                for (String line : paraLines) {
                    out.add(new WrappedLine(line, globalIndex + local));
                    local += line.length();
                }
            }

            // 段落之间有 '\n'（最后一个段落后没有）
            globalIndex += para.length();
            if (p < paragraphs.length - 1) {
                globalIndex += 1; // '\n'
            }
        }
        return out;
    }

    private static List<String> wrapSingleLine(TextRenderer font, String text, int maxWidthPx) {
        List<String> out = new ArrayList<>();
        if (text == null) text = "";

        // TextRenderer.wrapLines 返回 OrderedText，转换为纯字符串行
        List<OrderedText> wrapped = font.wrapLines(Text.literal(text), maxWidthPx);
        if (wrapped == null || wrapped.isEmpty()) {
            out.add(text);
            return out;
        }

        for (OrderedText ot : wrapped) {
            out.add(orderedTextToString(ot));
        }
        return out;
    }

    private static String orderedTextToString(OrderedText ordered) {
        if (ordered == null) return "";
        StringBuilder sb = new StringBuilder();
        ordered.accept(new CharacterVisitor() {
            @Override
            public boolean accept(int index, Style style, int codePoint) {
                sb.appendCodePoint(codePoint);
                return true;
            }
        });
        return sb.toString();
    }
}


