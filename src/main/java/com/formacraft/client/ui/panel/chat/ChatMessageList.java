package com.formacraft.client.ui.panel.chat;

import com.formacraft.client.ui.input.InputRouter;
import com.formacraft.client.ui.panel.ChatPanel;
import com.formacraft.client.ui.text.SelectableTextBlock;
import com.formacraft.common.model.build.BuildingSpec;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.net.*;
import java.net.http.*;

public final class ChatMessageList {
    private ChatMessageList() {}
    public static void drawMessages(ChatPanel host, DrawContext ctx, int innerX, int chatTop, int innerW, int chatBottom, int lineHeight) {

        int availableWidth = Math.max(1, innerW - ChatPanel.CHAT_BUBBLE_INSET * 2);
        host.renderedMessages.clear();

        // 选中的消息被删除/索引越界时，清空选区
        if (host.selectableMsgIndex >= host.messages.size()) {
            host.selectableMsgIndex = -1;
            host.selectingText = false;
            host.selectable.clearSelection();
        }

        if (host.messages.isEmpty()) {
            // 空提示
            ctx.drawText(
                    host.client.textRenderer,
                    Text.translatable("formacraft.chat.hint"),
                    innerX + ChatPanel.CHAT_BUBBLE_INSET,
                    chatBottom - lineHeight - ChatPanel.CHAT_BOTTOM_INSET,
                    0x888888,
                    false
            );
            return;
        }

        // 计算总内容高度（像素）与最大滚动偏移
        int totalContentHeight = 0;
        for (int idx = host.messages.size() - 1; idx >= 0; idx--) {
            ChatMessage msg = host.messages.get(idx);

            String measureText;
            if (msg.type == ChatMessage.MessageType.THINKING) {
                int dots = (int) ((System.currentTimeMillis() / 350) % 4);
                String base = (msg.text == null || msg.text.isBlank())
                        ? Text.translatable("formacraft.chat.thinking").getString()
                        : msg.text;
                measureText = base + ".".repeat(dots);
            } else {
                measureText = msg.text;
            }

            int maxWidthPx = Math.max(1, availableWidth - ChatPanel.CHAT_BUBBLE_TEXT_PAD);
            List<SelectableTextBlock.WrappedLine> wrapped =
                    SelectableTextBlock.wrap(host.client.textRenderer, measureText, maxWidthPx);
            int bubbleHeight = wrapped.size() * lineHeight + 2;
            totalContentHeight += bubbleHeight;
            totalContentHeight += msg.hasSpecSummary() ? (ChatPanel.CHAT_SPEC_SUMMARY_HEIGHT + 6) : ChatPanel.CHAT_MESSAGE_GAP;
        }

        int visibleHeight = Math.max(1, chatBottom - chatTop - ChatPanel.CHAT_BOTTOM_INSET);
        int maxOffset = Math.max(0, totalContentHeight - visibleHeight);
        host.chatMaxScrollOffset = maxOffset;
        if (host.scrollOffset > maxOffset) host.scrollOffset = maxOffset;
        if (host.scrollOffset < 0) host.scrollOffset = 0;

        // 自底向上布局：p = 距最新消息底部的累计高度；host.scrollOffset 平移整个内容区
        // host.scrollOffset=0 → 贴底显示最新；host.scrollOffset=max → 顶部对齐最旧消息
        int p = 0;

        for (int idx = host.messages.size() - 1; idx >= 0; idx--) {
            ChatMessage msg = host.messages.get(idx);

            // 包装文本（考虑字体大小）
            // wrapLines 使用真实像素宽度；气泡左右各有 4px padding，所以扣掉 8px
            int maxWidthPx = Math.max(1, availableWidth - ChatPanel.CHAT_BUBBLE_TEXT_PAD);
            
            // 需要绘制 caret（流式）/ thinking 点点点动画
            boolean caret = false;
            String displayText;
            if (msg.type == ChatMessage.MessageType.THINKING) {
                int dots = (int) ((System.currentTimeMillis() / 350) % 4);
                String base = (msg.text == null || msg.text.isBlank())
                        ? Text.translatable("formacraft.chat.thinking").getString()
                        : msg.text;
                displayText = base + ".".repeat(dots);
            } else if (msg.type == ChatMessage.MessageType.STREAMING) {
                displayText = msg.text;
                caret = true;
            } else {
                displayText = msg.text;
            }
            
            List<SelectableTextBlock.WrappedLine> wrapped =
                    SelectableTextBlock.wrap(host.client.textRenderer, displayText, maxWidthPx);

            int bubbleHeight = wrapped.size() * lineHeight + 2;  // 上下各 1px 内边距
            int blockHeight = bubbleHeight + (msg.hasSpecSummary() ? (ChatPanel.CHAT_SPEC_SUMMARY_HEIGHT + 8) : ChatPanel.CHAT_MESSAGE_GAP);
            int bubbleBottom = chatBottom - ChatPanel.CHAT_BOTTOM_INSET - p + host.scrollOffset;
            int bubbleTop = bubbleBottom - bubbleHeight;

            // 完全在可视区下方（更新消息）：跳过绘制，继续向更旧消息遍历
            if (bubbleTop >= chatBottom) {
                p += blockHeight;
                continue;
            }
            // 完全在可视区上方：更旧的消息也在上方，可停止
            if (bubbleBottom <= chatTop) {
                break;
            }

            // 玩家消息右对齐，AI 消息左对齐
            int bubbleX;
            int bgColor;
            int textColor;
            if (msg.fromPlayer) {
                bubbleX = innerX + innerW - availableWidth - ChatPanel.CHAT_BUBBLE_INSET;
                bgColor = 0xAA004466;
                textColor = 0xFFCCFFFF;
            } else {
                bubbleX = innerX + ChatPanel.CHAT_BUBBLE_INSET;
                if (msg.type == ChatMessage.MessageType.ERROR) {
                    bgColor = 0xAA662222;
                    textColor = 0xFFFFDDDD;
                } else if (msg.type == ChatMessage.MessageType.SYSTEM) {
                    bgColor = 0xAA222222;
                    textColor = 0xFFCCCCCC;
                } else {
                    bgColor = 0xAA333333;
                    textColor = 0xFFFFFFFF;
                }
            }

            // 气泡背景
            ctx.fill(bubbleX, bubbleTop, bubbleX + availableWidth, bubbleBottom, bgColor);

            // 文本（减小上边距，通过调整行高实现紧凑效果）
            int textY = bubbleTop + 1;
            int textX = bubbleX + 1;
            boolean isSelectedMsg = (idx == host.selectableMsgIndex);
            if (isSelectedMsg) {
                host.selectable.setBounds(textX, textY, maxWidthPx, lineHeight);
                host.selectable.setText(displayText);
                host.selectable.render(ctx, textColor);
            } else {
                for (SelectableTextBlock.WrappedLine line : wrapped) {
                    ctx.drawText(host.client.textRenderer, Text.literal(line.text()), textX, textY, textColor, false);
                    textY += lineHeight;
                }
            }

            // caret（流式打印中的光标符号）
            if (caret) {
                SelectableTextBlock.WrappedLine last = wrapped.isEmpty() ? null : wrapped.getLast();
                if (last != null) {
                    int cy = bubbleTop + 2 + (wrapped.size() - 1) * lineHeight;
                    int cx = textX + host.client.textRenderer.getWidth(last.text());
                    // ASCII caret，避免字体缺字
                    ctx.drawText(host.client.textRenderer, Text.literal("|"), cx, cy, textColor, false);
                }
            }

            // 记录本帧可选中区域（只允许选择非 thinking 的消息）
            ChatPanel.RenderedMessage rm = new ChatPanel.RenderedMessage();
            rm.msgIndex = idx;
            rm.bubbleX = bubbleX;
            rm.bubbleTop = bubbleTop;
            rm.bubbleBottom = bubbleBottom;
            rm.bubbleW = availableWidth;
            rm.textX = textX;
            rm.textY = bubbleTop + 2;
            rm.maxWidthPx = maxWidthPx;
            rm.lineHeight = lineHeight;
            rm.displayText = displayText;
            rm.selectable = msg.type != ChatMessage.MessageType.THINKING;
            host.renderedMessages.add(rm);

            // 如果 AI 有 spec，渲染一个「摘要卡片」
            if (msg.hasSpecSummary()) {
                int specY = bubbleTop - 6;
                drawSpecSummary(host, ctx, msg.spec, bubbleX, specY, availableWidth, lineHeight);
            }

            p += blockHeight;
        }
    
    }

