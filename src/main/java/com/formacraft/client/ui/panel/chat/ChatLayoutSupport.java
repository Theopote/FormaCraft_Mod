package com.formacraft.client.ui.panel.chat;

import com.formacraft.client.ai.context.SelectionContext;
import com.formacraft.client.ui.panel.ChatPanel;
import com.formacraft.client.ui.UiTheme;
import net.minecraft.client.gui.DrawContext;

public final class ChatLayoutSupport {
    private ChatLayoutSupport() {}
    public static ChatPanel.ChatLayout computeChatLayout(ChatPanel host) {

        int innerX = host.getPanelX() + ChatPanel.CHAT_PADDING_H;
        int innerY = host.getContentY() + ChatPanel.CHAT_PADDING_V;
        int innerW = Math.max(1, host.getPanelWidth() - ChatPanel.CHAT_PADDING_H * 2);
        int innerH = Math.max(1, host.getContentHeight() - ChatPanel.CHAT_PADDING_V * 2);

        int selectionHintHeight = SelectionContext.hasSelection() ? 10 : 0;
        int inputAreaHeight = host.getInputHeight(selectionHintHeight);
        int chatBottom = innerY + innerH - inputAreaHeight - ChatPanel.CHAT_DIVIDER_HEIGHT;
        int inputY = chatBottom + ChatPanel.CHAT_DIVIDER_HEIGHT;

        return new ChatPanel.ChatLayout(
                innerX,
                innerY,
                innerW,
                innerH,
                innerY,
                chatBottom,
                inputY,
                inputAreaHeight,
                selectionHintHeight
        );
    
    }

    public static ChatPanel.InputBounds computeInputBounds(ChatPanel host, ChatPanel.ChatLayout layout) {

        int innerX = layout.innerX();
        int innerW = layout.innerW();
        int inputY = layout.inputY();
        int inputAreaHeight = layout.inputAreaHeight();
        int selectionHintHeight = layout.selectionHintHeight();

        int btnX = innerX + innerW - ChatPanel.SEND_BUTTON_SIZE - 1;
        int btnY = inputY + inputAreaHeight - ChatPanel.SEND_BUTTON_SIZE - 1;
        int stopY = btnY - ChatPanel.SEND_BUTTON_SIZE - ChatPanel.STOP_BUTTON_SIZE - 2;

        int inputBoxX = innerX + 1;
        int inputBoxY = inputY + 1 + selectionHintHeight;
        int inputBoxW = innerW - ChatPanel.SEND_BUTTON_SIZE - 3;
        int inputBoxH = inputAreaHeight - 2 - selectionHintHeight;

        return new ChatPanel.InputBounds(inputBoxX, inputBoxY, inputBoxW, inputBoxH, btnX, btnY, stopY);
    
    }

    public static void enableScissor(DrawContext ctx, int x0, int y0, int x1, int y1) {

        if (x1 > x0 && y1 > y0) {
            ctx.enableScissor(x0, y0, x1, y1);
        }
    
    }

    public static void disableScissor(DrawContext ctx, int x0, int y0, int x1, int y1) {

        if (x1 > x0 && y1 > y0) {
            ctx.disableScissor();
        }
    
    }

    public static int chatLineHeight() {

        return UiTheme.CHAT_LINE_HEIGHT;
    
    }

    public static double getScaledMouseX(ChatPanel host) {

        return host.client.mouse.getX() / host.client.getWindow().getScaleFactor();
    
    }

    public static double getScaledMouseY(ChatPanel host) {

        return host.client.mouse.getY() / host.client.getWindow().getScaleFactor();
    
    }
}
