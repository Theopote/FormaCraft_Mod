package com.formacraft.client.ui.panel;

import com.formacraft.ai.AIResult;
import com.formacraft.ai.AIService;
import com.formacraft.ai.AIServiceManager;
import com.formacraft.ai.BuildingRequest;
import com.formacraft.client.ui.widget.MultilineTextInput;
import com.formacraft.client.ui.panel.chat.ChatMessage;
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

    // 滚动偏移：从底部往上偏移多少条消息
    private int scrollOffset = 0;

    // ========== AI 输出流式打印 ==========
    private boolean aiThinking = false;         // 是否在等待 AI 回复
    private String thinkingAnimation = "正在思考.";
    private int thinkingTick = 0;

    // 当前正在流式打印的文本
    private String streamingTarget = null;
    private StringBuilder streamingBuffer = new StringBuilder();
    private int streamingIndex = 0;

    // 对于结构化消息（如 BuildingSpec）
    private BuildingSpec streamingSpec = null;

    // Padding（减小边距）
    private static final int PADDING = 2;

    public ChatPanel() {
        // 初始输入框，在第一次 render 时会根据当前面板尺寸重新 setBounds
        this.inputBox = new MultilineTextInput(0, 0, 10, 48);
    }

    @Override
    protected void drawContents(DrawContext ctx) {
        // 计算字体缩放
        float fontScale = SettingsConfig.INSTANCE.fontSize / 10.0f;
        int baseFont = client.textRenderer.fontHeight;          // 默认字体高度
        int lineHeight = (int)(baseFont * fontScale + 3);       // 行距

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

        // 绘制聊天区域背景（更透明）
        ctx.fill(innerX, innerY, innerX + innerW, chatAreaBottom, 0x551A1A1A);

        // 绘制消息（自下而上）
        drawMessages(ctx, innerX, innerY, innerW, chatAreaBottom, lineHeight, fontScale);

        // 渲染"正在思考…"动画
        if (aiThinking && streamingTarget == null) {
            int animIndex = (thinkingTick / 10) % 3;
            switch (animIndex) {
                case 0 -> thinkingAnimation = "正在思考.";
                case 1 -> thinkingAnimation = "正在思考..";
                case 2 -> thinkingAnimation = "正在思考...";
            }
            thinkingTick++;

            int thinkingY = chatAreaBottom - lineHeight - 4;
            ctx.drawText(client.textRenderer, 
                    Text.literal(thinkingAnimation),
                    innerX + 4,
                    thinkingY,
                    0xAAAAAA, false);
        }

        // 绘制输入框背景 + 输入框 + 发送按钮
        int inputY = chatAreaBottom + 6;
        drawInputArea(ctx, innerX, inputY, innerW, inputAreaHeight);
        
        // 在输入区域上方绘制分隔线（参考 Quick Settings 样式）
        ctx.fill(innerX, chatAreaBottom, innerX + innerW, chatAreaBottom + 1, 0x66FFFFFF);
    }

    /**
     * 自下而上绘制消息气泡，支持 scrollOffset 和字体大小
     */
    private void drawMessages(DrawContext ctx, int innerX, int chatTop, int innerW, int chatBottom, int lineHeight, float fontScale) {
        int availableWidth = innerW - 20;

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
            int textWidth = (int)(availableWidth / fontScale);
            
            // 流式消息添加光标符号
            String displayText = msg.text;
            if (msg.type == ChatMessage.MessageType.STREAMING) {
                displayText = msg.text + "▍";
            }
            
            List<net.minecraft.text.OrderedText> wrapped =
                    client.textRenderer.wrapLines(Text.literal(displayText), textWidth);

            int bubbleHeight = wrapped.size() * lineHeight + 8;
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

            // 文本
            int textY = bubbleTop + 4;
            for (net.minecraft.text.OrderedText line : wrapped) {
                ctx.drawText(client.textRenderer, line, bubbleX + 4, textY, textColor, false);
                textY += lineHeight;
            }

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
        ctx.fill(innerX, inputY, innerX + innerW, inputY + inputAreaHeight, 0x881A1A1A);

        int btnWidth = 56;
        int btnHeight = 20;
        int btnX = innerX + innerW - btnWidth - 4;
        int btnY = inputY + inputAreaHeight - btnHeight - 4;

        // 检查鼠标是否悬停在按钮上
        double mouseX = client.mouse.getX() * client.getWindow().getScaledWidth() / client.getWindow().getWidth();
        double mouseY = client.mouse.getY() * client.getWindow().getScaledHeight() / client.getWindow().getHeight();
        boolean hovered = mouseX >= btnX && mouseX <= btnX + btnWidth && 
                         mouseY >= btnY && mouseY <= btnY + btnHeight;
        
        // 使用 Minecraft 风格的按钮
        drawMinecraftButton(ctx, btnX, btnY, btnWidth, btnHeight, 
                           Text.translatable("formacraft.chat.send"), hovered);

        // 输入框区域（根据字体大小调整高度）
        int inputBoxX = innerX + 4;
        int inputBoxY = inputY + 4;
        int inputBoxW = innerW - btnWidth - 12;
        int inputBoxH = inputAreaHeight - 8;

        inputBox.setBounds(inputBoxX, inputBoxY, inputBoxW, inputBoxH);
        inputBox.render(ctx);
    }

    /**
     * 获取输入框高度（用于布局计算）
     */
    private int getInputHeight() {
        float fontScale = SettingsConfig.INSTANCE.fontSize / 10.0f;
        return (int)(52 * fontScale);
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
        
        float fontScale = SettingsConfig.INSTANCE.fontSize / 10.0f;
        int inputAreaHeight = (int)(52 * fontScale);
        int chatAreaBottom = innerY + innerH - inputAreaHeight - 4;
        int inputY = chatAreaBottom + 6;

        int btnWidth = 56;
        int btnHeight = 20;
        int btnX = innerX + innerW - btnWidth - 4;
        int btnY = inputY + inputAreaHeight - btnHeight - 4;

        // 点击发送按钮
        if (mouseX >= btnX && mouseX <= btnX + btnWidth &&
                mouseY >= btnY && mouseY <= btnY + btnHeight) {
            sendCurrentMessage();
            return true;
        }

        // 点击输入框区域，设置焦点
        int inputBoxX = innerX + 4;
        int inputBoxY = inputY + 4;
        int inputBoxW = innerW - btnWidth - 12;
        int inputBoxH = inputAreaHeight - 8;
        
        if (mouseX >= inputBoxX && mouseX <= inputBoxX + inputBoxW &&
            mouseY >= inputBoxY && mouseY <= inputBoxY + inputBoxH) {
            inputBox.setFocused(true);
            // TODO: 可以在这里实现点击位置设置光标（需要计算点击位置对应的行列）
            return true;
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
     * - Enter：发送消息
     * - Shift+Enter：换行
     * - Backspace：删除一个字符
     * - 方向键、Home/End：光标移动
     * - Ctrl+C/V/X：复制粘贴剪切
     */
    @Override
    public void keyPressed(int keyCode, int scanCode, int modifiers) {
        // 设置输入框焦点
        inputBox.setFocused(true);

        // Backspace
        if (keyCode == 259) {
            inputBox.backspace();
            return;
        }

        // Enter 键（KeyCode 257 是 GLFW_KEY_ENTER）
        if (keyCode == GLFW.GLFW_KEY_ENTER) {
            // 检查 Shift 键
            boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
            if (shift) {
                // Shift+Enter：换行
                inputBox.insertNewLine();
            } else {
                // Enter：发送消息
                sendCurrentMessage();
            }
            return;
        }

        // 其他按键交给输入框处理（方向键、Ctrl+C/V/X、Home/End 等）
        inputBox.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void charTyped(char chr) {
        // 过滤控制字符
        if (chr == '\r' || chr == '\n') return;
        inputBox.charTyped(chr);
    }

    // ==========================
    //  核心逻辑：发送消息 & 调 AI
    // ==========================

    private void sendCurrentMessage() {
        String text = inputBox.getText().trim();
        if (text.isEmpty()) return;

        if (client.player == null || client.world == null) return;

        // 追加玩家消息
        messages.add(new ChatMessage(text, true));
        scrollOffset = 0; // 回到底部，显示最新消息

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

        // 启动思考动画
        startThinkingAnimation();

        // 异步调用 AI（不阻塞主线程）
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                AIResult result = aiService.generateBuildingPlan(request);

                // 在主线程中启动流式打印
                client.execute(() -> {
                    // 从 result 中提取 BuildingSpec（如果存在）
                    BuildingSpec spec = null;
                    if (result.getStructureData() != null) {
                        // TODO: 从 StructureData 转换为 BuildingSpec（如果需要）
                        // 这里暂时为 null，后续可以根据实际需求转换
                    }
                    
                    startStreamingAIMessage(result.getRawResponse(), null);

                    // 规划 & 自动建造（延续你现有流程）
                    BuildingBlueprint blueprint = planner.plan(request, result);
                    autoBuilder.build(world, blueprint);
                });
            } catch (Exception e) {
                client.execute(() -> {
                    aiThinking = false;
                    messages.add(new ChatMessage("AI 请求失败: " + e.getMessage(), false, null, ChatMessage.MessageType.ERROR));
                    scrollOffset = 0;
                });
            }
        });

        // 清空输入框
        inputBox.clear();
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

    // ==========================
    //  思考动画 & 流式打印
    // ==========================

    /**
     * 启动思考动画
     */
    private void startThinkingAnimation() {
        aiThinking = true;
        thinkingAnimation = "正在思考.";
        thinkingTick = 0;
    }

    /**
     * 启动流式打印 AI 消息
     */
    private void startStreamingAIMessage(String text, BuildingSpec spec) {
        this.aiThinking = false;
        this.streamingTarget = text != null ? text : "";
        this.streamingBuffer = new StringBuilder();
        this.streamingIndex = 0;
        this.streamingSpec = spec;

        // 开启流式打印
        tickStreaming();
    }

    /**
     * 流式打印主逻辑（每 tick 执行）
     */
    private void tickStreaming() {
        // 每 tick 打印 1～3 个字符（可改）
        if (streamingTarget != null) {
            int step = 2; // 打印速度（每 tick 2 个字符）
            for (int i = 0; i < step && streamingIndex < streamingTarget.length(); i++) {
                streamingBuffer.append(streamingTarget.charAt(streamingIndex));
                streamingIndex++;
            }

            // 当 streamingBuffer 有内容 → 动态显示
            if (!streamingBuffer.isEmpty()) {
                // 最后一个消息是否是 AI 的 partial？
                if (!messages.isEmpty() && !messages.getLast().fromPlayer
                        && messages.getLast().type == ChatMessage.MessageType.STREAMING) {
                    // 更新现有的流式消息
                    messages.set(messages.size() - 1,
                        ChatMessage.streaming(streamingBuffer.toString(), streamingSpec)
                    );
                } else {
                    // 创建新的流式消息
                    messages.add(ChatMessage.streaming(streamingBuffer.toString(), streamingSpec));
                    scrollOffset = 0; // 滚动到底部
                }
            }

            if (streamingIndex >= streamingTarget.length()) {
                // 完成流式打印
                streamingTarget = null;
                BuildingSpec finalSpec = streamingSpec;
                streamingSpec = null;

                // 将 STREAMING 类型转为 TEXT 或 SPEC
                if (!messages.isEmpty()) {
                    ChatMessage last = messages.getLast();
                    ChatMessage finalMsg;

                    if (finalSpec != null) {
                        finalMsg = new ChatMessage(last.text, false, finalSpec, ChatMessage.MessageType.SPEC);
                    } else {
                        finalMsg = new ChatMessage(last.text, false, null, ChatMessage.MessageType.TEXT);
                    }

                    messages.set(messages.size() - 1, finalMsg);
                }
                return;
            }

            // 下一 tick 继续打印
            client.execute(this::tickStreaming);
        }
    }
}