    public static void tickTextSelectionDrag(ChatPanel host) {

        if (host.selectableMsgIndex < 0) return;
        if (!host.selectingText) return;

        double mx = InputRouter.getMouseX();
        double my = InputRouter.getMouseY();

        if (InputRouter.leftDown) {
            host.selectable.mouseDragged(mx, my);
        } else {
            host.selectingText = false;
            host.selectable.mouseReleased();
        }
    
    }

    public static void tickInputSelectionDrag(ChatPanel host) {

        if (host.inputBox == null || !host.inputBox.isSelecting()) return;

        double mx = InputRouter.getMouseX();
        double my = InputRouter.getMouseY();

        if (InputRouter.leftDown) {
            host.inputBox.mouseDragged(mx, my);
        } else {
            host.inputBox.mouseReleased();
        }
    
    }

    public static void drawSpecSummary(ChatPanel host, DrawContext ctx, BuildingSpec spec, int x, int y, int w, int lineHeight) {

        // 摘要卡片背景
        int cardHeight = ChatPanel.CHAT_SPEC_SUMMARY_HEIGHT;
        ctx.fill(x, y - cardHeight, x + w, y, 0x55333333);

        int ty = y - cardHeight + 4;

        // 建筑类型
        com.formacraft.common.model.build.BuildingType type = spec.getType();
        if (type != null) {
            ctx.drawText(host.client.textRenderer,
                    Text.translatable("formacraft.preview.type", type.name()), x + 6, ty, 0xFFFFFF, false);
            ty += lineHeight;
        }

        // 尺寸信息
        com.formacraft.common.model.build.Footprint footprint = spec.getFootprint();
        if (footprint != null) {
            String sizeText;
            if ("circle".equals(footprint.getShape())) {
                sizeText = Text.translatable("formacraft.preview.radius", footprint.getRadius()).getString();
            } else {
                sizeText = Text.translatable("formacraft.preview.size",
                        footprint.getWidth(), footprint.getDepth()).getString();
            }
            ctx.drawText(host.client.textRenderer, Text.literal(sizeText), x + 6, ty, 0xDDDDDD, false);
            ty += lineHeight;
        }

        ctx.drawText(host.client.textRenderer,
                Text.translatable("formacraft.preview.height", spec.getHeight()), x + 6, ty, 0xDDDDDD, false);
    
    }
}
