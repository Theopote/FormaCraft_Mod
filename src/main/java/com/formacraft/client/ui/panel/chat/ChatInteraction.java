package com.formacraft.client.ui.panel.chat;

import com.formacraft.client.ui.panel.ChatPanel;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Click;
import net.minecraft.client.input.MouseInput;
import net.minecraft.text.Text;
import com.formacraft.client.ui.widget.HudClickSupport;
import org.lwjgl.glfw.GLFW;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.net.*;
import java.net.http.*;

public final class ChatInteraction {
    private ChatInteraction() {}
    public static boolean mouseClicked(ChatPanel host, double mouseX, double mouseY, int button) {

        host.ensureLayout();
        if (host.superMouseClicked(mouseX, mouseY, button)) return true; // 处理顶部 Tab 切换

        if (button != 0) return false;

        host.initWidgets();

        ChatPanel.ChatLayout layout = host.computeChatLayout();
        ChatPanel.InputBounds bounds = host.computeInputBounds(layout);
        int chatAreaBottom = layout.chatBottom();

        boolean generating = host.currentPrinter != null;

        Click click = new Click(mouseX, mouseY, new MouseInput(button, 0));
        host.referenceButton.setPosition(bounds.btnX(), bounds.btnY() - ChatPanel.SEND_BUTTON_SIZE - 1);
        if (button == 1 && host.referenceButton.isMouseOver(mouseX, mouseY)) {
            host.chooseReferenceImageFile();
            host.blurInput();
            return true;
        }
        if (HudClickSupport.click(host.referenceButton, click)) {
            host.blurInput();
            return true;
        }
        host.stopButton.setPosition(bounds.btnX(), bounds.stopY());
        host.stopButton.visible = generating;
        host.stopButton.active = generating;
        if (generating && HudClickSupport.click(host.stopButton, click)) {
            host.blurInput();
            return true;
        }

        host.sendButton.setPosition(bounds.btnX(), bounds.btnY());
        host.sendButton.visible = true;
        host.sendButton.active = true;
        if (HudClickSupport.click(host.sendButton, click)) {
            host.blurInput();
            return true;
        }

        // 在输出区点击：开始选择 / 或点击空白清除
        if (mouseY >= layout.chatTop() && mouseY < layout.chatBottom() && tryStartSelection(host, mouseX, mouseY)) {
            // 选中输出文本后，不要抢输入框焦点
            host.blurInput();
            return true;
        } else {
            // 点击非输出文本：清除选区
            host.selectable.clearSelection();
            host.selectableMsgIndex = -1;
        }

        host.inputBox.setBounds(bounds.inputBoxX(), bounds.inputBoxY(), bounds.inputBoxW(), bounds.inputBoxH());
        if (host.inputBox.mouseClicked(mouseX, mouseY)) {
            // 点击输入框即退出历史浏览态（保持当前内容）
            host.historyIndex = -1;
            host.selectable.clearSelection();
            host.selectableMsgIndex = -1;
            host.selectingText = false;
            return true;
        }

        // 点在面板内其它区域：取消输入焦点
        host.blurInput();
        return false;
    
    }

    public static boolean mouseDragged(ChatPanel host, double mouseX, double mouseY, int button, double deltaX, double deltaY) {

        if (button == 0 && host.inputBox != null && host.inputBox.isSelecting()) {
            host.inputBox.mouseDragged(mouseX, mouseY);
            return true;
        }
        return false;
    
    }

    public static boolean mouseReleased(ChatPanel host, double mouseX, double mouseY, int button) {

        if (host.inputBox != null) {
            host.inputBox.mouseReleased();
        }
        return host.superMouseReleased(mouseX, mouseY, button);
    
    }

    public static boolean tryStartSelection(ChatPanel host, double mouseX, double mouseY) {

        if (host.renderedMessages.isEmpty()) return false;

        // 从上到下优先（越靠上越先命中？）这里用“最后绘制的在上层”，所以从后往前
        for (int i = host.renderedMessages.size() - 1; i >= 0; i--) {
            ChatPanel.RenderedMessage rm = host.renderedMessages.get(i);
            if (!rm.selectable) continue;

            boolean insideBubble =
                    mouseX >= rm.bubbleX && mouseX <= rm.bubbleX + rm.bubbleW &&
                    mouseY >= rm.bubbleTop && mouseY <= rm.bubbleBottom;
            if (!insideBubble) continue;

            host.selectableMsgIndex = rm.msgIndex;
            host.selectable.setBounds(rm.textX, rm.textY, rm.maxWidthPx, rm.lineHeight);
            host.selectable.setText(rm.displayText);
            host.selectingText = host.selectable.mousePressed(mouseX, mouseY);
            return host.selectingText;
        }
        return false;
    
    }

