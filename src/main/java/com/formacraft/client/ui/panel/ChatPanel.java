package com.formacraft.client.ui.panel;

import com.formacraft.ai.AIResult;
import com.formacraft.ai.AIService;
import com.formacraft.ai.AIServiceManager;
import com.formacraft.ai.AICancelToken;
import com.formacraft.ai.BuildingRequest;
import com.formacraft.client.ui.widget.MultilineTextInput;
import com.formacraft.client.ui.panel.chat.AIStreamPrinter;
import com.formacraft.client.ui.panel.chat.ChatMessage;
import com.formacraft.client.ui.input.InputRouter;
import com.formacraft.client.ui.text.SelectableTextBlock;
import com.formacraft.common.builder.AutoBuilder;
import com.formacraft.common.builder.BuildingBlueprint;
import com.formacraft.common.builder.BuildingPlanner;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.config.SettingsConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * FormaCraft 主聊天面板（左侧固定栏 + 多行输入 + 可滚动消息区域）
 * 支持动态字体大小调整
 */
public class ChatPanel extends BasePanel {

    private final MinecraftClient client = MinecraftClient.getInstance();

    // ==== 后端服务 ====
    private final AIService aiService = new AIServiceManager();
    private final BuildingPlanner planner = new BuildingPlanner();
    private final AutoBuilder autoBuilder = new AutoBuilder();

    // ==== 对话状态 ====
    private final List<ChatMessage> messages = new ArrayList<>();
    private String sessionId = java.util.UUID.randomUUID().toString();

    // 多行输入组件
    private MultilineTextInput inputBox;

    // 输入历史（↑/↓）：只在光标位于首/末行时触发，避免与多行光标移动冲突
    private final List<String> inputHistory = new ArrayList<>();
    private int historyIndex = -1;     // -1 表示未浏览历史（编辑当前草稿）
    private String historyDraft = "";  // 进入历史浏览前的草稿

    // 滚动偏移：从底部往上偏移多少条消息
    private int scrollOffset = 0;

    // ========== AI 输出流式打印（token 队列 + typewriter） ==========
    private AIStreamPrinter currentPrinter = null;
    private AICancelToken currentCancelToken = null;
    private CompletableFuture<AIResult> currentRequestFuture = null;

    // ========== 输出区文字选择（拖拽选区 + Ctrl+C） ==========
    private final SelectableTextBlock selectable = new SelectableTextBlock(client);
    private int selectableMsgIndex = -1;
    private boolean selectingText = false;

    private static class RenderedMessage {
        int msgIndex;
        int bubbleX, bubbleTop, bubbleBottom, bubbleW;
        int textX, textY, maxWidthPx, lineHeight;
        String displayText;
        boolean selectable;
    }

    private final List<RenderedMessage> renderedMessages = new ArrayList<>();

    // Padding（减小边距）
    private static final int PADDING = 2;

    // 发送按钮（与顶部按钮/标签统一为 12x12 的方形）
    private static final int SEND_BUTTON_SIZE = 12;
    private int sendBtnX, sendBtnY, sendBtnW, sendBtnH;
    private int stopBtnX, stopBtnY, stopBtnW, stopBtnH;

    public ChatPanel() {
        // 初始输入框，在第一次 render 时会根据当前面板尺寸重新 setBounds
        this.inputBox = new MultilineTextInput(0, 0, 10, 48);
        // 聊天输入：限制总长度与最大行数，避免粘贴超大文本造成 UI 卡顿
        this.inputBox.setMaxChars(2048);
        this.inputBox.setMaxLines(12);
    }

    @Override
    protected void drawContents(DrawContext ctx) {
        // 拖拽更新选区（不依赖额外鼠标事件：利用 InputRouter.leftDown + 每帧更新）
        tickTextSelectionDrag();

        // 每帧推进流式打印（不会阻塞）
        if (currentPrinter != null) {
            currentPrinter.tick();
            if (currentPrinter.isFinished()) {
                currentPrinter = null;
            }
        }

        // 计算字体缩放（减小字体大小）
        float fontScale = SettingsConfig.INSTANCE.fontSize / 10.0f * 0.85f;  // 减小到85%
        int baseFont = client.textRenderer.fontHeight;          // 默认字体高度
        int lineHeight = (int)(baseFont * fontScale + 1);       // 行距（更紧凑）

        // 面板内边距
        int padding = PADDING;
        int titleBarHeight = 22;

        int innerX = panelX + padding;
        int innerY = panelY + titleBarHeight + padding;
        int innerW = panelWidth - padding * 2;
        int innerH = panelHeight - titleBarHeight - padding * 2;

        // 输入区域高度（根据字体大小动态调整）
        int inputAreaHeight = getInputHeight();
        int chatAreaBottom = innerY + innerH - inputAreaHeight - 4;

        // 绘制聊天区域背景（半透明）
        ctx.fill(innerX, innerY, innerX + innerW, chatAreaBottom, 0x201A1A1A);

        // 绘制消息（自下而上）
        drawMessages(ctx, innerX, innerY, innerW, chatAreaBottom, lineHeight, fontScale);

        // 绘制输入框背景 + 输入框 + 发送按钮
        int inputY = chatAreaBottom + 6;
        drawInputArea(ctx, innerX, inputY, innerW, inputAreaHeight);
        
        // 在输入区域上方绘制分隔线（参考 Quick Settings 样式）
        ctx.fill(innerX, chatAreaBottom, innerX + innerW, chatAreaBottom + 1, 0x66FFFFFF);
        
        // 注意：发送按钮的 tooltip 应该在 BasePanel 的 drawTooltip 中处理
        // 这里不再绘制，避免覆盖其他按钮的 tooltip
    }

    /**
     * 自下而上绘制消息气泡，支持 scrollOffset 和字体大小
     */
    private void drawMessages(DrawContext ctx, int innerX, int chatTop, int innerW, int chatBottom, int lineHeight, float fontScale) {
        int availableWidth = innerW - 20;
        renderedMessages.clear();

        // 选中的消息被删除/索引越界时，清空选区
        if (selectableMsgIndex >= messages.size()) {
            selectableMsgIndex = -1;
            selectingText = false;
            selectable.clearSelection();
        }

        if (messages.isEmpty()) {
            // 空提示
            ctx.drawText(
                    client.textRenderer,
                    Text.translatable("formacraft.chat.hint", "输入描述让 AI 为你建造…"),
                    innerX + 4,
                    chatBottom - lineHeight - 2,
                    0x888888,
                    false
            );
            return;
        }

        // 计算最大可用 scrollOffset
        int maxOffset = Math.max(0, messages.size() - 1);
        if (scrollOffset > maxOffset) scrollOffset = maxOffset;
        if (scrollOffset < 0) scrollOffset = 0;

        int startIndex = messages.size() - 1 - scrollOffset;
        int y = chatBottom - 6; // 从下往上绘制

        for (int idx = startIndex; idx >= 0; idx--) {
            ChatMessage msg = messages.get(idx);

            // 包装文本（考虑字体大小）
            int maxWidthPx = Math.max(1, (int)(availableWidth / fontScale));
            
            // 需要绘制 caret（流式）/ thinking 点点点动画
            boolean caret = false;
            String displayText;
            if (msg.type == ChatMessage.MessageType.THINKING) {
                int dots = (int) ((System.currentTimeMillis() / 350) % 4);
                displayText = "AI 正在思考" + ".".repeat(dots);
            } else if (msg.type == ChatMessage.MessageType.STREAMING) {
                displayText = msg.text;
                caret = true;
            } else {
                displayText = msg.text;
            }
            
            List<SelectableTextBlock.WrappedLine> wrapped =
                    SelectableTextBlock.wrap(client.textRenderer, displayText, maxWidthPx);

            int bubbleHeight = wrapped.size() * lineHeight + 4;  // 减小气泡内边距
            int bubbleBottom = y;
            int bubbleTop = bubbleBottom - bubbleHeight;

            // 如果超出聊天区域上边界就停止绘制
            if (bubbleTop < chatTop) {
                break;
            }

            // 玩家消息右对齐，AI 消息左对齐
            int bubbleX;
            int bgColor;
            int textColor;
            if (msg.fromPlayer) {
                bubbleX = innerX + innerW - availableWidth - 4;
                bgColor = 0xAA004466;
                textColor = 0xFFCCFFFF;
            } else {
                bubbleX = innerX + 4;
                bgColor = 0xAA333333;
                textColor = 0xFFFFFFFF;
            }

            // 气泡背景
            ctx.fill(bubbleX, bubbleTop, bubbleX + availableWidth, bubbleBottom, bgColor);

            // 文本（减小上边距，通过调整行高实现紧凑效果）
            int textY = bubbleTop + 2;
            int textX = bubbleX + 4;
            boolean isSelectedMsg = (idx == selectableMsgIndex);
            if (isSelectedMsg) {
                selectable.setBounds(textX, textY, maxWidthPx, lineHeight);
                selectable.setText(displayText);
                selectable.render(ctx, textColor);
            } else {
                for (SelectableTextBlock.WrappedLine line : wrapped) {
                    ctx.drawText(client.textRenderer, Text.literal(line.text()), textX, textY, textColor, false);
                    textY += lineHeight;
                }
            }

            // caret（流式打印中的光标符号）
            if (caret) {
                SelectableTextBlock.WrappedLine last = wrapped.isEmpty() ? null : wrapped.getLast();
                if (last != null) {
                    int cy = bubbleTop + 2 + (wrapped.size() - 1) * lineHeight;
                    int cx = textX + client.textRenderer.getWidth(last.text());
                    ctx.drawText(client.textRenderer, Text.literal("▍"), cx, cy, textColor, false);
                }
            }

            // 记录本帧可选中区域（只允许选择非 thinking 的消息）
            RenderedMessage rm = new RenderedMessage();
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
            renderedMessages.add(rm);

            // 如果 AI 有 spec，渲染一个「摘要卡片」
            if (msg.hasSpecSummary()) {
                int specY = bubbleTop - 6;
                drawSpecSummary(ctx, msg.spec, bubbleX, specY, availableWidth, lineHeight);
                y = specY - 6;
            } else {
                // 为下一条消息留出间距
                y = bubbleTop - 6;
            }
        }
    }