    public static void mouseScrolled(ChatPanel host, double mouseX, double mouseY, double amount) {

        host.ensureLayout();
        if (!host.isMouseOver(mouseX, mouseY)) return;

        ChatPanel.ChatLayout layout = host.computeChatLayout();
        boolean inChatArea = mouseX >= layout.innerX()
                && mouseX <= layout.innerX() + layout.innerW()
                && mouseY >= layout.chatTop()
                && mouseY < layout.chatBottom();

        // 输入框区域：滚动输入内容；聊天区：滚动消息
        if (!inChatArea && host.inputBox != null && host.inputBox.isMouseOver(mouseX, mouseY)) {
            host.inputBox.mouseScrolled(amount);
            return;
        }
        if (!inChatArea) {
            return;
        }

        // 像素级平滑滚动：保留小数余量，触控板/高精滚轮体验更稳定
        // amount > 0 向上看历史；amount < 0 回到底部方向
        final double pxPerWheelUnit = 12.0;
        host.scrollRemainder += amount * pxPerWheelUnit;

        int deltaPx;
        if (host.scrollRemainder > 0) {
            deltaPx = (int) Math.floor(host.scrollRemainder);
        } else {
            deltaPx = (int) Math.ceil(host.scrollRemainder);
        }

        if (deltaPx != 0) {
            host.scrollOffset += deltaPx;
            if (host.scrollOffset < 0) host.scrollOffset = 0;
            if (host.scrollOffset > host.chatMaxScrollOffset) host.scrollOffset = host.chatMaxScrollOffset;
            host.scrollRemainder -= deltaPx;
        }
    
    }

    public static void keyPressed(ChatPanel host, int keyCode, int scanCode, int modifiers) {

        // Ctrl+C：复制输出区选中内容（优先级最高，不影响输入框 Ctrl+C）
        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        if (ctrl && keyCode == GLFW.GLFW_KEY_C && host.selectable.hasSelection()) {
            String selected = host.selectable.getSelectedText();
            if (!selected.isEmpty() && host.client != null && host.client.keyboard != null) {
                host.client.keyboard.setClipboard(selected);
                return;
            }
        }

        // 输入框未获焦时不处理编辑键（需点击输入区才可输入）
        if (host.inputBox == null || !host.inputBox.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) host.blurInput();
            return;
        }

        // Backspace
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            host.historyIndex = -1; // 编辑行为：退出历史浏览态
            host.inputBox.backspace();
            return;
        }

        // Enter / 小键盘 Enter：发送；Shift+Enter：换行
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
            if (shift) {
                // Shift+Enter：换行
                host.historyIndex = -1;
                host.inputBox.insertNewLine();
            } else {
                // Enter：发送
                host.sendCurrentMessage();
            }
            return;
        }

        // ↑/↓ 输入历史：仅在输入为空时进入；已在浏览态时可继续翻阅。
        // 有内容编辑时 ↑/↓ 只移动光标，避免单行输入时误把上次消息换进来。
        boolean noMods = (modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_SHIFT | GLFW.GLFW_MOD_SUPER)) == 0;
        boolean browsingHistory = host.historyIndex != -1;
        boolean inputEmpty = host.inputBox.getText().isEmpty();
        if (noMods && keyCode == GLFW.GLFW_KEY_UP && host.inputBox.isCursorAtFirstLine()
                && (browsingHistory || inputEmpty) && !host.inputHistory.isEmpty()) {
            if (host.historyIndex == -1) {
                host.historyDraft = host.inputBox.getText();
                host.historyIndex = host.inputHistory.size() - 1;
            } else if (host.historyIndex > 0) {
                host.historyIndex--;
            }
            host.inputBox.setText(host.inputHistory.get(host.historyIndex));
            return;
        }
        if (noMods && keyCode == GLFW.GLFW_KEY_DOWN && host.inputBox.isCursorAtLastLine() && browsingHistory) {
            if (host.historyIndex < host.inputHistory.size() - 1) {
                host.historyIndex++;
                host.inputBox.setText(host.inputHistory.get(host.historyIndex));
            } else {
                host.historyIndex = -1;
                host.inputBox.setText(host.historyDraft);
            }
            return;
        }

        // 其他按键交给输入框处理（方向键、Ctrl+C/V/X、Home/End 等）
        if (keyCode != GLFW.GLFW_KEY_UP && keyCode != GLFW.GLFW_KEY_DOWN) {
            // 非上下键的编辑/移动：退出历史浏览态（保持当前内容）
            host.historyIndex = -1;
        }
        host.inputBox.keyPressed(keyCode, scanCode, modifiers);
    
    }

    public static void charTyped(ChatPanel host, char chr) {

        // 过滤控制字符；未获焦时不输入
        if (chr == '\r' || chr == '\n') return;
        if (host.inputBox == null || !host.inputBox.isFocused()) return;
        host.historyIndex = -1; // 输入即退出历史浏览态
        host.inputBox.charTyped(chr);
    
    }

    public static boolean drawCustomTooltip(ChatPanel host, DrawContext ctx, double mouseX, double mouseY) {

        host.initWidgets();

        if (host.referenceButton.isMouseOver(mouseX, mouseY)) {
            String key = host.hasReferenceImage() ? "formacraft.chat.reference.remove" : "formacraft.chat.reference.add";
            host.drawTooltipCompatPublic(ctx, List.of(Text.translatable(key)), (int) mouseX, (int) mouseY);
            return true;
        }
        if (host.sendButton != null && host.sendButton.visible && host.sendButton.isMouseOver(mouseX, mouseY)) {
            host.drawTooltipCompatPublic(ctx,
                        java.util.Collections.singletonList(Text.translatable("formacraft.chat.send.tooltip")),
                        (int) mouseX, (int) mouseY);
            return true;
        }

        if (host.stopButton != null && host.stopButton.visible && host.stopButton.isMouseOver(mouseX, mouseY)) {
            host.drawTooltipCompatPublic(ctx,
                        java.util.Collections.singletonList(Text.translatable("formacraft.chat.stop.tooltip")),
                        (int) mouseX, (int) mouseY);
                return true;
            }

        return false;
    
    }
}