    private void tickTextSelectionDrag() {
        if (selectableMsgIndex < 0) return;
        if (!selectingText) return;

        double mx = InputRouter.getMouseX();
        double my = InputRouter.getMouseY();

        if (InputRouter.leftDown) {
            selectable.mouseDragged(mx, my);
        } else {
            selectingText = false;
            selectable.mouseReleased();
        }
    }

    /**
     * 绘制 Spec 摘要卡片
     */
    private void drawSpecSummary(DrawContext ctx, BuildingSpec spec, int x, int y, int w, int lineHeight) {
        // 摘要卡片背景
        int cardHeight = (int)(50 * (SettingsConfig.INSTANCE.fontSize / 10.0f));
        ctx.fill(x, y - cardHeight, x + w, y, 0x55333333);

        int ty = y - cardHeight + 4;

        // 建筑类型
        com.formacraft.common.model.build.BuildingType type = spec.getType();
        if (type != null) {
            ctx.drawText(client.textRenderer, 
                    Text.literal("📐 " + type.name()), 
                    x + 6, ty, 0xFFFFFF, false);
            ty += lineHeight;
        }

        // 尺寸信息
        com.formacraft.common.model.build.Footprint footprint = spec.getFootprint();
        if (footprint != null) {
            String sizeText;
            if ("circle".equals(footprint.getShape())) {
                sizeText = "Radius: " + footprint.getRadius();
            } else {
                sizeText = "Size: " + footprint.getWidth() + "×" + footprint.getDepth();
            }
            ctx.drawText(client.textRenderer, Text.literal(sizeText), x + 6, ty, 0xDDDDDD, false);
            ty += lineHeight;
        }

        // 高度
        ctx.drawText(client.textRenderer,
                Text.literal("Height: " + spec.getHeight()),
                x + 6, ty, 0xDDDDDD, false);
    }

    /**
     * 绘制输入区域（多行输入框 + Send 按钮）
     */
    private void drawInputArea(DrawContext ctx, int innerX, int inputY, int innerW, int inputAreaHeight) {
        // 背景（更透明）
        ctx.fill(innerX, inputY, innerX + innerW, inputY + inputAreaHeight, 0x401A1A1A);

        int btnWidth = SEND_BUTTON_SIZE;
        int btnHeight = SEND_BUTTON_SIZE;
        int btnX = innerX + innerW - btnWidth - 2;
        int btnY = inputY + inputAreaHeight - btnHeight - 2;

        // 记录给 tooltip / click 使用
        sendBtnX = btnX;
        sendBtnY = btnY;
        sendBtnW = btnWidth;
        sendBtnH = btnHeight;

        // Stop 按钮（仅流式打印时显示，位于发送按钮上方）
        boolean streaming = currentPrinter != null;
        stopBtnW = 72;
        stopBtnH = 14;
        stopBtnX = innerX + innerW - stopBtnW - 2;
        stopBtnY = btnY - stopBtnH - 2;

        // 检查鼠标是否悬停在按钮上
        double mouseX = client.mouse.getX() / client.getWindow().getScaleFactor();
        double mouseY = client.mouse.getY() / client.getWindow().getScaleFactor();
        boolean hovered = mouseX >= btnX && mouseX <= btnX + btnWidth && 
                         mouseY >= btnY && mouseY <= btnY + btnHeight;
        
        // 使用统一风格的方形按钮 + 图标
        drawMinecraftButton(ctx, btnX, btnY, btnWidth, btnHeight,
                           Text.literal("📤"), hovered);

        if (streaming) {
            boolean stopHovered = mouseX >= stopBtnX && mouseX <= stopBtnX + stopBtnW &&
                    mouseY >= stopBtnY && mouseY <= stopBtnY + stopBtnH;
            drawMinecraftButton(ctx, stopBtnX, stopBtnY, stopBtnW, stopBtnH,
                    Text.literal("⏹ 停止生成"), stopHovered);
        }

        // 输入框区域（根据字体大小调整高度，减小边距）
        int inputBoxX = innerX + 2;
        int inputBoxY = inputY + 2;
        int inputBoxW = innerW - btnWidth - 6;
        int inputBoxH = inputAreaHeight - 4;

        inputBox.setBounds(inputBoxX, inputBoxY, inputBoxW, inputBoxH);
        inputBox.render(ctx);
    }

    /**
     * 获取输入框高度（用于布局计算）
     */
    private int getInputHeight() {
        // 根据当前输入行数自动增长：到上限后固定高度，内部滚动由 MultilineTextInput 保证光标可见
        float fontScale = SettingsConfig.INSTANCE.fontSize / 10.0f * 0.85f;
        int baseFont = client.textRenderer.fontHeight;
        int lineHeight = (int) (baseFont * fontScale + 1);

        int minVisibleLines = 2;
        int maxVisibleLines = 6; // UI 上限：超过则输入框内部滚动
        int lines = Math.max(1, inputBox.getLineCount());
        int visible = Math.max(minVisibleLines, Math.min(maxVisibleLines, lines));

        // inputBoxH 需要满足：maxLinesVisible = (h - 8) / lineHeight ≈ visible
        int inputBoxH = 8 + visible * lineHeight;
        return inputBoxH + 4; // drawInputArea 中上下各 2px padding
    }
    
    /**
     * 重写自定义 tooltip 处理，添加发送按钮的 tooltip
     */
    @Override
    protected boolean drawCustomTooltip(DrawContext ctx, double mouseX, double mouseY) {
        // 检查发送按钮的 tooltip
        // 注意：sendBtnX/Y/W/H 在 drawInputArea 中设置，需要确保已经初始化
        if (sendBtnW > 0 && sendBtnH > 0) {
            if (mouseX >= sendBtnX && mouseX <= sendBtnX + sendBtnW &&
                mouseY >= sendBtnY && mouseY <= sendBtnY + sendBtnH) {
                ctx.drawTooltip(client.textRenderer,
                        java.util.Collections.singletonList(Text.translatable("formacraft.chat.send.tooltip")),
                        (int) mouseX, (int) mouseY);
                return true; // 已处理 tooltip
            }
        }

        if (currentPrinter != null && stopBtnW > 0 && stopBtnH > 0) {
            if (mouseX >= stopBtnX && mouseX <= stopBtnX + stopBtnW &&
                mouseY >= stopBtnY && mouseY <= stopBtnY + stopBtnH) {
                ctx.drawTooltip(client.textRenderer,
                        java.util.Collections.singletonList(Text.literal("中断生成")),
                        (int) mouseX, (int) mouseY);
                return true;
            }
        }
        return false; // 未处理，继续检查其他按钮
    }

    // ==========================
    //  输入&滚动事件
    // ==========================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true; // 处理顶部 Tab 切换

        if (button != 0) return false;

        int padding = PADDING;
        int titleBarHeight = 22;
        int innerX = panelX + padding;
        int innerY = panelY + titleBarHeight + padding;
        int innerW = panelWidth - padding * 2;
        int innerH = panelHeight - titleBarHeight - padding * 2;
        
        int inputAreaHeight = getInputHeight();
        int chatAreaBottom = innerY + innerH - inputAreaHeight - 4;
        int inputY = chatAreaBottom + 6;

        int btnWidth = SEND_BUTTON_SIZE;
        int btnHeight = SEND_BUTTON_SIZE;
        int btnX = innerX + innerW - btnWidth - 4;
        int btnY = inputY + inputAreaHeight - btnHeight - 4;

        // 点击 Stop 按钮（优先于发送）
        if (currentPrinter != null &&
                mouseX >= stopBtnX && mouseX <= stopBtnX + stopBtnW &&
                mouseY >= stopBtnY && mouseY <= stopBtnY + stopBtnH) {
            stopGenerating();
            selectable.clearSelection();
            selectableMsgIndex = -1;
            return true;
        }

        // 点击发送按钮
        if (mouseX >= btnX && mouseX <= btnX + btnWidth &&
                mouseY >= btnY && mouseY <= btnY + btnHeight) {
            sendCurrentMessage();
            selectable.clearSelection();
            selectableMsgIndex = -1;
            return true;
        }

        // 在输出区点击：开始选择 / 或点击空白清除
        if (tryStartSelection(mouseX, mouseY)) {
            // 选中输出文本后，不要抢输入框焦点
            inputBox.setFocused(false);
            return true;
        } else {
            // 点击非输出文本：清除选区
            selectable.clearSelection();
            selectableMsgIndex = -1;
        }

        // 点击输入框区域，设置焦点
        int inputBoxX = innerX + 4;
        int inputBoxY = inputY + 4;
        int inputBoxW = innerW - btnWidth - 12;
        int inputBoxH = inputAreaHeight - 8;
        
        if (mouseX >= inputBoxX && mouseX <= inputBoxX + inputBoxW &&
            mouseY >= inputBoxY && mouseY <= inputBoxY + inputBoxH) {
            inputBox.setFocused(true);
            // 点击输入框即退出历史浏览态（保持当前内容）
            historyIndex = -1;
            selectable.clearSelection();
            selectableMsgIndex = -1;
            // TODO: 可以在这里实现点击位置设置光标（需要计算点击位置对应的行列）
            return true;
        }

        return false;
    }

    private boolean tryStartSelection(double mouseX, double mouseY) {
        if (renderedMessages.isEmpty()) return false;

        // 从上到下优先（越靠上越先命中？）这里用“最后绘制的在上层”，所以从后往前
        for (int i = renderedMessages.size() - 1; i >= 0; i--) {
            RenderedMessage rm = renderedMessages.get(i);
            if (!rm.selectable) continue;

            boolean insideBubble =
                    mouseX >= rm.bubbleX && mouseX <= rm.bubbleX + rm.bubbleW &&
                    mouseY >= rm.bubbleTop && mouseY <= rm.bubbleBottom;
            if (!insideBubble) continue;

            selectableMsgIndex = rm.msgIndex;
            selectable.setBounds(rm.textX, rm.textY, rm.maxWidthPx, rm.lineHeight);
            selectable.setText(rm.displayText);
            selectingText = selectable.mousePressed(mouseX, mouseY);
            return selectingText;
        }
        return false;
    }

    @Override
    public void mouseScrolled(double mouseX, double mouseY, double amount) {
        // 只要在面板区域内滚动，就滚动聊天
        if (mouseX >= panelX && mouseX <= panelX + panelWidth) {
            if (amount > 0) {
                // 向上滚动，增加 offset（看更早的消息）
                scrollOffset = Math.min(scrollOffset + 1, Math.max(0, messages.size() - 1));
            } else if (amount < 0) {
                // 向下滚动，减少 offset（接近最新消息）
                scrollOffset = Math.max(0, scrollOffset - 1);
            }
        }
    }

    /**
     * 键盘按下事件（简化版，保持向后兼容）
     */
    @Override
    public void keyPressed(int keyCode) {
        keyPressed(keyCode, 0, 0);
    }

    /**
     * 键盘按下事件（完整版，带修饰符）：
     * - Enter：换行
     * - Shift+Enter：发送消息
     * - Backspace：删除一个字符
     * - 方向键、Home/End：光标移动
     * - Ctrl+C/V/X：复制粘贴剪切
     */
    @Override
    public void keyPressed(int keyCode, int scanCode, int modifiers) {
        // Ctrl+C：复制输出区选中内容（优先级最高，不影响输入框 Ctrl+C）
        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        if (ctrl && keyCode == GLFW.GLFW_KEY_C && selectable.hasSelection()) {
            String selected = selectable.getSelectedText();
            if (!selected.isEmpty() && client != null && client.keyboard != null) {
                client.keyboard.setClipboard(selected);
                return;
            }
        }

        // 设置输入框焦点
        inputBox.setFocused(true);

        // Backspace
        if (keyCode == 259) {
            historyIndex = -1; // 编辑行为：退出历史浏览态
            inputBox.backspace();
            return;
        }

        // Enter 键（KeyCode 257 是 GLFW_KEY_ENTER）
        if (keyCode == GLFW.GLFW_KEY_ENTER) {
            // 检查 Shift 键
            boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
            if (shift) {
                // Shift+Enter：发送消息
                sendCurrentMessage();
            } else {
                // Enter：换行
                historyIndex = -1;
                inputBox.insertNewLine();
            }
            return;
        }

        // ↑/↓ 输入历史：只在光标位于首行/末行且没有修饰键时触发
        boolean noMods = (modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_SHIFT | GLFW.GLFW_MOD_SUPER)) == 0;
        if (noMods && keyCode == GLFW.GLFW_KEY_UP && inputBox.isCursorAtFirstLine()) {
            if (!inputHistory.isEmpty()) {
                if (historyIndex == -1) {
                    historyDraft = inputBox.getText();
                    historyIndex = inputHistory.size() - 1;
                } else if (historyIndex > 0) {
                    historyIndex--;
                }
                inputBox.setText(inputHistory.get(historyIndex));
            }
            return;
        }
        if (noMods && keyCode == GLFW.GLFW_KEY_DOWN && inputBox.isCursorAtLastLine()) {
            if (historyIndex != -1) {
                if (historyIndex < inputHistory.size() - 1) {
                    historyIndex++;
                    inputBox.setText(inputHistory.get(historyIndex));
                } else {
                    historyIndex = -1;
                    inputBox.setText(historyDraft);
                }
            }
            return;
        }

        // 其他按键交给输入框处理（方向键、Ctrl+C/V/X、Home/End 等）
        // 注意：这会包含方向键上下移动“行内光标”，只有在到达边界才会被上面的历史逻辑接管
        if (keyCode != GLFW.GLFW_KEY_UP && keyCode != GLFW.GLFW_KEY_DOWN) {
            // 非上下键的编辑/移动：退出历史浏览态（保持当前内容）
            historyIndex = -1;
        }
        inputBox.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void charTyped(char chr) {
        // 过滤控制字符
        if (chr == '\r' || chr == '\n') return;
        historyIndex = -1; // 输入即退出历史浏览态
        inputBox.charTyped(chr);
    }

    // ==========================
    //  核心逻辑：发送消息 & 调 AI
    // ==========================

    private void sendCurrentMessage() {
        String text = inputBox.getText().trim();
        if (text.isEmpty()) return;

        if (client.player == null || client.world == null) return;

        // 如果上一条还在生成，先真中断，避免并发覆盖 UI
        stopGenerating();

        // 追加玩家消息
        messages.add(new ChatMessage(text, true));
        scrollOffset = 0; // 回到底部，显示最新消息

        // 记录输入历史（去重：与上一条完全相同则不重复入栈）
        if (inputHistory.isEmpty() || !inputHistory.getLast().equals(text)) {
            inputHistory.add(text);
            // 简单上限，避免无限增长
            if (inputHistory.size() > 50) {
                inputHistory.removeFirst();
            }
        }
        historyIndex = -1;
        historyDraft = "";

        World world = client.world;
        BlockPos start = client.player.getBlockPos().add(2, 0, 2);
        String dimensionId = world.getRegistryKey().getValue().toString();

        // 构造历史
        List<String> history = new ArrayList<>();
        for (ChatMessage m : messages) {
            if (m.type != ChatMessage.MessageType.STREAMING) {
                history.add((m.fromPlayer ? "Player: " : "AI: ") + m.text);
            }
        }

        BuildingRequest request = new BuildingRequest(text, start, dimensionId, sessionId, history);

        // 添加 AI thinking 占位消息，并创建流式打印器
        int aiMsgIndex = messages.size();
        messages.add(ChatMessage.thinking());
        scrollOffset = 0;
        AIStreamPrinter printer = new AIStreamPrinter(messages, aiMsgIndex);
        printer.setCharsPerTick(2);
        currentPrinter = printer;
        currentCancelToken = new AICancelToken();
        AICancelToken token = currentCancelToken;

        // 异步调用 AI（不阻塞主线程）——可取消：token + future.cancel(true)
        currentRequestFuture = CompletableFuture.supplyAsync(() -> aiService.generateBuildingPlan(request, token));
        currentRequestFuture.whenComplete((result, err) -> {
            if (token.isCancelled()) return;

            if (err != null) {
                client.execute(() -> {
                    if (currentPrinter == printer) currentPrinter = null;
                    messages.add(new ChatMessage("AI 请求失败: " + err.getMessage(), false, null, ChatMessage.MessageType.ERROR));
                    scrollOffset = 0;
                });
                return;
            }

            if (result == null) {
                // 被取消：不再更新消息（Stop 已经把消息标记为 CANCELLED）
                return;
            }

            // 一次性塞入整段文本，由 tick() 按 charsPerTick 打印；未来真实流式直接多次 appendToken 即可
            printer.appendToken(result.getRawResponse());
            printer.finish();

            client.execute(() -> {
                BuildingBlueprint blueprint = planner.plan(request, result);
                autoBuilder.build(world, blueprint);
            });
        });

        // 清空输入框
        inputBox.clear();
    }

    private void stopGenerating() {
        // 1) 取消 token（AI 调用层主动退出）
        if (currentCancelToken != null) {
            currentCancelToken.cancel();
            currentCancelToken = null;
        }

        // 2) 取消 future（触发线程中断，终止阻塞的 httpClient.send）
        if (currentRequestFuture != null) {
            currentRequestFuture.cancel(true);
            currentRequestFuture = null;
        }

        // 3) 停止打字机 + 将消息标记为“已停止生成”
        if (currentPrinter != null) {
            currentPrinter.cancel();
            currentPrinter = null;
            scrollOffset = 0;
        }
    }

    /**
     * 重置会话（可以在顶部加一个按钮调用）
     */
    public void resetSession() {
        messages.clear();
        scrollOffset = 0;
        inputBox.clear();
        sessionId = java.util.UUID.randomUUID().toString();
    }

    /**
     * 添加 AI 回复消息（带 BuildingSpec）
     * 用于从网络接收到的 BuildingSpec 时调用
     */
    public void addAIMessage(String text, BuildingSpec spec) {
        if (spec != null) {
            messages.add(new ChatMessage(text, false, spec));
        } else {
            messages.add(new ChatMessage(text, false));
        }
        scrollOffset = 0; // 自动滚动到底部
    }

    /**
     * 添加普通消息
     */
    public void addMessage(String text, boolean fromPlayer) {
        messages.add(new ChatMessage(text, fromPlayer));
        scrollOffset = 0; // 自动滚动到底部
    }

}
