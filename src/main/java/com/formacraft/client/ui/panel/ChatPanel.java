package com.formacraft.client.ui.panel;

import com.formacraft.ai.context.BrushContext;
import com.formacraft.ai.context.SelectionContext;
import com.formacraft.ai.prompt.PromptAssembler;
import com.formacraft.ai.prompt.PromptMode;
import com.formacraft.client.component.ClientComponentCatalogState;
import com.formacraft.client.buildcontext.BuildContextResolver;
import com.formacraft.client.network.FormaCraftClientNetworking;
import com.formacraft.client.preview.BuildingPreviewState;
import com.formacraft.client.preview.OutlinePreviewState;
import com.formacraft.client.preview.PromptModeState;
import com.formacraft.client.ui.widget.HudClickSupport;
import com.formacraft.client.ui.widget.MultilineTextInput;
import com.formacraft.client.ui.panel.chat.AIStreamPrinter;
import com.formacraft.client.ui.panel.chat.ChatMessage;
import com.formacraft.client.ui.input.InputRouter;
import com.formacraft.client.ui.text.SelectableTextBlock;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.common.model.request.ReferenceInputExtractor;
import com.formacraft.client.ui.UiTheme;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.config.SettingsConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.MouseInput;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * FormaCraft 主聊天面板（左侧固定栏 + 多行输入 + 可滚动消息区域）
 * 支持动态字体大小调整
 */
public class ChatPanel extends BasePanel {

    private static final FcaLog LOG = FcaLog.of("ChatPanel");

    private final MinecraftClient client = MinecraftClient.getInstance();

    // 已切换为“服务端预览 + 确认后建造”的流程：
    // ChatPanel 只负责发起请求（FormaCraftClientNetworking.sendBuildRequest），不再本地直接规划/建造。

    // ==== 对话状态 ====
    private final List<ChatMessage> messages = new ArrayList<>();
    private String sessionId = java.util.UUID.randomUUID().toString();

    // 多行输入组件
    private MultilineTextInput inputBox;

    // 输入历史（↑/↓）：只在光标位于首/末行时触发，避免与多行光标移动冲突
    private final List<String> inputHistory = new ArrayList<>();
    private int historyIndex = -1;     // -1 表示未浏览历史（编辑当前草稿）
    private String historyDraft = "";  // 进入历史浏览前的草稿

    // 滚动偏移：从底部往上偏移多少像素（平滑滚动，避免按“整条消息”跳页）
    private int scrollOffset = 0;
    private double scrollRemainder = 0.0;
    /** 上一帧计算的最大滚动偏移，供滚轮事件 clamp 使用 */
    private int chatMaxScrollOffset = 0;

    // ========== AI 输出流式打印（token 队列 + typewriter） ==========
    private AIStreamPrinter currentPrinter = null;

    // ========== 服务端请求状态（用于更准确的“思考中/超时/错误”展示） ==========
    private long pendingRequestToken = 0L;
    private int pendingThinkingIndex = -1;
    // 本地等待计时：从发出请求即刻开始，每秒刷新“已等待 N 秒”，不依赖服务端心跳
    private long pendingStartMs = 0L;
    private String pendingPhase = "";
    private static final long SOFT_TIMEOUT_SEC = 15;
    // 复杂复合结构/城市规划可能很慢（deepseek/reasoner 尤其），默认给 10 分钟避免误报超时
    private static final long HARD_TIMEOUT_SEC = 600;

    // 轻量 health check（仅用于本地 localhost 情况下的“更准确提示”）
    private final HttpClient healthHttp = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .build();

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

    /** Chat 侧栏较窄，使用比全局 CONTENT_PADDING 更紧凑的内边距 */
    private static final int CHAT_PADDING_H = 1;
    private static final int CHAT_PADDING_V = 1;
    /** 气泡距内容区左右边缘的水平留白 */
    private static final int CHAT_BUBBLE_INSET = 1;
    /** 气泡内文字左右 padding（各 1px） */
    private static final int CHAT_BUBBLE_TEXT_PAD = 2;
    /** 最新消息距输入区分隔线的底部留白 */
    private static final int CHAT_BOTTOM_INSET = 2;
    /** 相邻消息块之间的垂直间距 */
    private static final int CHAT_MESSAGE_GAP = 2;
    /** 输入框默认可见行数（与 MultilineTextInput 内边距对齐） */
    private static final int CHAT_INPUT_MIN_VISIBLE_LINES = 3;
    private static final int CHAT_INPUT_MAX_VISIBLE_LINES = 8;
    /** MultilineTextInput 上下各 4px，合计 8px */
    private static final int CHAT_INPUT_BOX_VERTICAL_PAD = 8;
    /** UI 侧允许的最大输入量（发送时由 FormaRequestCompactor 再压缩） */
    private static final int CHAT_INPUT_MAX_CHARS = 1_048_576;
    private static final int CHAT_INPUT_MAX_LINES = 10_000;
    /** 聊天区与输入区之间仅留分隔线，不再额外留白 */
    private static final int CHAT_DIVIDER_HEIGHT = 1;

    /** 聊天区布局（与 drawContents / 鼠标事件共用，避免漂移） */
    private record ChatLayout(
            int innerX,
            int innerY,
            int innerW,
            int innerH,
            int chatTop,
            int chatBottom,
            int inputY,
            int inputAreaHeight,
            int selectionHintHeight
    ) {}

    private ChatLayout computeChatLayout() {
        int innerX = panelX + CHAT_PADDING_H;
        int innerY = getContentY() + CHAT_PADDING_V;
        int innerW = Math.max(1, panelWidth - CHAT_PADDING_H * 2);
        int innerH = Math.max(1, getContentHeight() - CHAT_PADDING_V * 2);

        int selectionHintHeight = SelectionContext.hasSelection() ? 10 : 0;
        int inputAreaHeight = getInputHeight(selectionHintHeight);
        int chatBottom = innerY + innerH - inputAreaHeight - CHAT_DIVIDER_HEIGHT;
        int inputY = chatBottom + CHAT_DIVIDER_HEIGHT;

        return new ChatLayout(
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

    /** 输入框与发送按钮布局（draw / mouseClicked 共用，避免点击区域漂移） */
    private record InputBounds(
            int inputBoxX,
            int inputBoxY,
            int inputBoxW,
            int inputBoxH,
            int btnX,
            int btnY,
            int stopY
    ) {}

    private InputBounds computeInputBounds(ChatLayout layout) {
        int innerX = layout.innerX();
        int innerW = layout.innerW();
        int inputY = layout.inputY();
        int inputAreaHeight = layout.inputAreaHeight();
        int selectionHintHeight = layout.selectionHintHeight();

        int btnX = innerX + innerW - SEND_BUTTON_SIZE - 1;
        int btnY = inputY + inputAreaHeight - SEND_BUTTON_SIZE - 1;
        int stopY = btnY - STOP_BUTTON_SIZE - 1;

        int inputBoxX = innerX + 1;
        int inputBoxY = inputY + 1 + selectionHintHeight;
        int inputBoxW = innerW - SEND_BUTTON_SIZE - 3;
        int inputBoxH = inputAreaHeight - 2 - selectionHintHeight;

        return new InputBounds(inputBoxX, inputBoxY, inputBoxW, inputBoxH, btnX, btnY, stopY);
    }

    private static void enableScissor(DrawContext ctx, int x0, int y0, int x1, int y1) {
        if (x1 > x0 && y1 > y0) {
            ctx.enableScissor(x0, y0, x1, y1);
        }
    }

    private static void disableScissor(DrawContext ctx, int x0, int y0, int x1, int y1) {
        if (x1 > x0 && y1 > y0) {
            ctx.disableScissor();
        }
    }

    // 统一控件高度：与 SettingsPanel 输入/按钮一致（16）
    private static final int SEND_BUTTON_SIZE = 16;
    private static final int STOP_BUTTON_SIZE = 16;

    // 原版风格按钮（与 SettingsPanel 保持一致：ButtonWidget 渲染）
    private ButtonWidget sendButton;
    private ButtonWidget stopButton;

    // Prompt 模式：默认 BUILD（不再显示按钮，直接使用默认模式）
    private PromptMode promptMode = PromptMode.BUILD;

    public ChatPanel() {
        // 初始输入框，在第一次 render 时会根据当前面板尺寸重新 setBounds
        this.inputBox = new MultilineTextInput(0, 0, 10, 48);
        // 聊天输入：UI 侧尽量放宽；网络发送时 FormaRequestCompactor 会再压缩
        this.inputBox.setMaxChars(CHAT_INPUT_MAX_CHARS);
        this.inputBox.setMaxLines(CHAT_INPUT_MAX_LINES);
        initWidgets();
    }

    private void initWidgets() {
        if (sendButton != null) return;

        sendButton = ButtonWidget.builder(Text.literal(">"), b -> {
                    sendCurrentMessage();
                    selectable.clearSelection();
                    selectableMsgIndex = -1;
                })
                .dimensions(0, 0, SEND_BUTTON_SIZE, SEND_BUTTON_SIZE)
                .tooltip(Tooltip.of(Text.translatable("formacraft.chat.send.tooltip")))
                .build();

        stopButton = ButtonWidget.builder(Text.literal("■"), b -> {
                    stopGenerating();
                    selectable.clearSelection();
                    selectableMsgIndex = -1;
                })
                .dimensions(0, 0, STOP_BUTTON_SIZE, STOP_BUTTON_SIZE)
                .tooltip(Tooltip.of(Text.translatable("formacraft.chat.stop.tooltip")))
                .build();
    }

    private double getScaledMouseX() {
        return client.mouse.getX() / client.getWindow().getScaleFactor();
    }

    private double getScaledMouseY() {
        return client.mouse.getY() / client.getWindow().getScaleFactor();
    }

    private float getFontScale() {
        // 注意：当前 HUD 文本没有真正缩放（DrawContext 2D 矩阵 API 在 1.21+ 已变化）。
        // 这里的 fontScale 仅用于“行高/布局密度”，并且我们会 clamp，避免 fontSize 调小导致行距小于字体高度而重叠。
        float s = SettingsConfig.INSTANCE.fontSize / 14.0f;
        if (s < 0.75f) s = 0.75f;
        if (s > 1.4f) s = 1.4f;
        return s;
    }

    @Override
    protected void drawContents(DrawContext ctx) {
        ensureLayout();
        // 拖拽更新选区（不依赖额外鼠标事件：利用 InputRouter.leftDown + 每帧更新）
        tickTextSelectionDrag();

        // 每帧推进流式打印（不会阻塞）
        if (currentPrinter != null) {
            currentPrinter.tick();
            if (currentPrinter.isFinished()) {
                currentPrinter = null;
            }
        }

        // 字体“缩放”当前仅体现为行高变化：保证最小行高 >= 字体高度 + 2，避免重叠
        float fontScale = getFontScale();
        int baseFont = client.textRenderer.fontHeight;
        int lineHeight = Math.max(baseFont + 2, (int)((baseFont + 2) * fontScale));

        // 面板内边距
        ChatLayout layout = computeChatLayout();
        int innerX = layout.innerX();
        int innerY = layout.innerY();
        int innerW = layout.innerW();
        int chatAreaBottom = layout.chatBottom();

        // 内容区统一背景 + 聊天消息区略深一层
        drawContentBackground(ctx);
        ctx.fill(innerX, innerY, innerX + innerW, chatAreaBottom, UiTheme.CHAT_MESSAGE_BG);

        // 消息区裁剪：防止气泡/文本画出聊天区（顶部顶到标签栏、底部侵入输入区）
        enableScissor(ctx, innerX, innerY, innerX + innerW, chatAreaBottom);
        try {
            drawMessages(ctx, innerX, innerY, innerW, chatAreaBottom, lineHeight, fontScale);
        } finally {
            disableScissor(ctx, innerX, innerY, innerX + innerW, chatAreaBottom);
        }

        // 绘制输入框背景 + 输入框 + 发送按钮
        drawInputArea(ctx, layout);
        
        // 在输入区域上方绘制分隔线（参考 Quick Settings 样式）
        ctx.fill(innerX, chatAreaBottom, innerX + innerW, chatAreaBottom + 1, UiTheme.DIVIDER_SECTION);
        
        // 注意：发送按钮的 tooltip 应该在 BasePanel 的 drawTooltip 中处理
        // 这里不再绘制，避免覆盖其他按钮的 tooltip
    }

    /**
     * 自下而上绘制消息气泡，支持 scrollOffset 和字体大小
     */
    private void drawMessages(DrawContext ctx, int innerX, int chatTop, int innerW, int chatBottom, int lineHeight, float fontScale) {
        int availableWidth = Math.max(1, innerW - CHAT_BUBBLE_INSET * 2);
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
                    Text.translatable("formacraft.chat.hint"),
                    innerX + CHAT_BUBBLE_INSET,
                    chatBottom - lineHeight - CHAT_BOTTOM_INSET,
                    0x888888,
                    false
            );
            return;
        }

        // 计算总内容高度（像素）与最大滚动偏移
        int totalContentHeight = 0;
        for (int idx = messages.size() - 1; idx >= 0; idx--) {
            ChatMessage msg = messages.get(idx);

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

            int maxWidthPx = Math.max(1, availableWidth - CHAT_BUBBLE_TEXT_PAD);
            List<SelectableTextBlock.WrappedLine> wrapped =
                    SelectableTextBlock.wrap(client.textRenderer, measureText, maxWidthPx);
            int bubbleHeight = wrapped.size() * lineHeight + 2;
            totalContentHeight += bubbleHeight;
            totalContentHeight += msg.hasSpecSummary() ? ((int)(50 * fontScale) + 6) : CHAT_MESSAGE_GAP;
        }

        int visibleHeight = Math.max(1, chatBottom - chatTop - CHAT_BOTTOM_INSET);
        int maxOffset = Math.max(0, totalContentHeight - visibleHeight);
        chatMaxScrollOffset = maxOffset;
        if (scrollOffset > maxOffset) scrollOffset = maxOffset;
        if (scrollOffset < 0) scrollOffset = 0;

        // 自底向上布局：p = 距最新消息底部的累计高度；scrollOffset 平移整个内容区
        // scrollOffset=0 → 贴底显示最新；scrollOffset=max → 顶部对齐最旧消息
        int p = 0;

        for (int idx = messages.size() - 1; idx >= 0; idx--) {
            ChatMessage msg = messages.get(idx);

            // 包装文本（考虑字体大小）
            // wrapLines 使用真实像素宽度；气泡左右各有 4px padding，所以扣掉 8px
            int maxWidthPx = Math.max(1, availableWidth - CHAT_BUBBLE_TEXT_PAD);
            
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
                    SelectableTextBlock.wrap(client.textRenderer, displayText, maxWidthPx);

            int bubbleHeight = wrapped.size() * lineHeight + 2;  // 上下各 1px 内边距
            int blockHeight = bubbleHeight + (msg.hasSpecSummary() ? ((int)(50 * fontScale) + 8) : CHAT_MESSAGE_GAP);
            int bubbleBottom = chatBottom - CHAT_BOTTOM_INSET - p + scrollOffset;
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
                bubbleX = innerX + innerW - availableWidth - CHAT_BUBBLE_INSET;
                bgColor = 0xAA004466;
                textColor = 0xFFCCFFFF;
            } else {
                bubbleX = innerX + CHAT_BUBBLE_INSET;
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
                    // ASCII caret，避免字体缺字
                    ctx.drawText(client.textRenderer, Text.literal("|"), cx, cy, textColor, false);
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
            }

            p += blockHeight;
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
        int cardHeight = (int)(50 * getFontScale());
        ctx.fill(x, y - cardHeight, x + w, y, 0x55333333);

        int ty = y - cardHeight + 4;

        // 建筑类型
        com.formacraft.common.model.build.BuildingType type = spec.getType();
        if (type != null) {
            ctx.drawText(client.textRenderer,
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
            ctx.drawText(client.textRenderer, Text.literal(sizeText), x + 6, ty, 0xDDDDDD, false);
            ty += lineHeight;
        }

        ctx.drawText(client.textRenderer,
                Text.translatable("formacraft.preview.height", spec.getHeight()), x + 6, ty, 0xDDDDDD, false);
    }

    /**
     * 绘制输入区域（多行输入框 + Send 按钮）
     */
    private void drawInputArea(DrawContext ctx, ChatLayout layout) {
        int innerX = layout.innerX();
        int innerW = layout.innerW();
        int inputY = layout.inputY();
        int inputAreaHeight = layout.inputAreaHeight();
        int selectionHintHeight = layout.selectionHintHeight();
        InputBounds bounds = computeInputBounds(layout);

        // 背景（更透明）
        ctx.fill(innerX, inputY, innerX + innerW, inputY + inputAreaHeight, 0x401A1A1A);

        initWidgets();

        boolean generating = currentPrinter != null;

        double mouseX = getScaledMouseX();
        double mouseY = getScaledMouseY();

        sendButton.setPosition(bounds.btnX(), bounds.btnY());
        sendButton.visible = true;
        sendButton.active = true;
        sendButton.render(ctx, (int) mouseX, (int) mouseY, 0.0f);

        stopButton.setPosition(bounds.btnX(), bounds.stopY());
        stopButton.visible = generating;
        stopButton.active = generating;
        if (generating) {
            stopButton.render(ctx, (int) mouseX, (int) mouseY, 0.0f);
        }

        if (selectionHintHeight > 0) {
            String hint = Text.translatable(
                    "formacraft.chat.selection.hint",
                    SelectionContext.sizeX(),
                    SelectionContext.sizeY(),
                    SelectionContext.sizeZ()
            ).getString();
            ctx.drawTextWithShadow(client.textRenderer, Text.literal(hint), bounds.inputBoxX(), inputY + 1, 0xFFAAAAAA);
        }

        inputBox.setBounds(bounds.inputBoxX(), bounds.inputBoxY(), bounds.inputBoxW(), bounds.inputBoxH());
        enableScissor(ctx, bounds.inputBoxX(), bounds.inputBoxY(),
                bounds.inputBoxX() + bounds.inputBoxW(), bounds.inputBoxY() + bounds.inputBoxH());
        try {
            inputBox.render(ctx);
        } finally {
            disableScissor(ctx, bounds.inputBoxX(), bounds.inputBoxY(),
                    bounds.inputBoxX() + bounds.inputBoxW(), bounds.inputBoxY() + bounds.inputBoxH());
        }
    }

    private int getInputHeight(int selectionHintHeight) {
        // 默认至少显示 3 行；超过上限后固定高度，内部滚动由 MultilineTextInput 保证光标可见
        float fontScale = getFontScale();
        int baseFont = client.textRenderer.fontHeight;
        int lineHeight = Math.max(baseFont + 2, (int)((baseFont + 2) * fontScale));

        int lines = Math.max(1, inputBox.getLineCount());
        int visible = Math.max(
                CHAT_INPUT_MIN_VISIBLE_LINES,
                Math.min(CHAT_INPUT_MAX_VISIBLE_LINES, lines)
        );

        // 与 MultilineTextInput.render 一致：maxLinesVisible = (height - 8) / lineHeight
        int inputBoxH = CHAT_INPUT_BOX_VERTICAL_PAD + visible * lineHeight;
        return inputBoxH + 2 + selectionHintHeight;
    }
    
    /**
     * 重写自定义 tooltip 处理，添加发送按钮的 tooltip
     */
    @Override
    protected boolean drawCustomTooltip(DrawContext ctx, double mouseX, double mouseY) {
        initWidgets();

        if (sendButton != null && sendButton.visible && sendButton.isMouseOver(mouseX, mouseY)) {
            drawTooltipCompat(ctx,
                        java.util.Collections.singletonList(Text.translatable("formacraft.chat.send.tooltip")),
                        (int) mouseX, (int) mouseY);
            return true;
        }

        if (stopButton != null && stopButton.visible && stopButton.isMouseOver(mouseX, mouseY)) {
            drawTooltipCompat(ctx,
                        java.util.Collections.singletonList(Text.translatable("formacraft.chat.stop.tooltip")),
                        (int) mouseX, (int) mouseY);
                return true;
            }

        return false;
    }

    // ==========================
    //  输入&滚动事件
    // ==========================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        ensureLayout();
        if (super.mouseClicked(mouseX, mouseY, button)) return true; // 处理顶部 Tab 切换

        if (button != 0) return false;

        initWidgets();

        ChatLayout layout = computeChatLayout();
        InputBounds bounds = computeInputBounds(layout);
        int chatAreaBottom = layout.chatBottom();

        boolean generating = currentPrinter != null;

        Click click = new Click(mouseX, mouseY, new MouseInput(button, 0));
        stopButton.setPosition(bounds.btnX(), bounds.stopY());
        stopButton.visible = generating;
        stopButton.active = generating;
        if (generating && HudClickSupport.click(stopButton, click)) {
            return true;
        }

        sendButton.setPosition(bounds.btnX(), bounds.btnY());
        sendButton.visible = true;
        sendButton.active = true;
        if (HudClickSupport.click(sendButton, click)) {
            return true;
        }

        // 在输出区点击：开始选择 / 或点击空白清除
        if (mouseY >= layout.chatTop() && mouseY < layout.chatBottom() && tryStartSelection(mouseX, mouseY)) {
            // 选中输出文本后，不要抢输入框焦点
            inputBox.setFocused(false);
            return true;
        } else {
            // 点击非输出文本：清除选区
            selectable.clearSelection();
            selectableMsgIndex = -1;
        }

        inputBox.setBounds(bounds.inputBoxX(), bounds.inputBoxY(), bounds.inputBoxW(), bounds.inputBoxH());
        if (inputBox.mouseClicked(mouseX, mouseY)) {
            // 点击输入框即退出历史浏览态（保持当前内容）
            historyIndex = -1;
            selectable.clearSelection();
            selectableMsgIndex = -1;
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
        ensureLayout();
        if (!isMouseOver(mouseX, mouseY)) return;

        ChatLayout layout = computeChatLayout();
        boolean inChatArea = mouseX >= layout.innerX()
                && mouseX <= layout.innerX() + layout.innerW()
                && mouseY >= layout.chatTop()
                && mouseY < layout.chatBottom();

        // 输入框区域：滚动输入内容；聊天区：滚动消息
        if (!inChatArea && inputBox != null && inputBox.isMouseOver(mouseX, mouseY)) {
            inputBox.mouseScrolled(amount);
            return;
        }
        if (!inChatArea) {
            return;
        }

        // 像素级平滑滚动：保留小数余量，触控板/高精滚轮体验更稳定
        // amount > 0 向上看历史；amount < 0 回到底部方向
        final double pxPerWheelUnit = 12.0;
        scrollRemainder += amount * pxPerWheelUnit;

        int deltaPx;
        if (scrollRemainder > 0) {
            deltaPx = (int) Math.floor(scrollRemainder);
        } else {
            deltaPx = (int) Math.ceil(scrollRemainder);
        }

        if (deltaPx != 0) {
            scrollOffset += deltaPx;
            if (scrollOffset < 0) scrollOffset = 0;
            if (scrollOffset > chatMaxScrollOffset) scrollOffset = chatMaxScrollOffset;
            scrollRemainder -= deltaPx;
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
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            historyIndex = -1; // 编辑行为：退出历史浏览态
            inputBox.backspace();
            return;
        }

        // Enter / 小键盘 Enter：发送；Shift+Enter：换行
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
            if (shift) {
                // Shift+Enter：换行
                historyIndex = -1;
                inputBox.insertNewLine();
            } else {
                // Enter：发送
                sendCurrentMessage();
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

    @Override
    public boolean wantsKeyboardInput() {
        // 聊天面板：仅当输入框真正处于焦点时才声明需要键盘（与其它面板语义一致）
        return inputBox != null && inputBox.isFocused();
    }

    // ==========================
    //  核心逻辑：发送消息 & 调 AI
    // ==========================

    private void sendCurrentMessage() {
        String text = inputBox.getText().trim();
        if (text.isEmpty()) return;

        if (client.player == null || client.world == null) return;
        if (client.getNetworkHandler() == null) {
            messages.add(ChatMessage.error(Text.translatable("formacraft.chat.error.not_connected").getString()));
            scrollOffset = 0;
            return;
        }

        // 预览状态下的“位置微调”指令：不走 AI 请求
        if (tryHandlePreviewAdjust(text)) {
            inputBox.clear();
            return;
        }

        // 如果上一条还在生成，先真中断，避免并发覆盖 UI（本地流式）
        stopGenerating();

        // 记录本次使用的模式（用于 Prompt/BuildContext/PatchFilter 统一解析）
        PromptModeState.setLastMode(promptMode);

        // Prompt 拼接（UI 显示 rawInput；发给 AI 的是 finalPrompt）
        String finalPrompt = PromptAssembler.assemble(text, promptMode);
        if (finalPrompt.isEmpty()) return;

        // 追加玩家消息（显示原始输入，避免聊天内容被“系统拼接”污染）
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

        notifyComponentCatalogStatus(text);

        World world = client.world;
        String dimensionId = world.getRegistryKey().getValue().toString();

        // origin（基准点）：统一从 BuildContextResolver 解析（Outline > Selection > Anchor > CursorHit）
        var bc = BuildContextResolver.resolve(promptMode == PromptMode.MODIFY_REGION);
        BlockPos origin = (bc != null && bc.origin != null) ? bc.origin : client.player.getBlockPos().add(2, 0, 2);

        // 构造历史（限制长度，避免 C2S 包超过 32767 字符）
        List<String> history = getStrings();

        // 改为：发送到服务端 → 服务端请求 Orchestrator → 生成预览线框 → 客户端确认后再真正建造
        FormaRequest req = new FormaRequest();
        req.setRequestText(finalPrompt);
        req.setUserMessage(text);
        req.setReferences(ReferenceInputExtractor.extractFromText(text));
        req.setPromptMode(promptMode.name());
        // 因为 PromptAssembler 总是生成 LlmPlan 格式的 prompt，所以默认使用 LlmPlan
        // 用户可以通过 outputFormat 字段覆盖这个行为
        req.setOutputFormat("llmplan");
        req.setPlayerPos(origin);
        // 额外字段：给后端更多“结构化上下文”（避免只从 prompt 文本里解析）
        try {
            if (bc != null && bc.facing != null) {
                req.setFacing(bc.facing.name());
            } else if (client.player != null) {
                req.setFacing(client.player.getHorizontalFacing().name());
            }
        } catch (Throwable t) {
            LOG.debug("enrich build request facing failed", t);
        }
        req.setSelectionMin(SelectionContext.hasSelection() ? SelectionContext.min() : null);
        req.setSelectionMax(SelectionContext.hasSelection() ? SelectionContext.max() : null);
        
        // 笔刷选中区域（如果没有选区，则传递笔刷边界）
        try {
            if (BrushContext.hasBrushSelection() && !SelectionContext.hasSelection()) {
                int[] bounds = BrushContext.getBounds();
                if (bounds != null) {
                    req.setBrushMin(new BlockPos(bounds[0], bounds[1], bounds[2]));
                    req.setBrushMax(new BlockPos(bounds[3], bounds[4], bounds[5]));
                }
            }
        } catch (Throwable t) {
            LOG.debug("enrich build request brush bounds failed", t);
        }

        try {
            // 用于服务端“生成阶段硬裁剪”：禁区/轮廓不再只靠 AI 遵守
            if (bc != null) {
                req.setOutline(bc.outline);
                req.setProtectedZones(bc.protectedZones);
            }
        } catch (Throwable t) {
            LOG.debug("enrich build request outline/zones failed", t);
        }

        // 路径走廊（PathTool）：结构化下发，用于服务端"走廊"硬裁剪（Phase 9）
        try {
            com.formacraft.common.skeleton.PathSkeleton skel =
                    com.formacraft.client.tool.PathTool.INSTANCE.toSkeleton();
            if (skel.isValid()) {
                req.setPathNodes(new java.util.ArrayList<>(skel.nodes));
                req.setPathRadius(skel.corridorRadius);
            }
        } catch (Throwable t) {
            LOG.debug("enrich build request path corridor failed", t);
        }
        req.setSessionId(sessionId);
        req.setChatHistory(history);

        // 关键：把客户端 Settings 中的 LLM 配置随请求发送给服务端/后端。
        // 否则 Python 后端会收到 apiKey/model/provider/baseUrl 都是 null，通常只能走“兜底模板”，导致“无论输入什么都生成得很像”。
        SettingsConfig cfg = SettingsConfig.INSTANCE;
        String apiKey = cfg.apiKey != null ? cfg.apiKey.trim() : "";
        if (!apiKey.isEmpty()) req.setApiKey(apiKey);

        String model = cfg.model != null ? cfg.model.trim() : "";
        if (!model.isEmpty()) req.setModel(model);

        req.setTemperature(cfg.temperature); // always send (server/backend may ignore if unsupported)

        String provider = cfg.llmProvider != null ? cfg.llmProvider.trim() : "";
        if (!provider.isEmpty()) req.setLlmProvider(provider);

        String baseUrl = cfg.llmBaseUrl != null ? cfg.llmBaseUrl.trim() : "";
        if (!baseUrl.isEmpty()) req.setLlmBaseUrl(baseUrl);

        String searchProvider = cfg.searchProvider != null ? cfg.searchProvider.trim() : "";
        if (!searchProvider.isEmpty()) req.setSearchProvider(searchProvider);

        String searchApiKey = cfg.searchApiKey != null ? cfg.searchApiKey.trim() : "";
        if (!searchApiKey.isEmpty()) req.setSearchApiKey(searchApiKey);

        String googleCseCx = cfg.googleCseCx != null ? cfg.googleCseCx.trim() : "";
        if (!googleCseCx.isEmpty()) req.setGoogleCseCx(googleCseCx);

        // 记录 origin，确保 confirm 时与预览一致
        BuildingPreviewState.setPendingOrigin(origin);

        // 添加 AI thinking 占位消息（等服务端返回 ResponseBuildSpecPayload / Error 时会补上 AI 消息）
        pendingRequestToken = System.currentTimeMillis();
        pendingThinkingIndex = messages.size();
        // 给用户可见性：显示本次使用的 provider/model（不暴露 API Key）
        String basePhase = getString(req);
        pendingPhase = basePhase;
        pendingStartMs = System.currentTimeMillis();
        messages.add(ChatMessage.thinking(formatThinkingElapsed(basePhase, 0)));
        scrollOffset = 0;

        FormaCraftClientNetworking.sendBuildRequest(req);

        // 本地等待计时：第 1 秒即开始跳动，不依赖服务端心跳
        startElapsedTicker(pendingRequestToken);

        // 软超时：服务端/AI 可能在生成，15s 不应直接报错（避免误导）
        final long token = pendingRequestToken;
        CompletableFuture.delayedExecutor(SOFT_TIMEOUT_SEC, TimeUnit.SECONDS).execute(() -> {
            // 仅在“本次请求仍在等待”时触发
            if (pendingRequestToken != token) return;
            if (pendingThinkingIndex < 0 || pendingThinkingIndex >= messages.size()) return;
            ChatMessage cur = messages.get(pendingThinkingIndex);
            if (cur == null || cur.type != ChatMessage.MessageType.THINKING) return;

            // 本地 endpoint 时做一次 /health 快速判断，让提示更准确
            String endpoint = SettingsConfig.INSTANCE.orchestratorEndpoint;
            String healthUrl = buildHealthUrlOrNull(endpoint);
            if (healthUrl != null) {
                CompletableFuture.supplyAsync(() -> isHealthy(healthUrl))
                        .thenAccept(healthy -> client.execute(() -> {
                            if (pendingRequestToken != token) return;
                            if (pendingThinkingIndex < 0 || pendingThinkingIndex >= messages.size()) return;
                            ChatMessage cur2 = messages.get(pendingThinkingIndex);
                            if (cur2 == null || cur2.type != ChatMessage.MessageType.THINKING) return;

                            if (healthy) {
                                setPendingPhase(Text.translatable("formacraft.chat.phase.still_generating_healthy").getString());
                            } else {
                                setPendingPhase(Text.translatable("formacraft.chat.phase.still_waiting_backend").getString());
                            }
                        }));
            } else {
                client.execute(() -> {
                    if (pendingRequestToken != token) return;
                    if (pendingThinkingIndex < 0 || pendingThinkingIndex >= messages.size()) return;
                    ChatMessage cur2 = messages.get(pendingThinkingIndex);
                    if (cur2 == null || cur2.type != ChatMessage.MessageType.THINKING) return;
                    setPendingPhase(Text.translatable("formacraft.chat.phase.still_waiting_server").getString());
                });
            }
        });

        // 硬超时：长时间无回包才报错（避免把“生成慢”误判成“后端没跑”）
        CompletableFuture.delayedExecutor(HARD_TIMEOUT_SEC, TimeUnit.SECONDS).execute(() -> client.execute(() -> {
            if (pendingRequestToken != token) return;
            if (pendingThinkingIndex < 0 || pendingThinkingIndex >= messages.size()) return;
            ChatMessage cur = messages.get(pendingThinkingIndex);
            if (cur == null || cur.type != ChatMessage.MessageType.THINKING) return;
            messages.set(pendingThinkingIndex, ChatMessage.error(
                    Text.translatable("formacraft.chat.error.timeout", HARD_TIMEOUT_SEC).getString()
            ));
            scrollOffset = 0;
        }));

        // 清空输入框
        inputBox.clear();
    }

    private @NotNull List<String> getStrings() {
        List<String> history = new ArrayList<>();
        int histFrom = Math.max(0, messages.size() - 8);
        for (int i = histFrom; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (m.type != ChatMessage.MessageType.STREAMING) {
                String line = (m.fromPlayer ? "Player: " : "AI: ") + m.text;
                if (line.length() > 400) {
                    line = line.substring(0, 400) + "...";
                }
                history.add(line);
            }
        }
        return history;
    }

    /**
     * 建造前检查构件目录是否就绪：触发同步、磁盘回填，并在必要时提示玩家。
     */
    private void notifyComponentCatalogStatus(String userText) {
        ClientComponentCatalogState.hydrateFromDiskIfEmpty();

        if (ClientComponentCatalogState.isSyncPending() && !ClientComponentCatalogState.isSyncComplete()) {
            FormaCraftClientNetworking.sendComponentCatalogRequest();
            messages.add(ChatMessage.system(
                    Text.translatable("formacraft.chat.catalog.sync_pending").getString()));
            scrollOffset = 0;
            return;
        }

        if (!ClientComponentCatalogState.hasRegisteredComponents() && mightReferencePlayerComponents(userText)) {
            messages.add(ChatMessage.system(
                    Text.translatable("formacraft.chat.catalog.no_components").getString()));
            scrollOffset = 0;
        }
    }

    private static boolean mightReferencePlayerComponents(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("构件")
                || lower.contains("component")
                || lower.contains("我的门")
                || lower.contains("我的窗")
                || lower.contains("player component")
                || lower.contains("custom component");
    }

    private static @NotNull String getString(FormaRequest req) {
        String providerHint = (req.getLlmProvider() == null || req.getLlmProvider().isBlank()) ? "auto" : req.getLlmProvider();
        String modelHint = (req.getModel() == null || req.getModel().isBlank()) ? "auto" : req.getModel();
        String baseHint = (req.getLlmBaseUrl() == null || req.getLlmBaseUrl().isBlank()) ? "" : (" @ " + req.getLlmBaseUrl());
        String warn = "";
        if ((req.getApiKey() == null || req.getApiKey().isBlank())
                && (providerHint.equalsIgnoreCase("openai")
                || providerHint.equalsIgnoreCase("openai_compat")
                || providerHint.equalsIgnoreCase("deepseek"))) {
            warn = Text.translatable("formacraft.chat.request.no_api_key_warn").getString();
        }
        return Text.translatable("formacraft.chat.request.sent", providerHint, modelHint, baseHint, warn).getString();
    }

    private static String formatThinkingElapsed(String phase, long seconds) {
        String p = (phase == null || phase.isBlank())
                ? Text.translatable("formacraft.chat.phase.generating_default").getString()
                : phase.trim();
        return Text.translatable("formacraft.chat.thinking.elapsed", p, seconds).getString();
    }

    private boolean tryHandlePreviewAdjust(String text) {
        if (!(BuildingPreviewState.isActive() || OutlinePreviewState.active) || text == null) {
            return false;
        }
        PreviewAdjustCommand cmd = PreviewAdjustCommand.parse(text, client.player != null ? client.player.getHorizontalFacing() : null);
        if (cmd == null) {
            return false;
        }
        messages.add(new ChatMessage(text, true));
        scrollOffset = 0;

        if (inputHistory.isEmpty() || !inputHistory.getLast().equals(text)) {
            inputHistory.add(text);
            if (inputHistory.size() > 50) {
                inputHistory.removeFirst();
            }
        }
        historyIndex = -1;
        historyDraft = "";

        messages.add(ChatMessage.system(cmd.hint));
        scrollOffset = 0;
        FormaCraftClientNetworking.sendPreviewAdjust(cmd.dx, cmd.dy, cmd.dz);
        return true;
    }

    private record PreviewAdjustCommand(int dx, int dy, int dz, String hint) {
            private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d{1,3})");

        private static PreviewAdjustCommand parse(String raw, Direction facing) {
                String text = raw.trim();
                if (text.isEmpty() || text.startsWith("/")) {
                    return null;
                }
                String lower = text.toLowerCase(Locale.ROOT);

                int step = parseStep(lower);
                Direction dir = parseAbsoluteDirection(lower);
                if (dir == null) {
                    dir = parseRelativeDirection(lower, facing);
                }

                int dx = 0;
                int dy = 0;
                int dz = 0;

                if (dir != null) {
                    dx = dir.getOffsetX() * step;
                    dz = dir.getOffsetZ() * step;
                }
                if (containsAny(lower, "上移", "往上", "向上", "上挪", "抬高", "升高", "往高", "向高", "up", "raise")) {
                    dy = step;
                } else if (containsAny(lower, "下移", "往下", "向下", "下挪", "降低", "下降", "down", "lower")) {
                    dy = -step;
                }

                if (dx == 0 && dy == 0 && dz == 0) {
                    return null;
                }
                String hint = Text.translatable("formacraft.chat.preview.adjust.hint", dx, dy, dz).getString();
                return new PreviewAdjustCommand(dx, dy, dz, hint);
            }

            private static int parseStep(String text) {
                Matcher m = NUMBER_PATTERN.matcher(text);
                if (m.find()) {
                    try {
                        int v = Integer.parseInt(m.group(1));
                        return Math.max(1, Math.min(32, v));
                    } catch (NumberFormatException e) {
                        LOG.debug("parseStep number failed text={}", text);
                    }
                }
                if (text.contains("多一点") || text.contains("再多") || text.contains("再往")) {
                    return 2;
                }
                return 1;
            }

            private static Direction parseAbsoluteDirection(String text) {
                if (containsAny(text, "向北", "往北", "北移", "north")) return Direction.NORTH;
                if (containsAny(text, "向南", "往南", "南移", "south")) return Direction.SOUTH;
                if (containsAny(text, "向东", "往东", "东移", "east")) return Direction.EAST;
                if (containsAny(text, "向西", "往西", "西移", "west")) return Direction.WEST;
                return null;
            }

            private static Direction parseRelativeDirection(String text, Direction facing) {
                if (facing == null) return null;
                if (containsAny(text, "往前", "向前", "前移", "前挪", "forward")) {
                    return facing;
                }
                if (containsAny(text, "往后", "向后", "后移", "后退", "back")) {
                    return facing.getOpposite();
                }
                if (containsAny(text, "往左", "向左", "左移", "left")) {
                    return facing.rotateYCounterclockwise();
                }
                if (containsAny(text, "往右", "向右", "右移", "right")) {
                    return facing.rotateYClockwise();
                }
                return null;
            }

            private static boolean containsAny(String text, String... keys) {
                for (String key : keys) {
                    if (text.contains(key)) return true;
                }
                return false;
            }
        }

    private static String buildHealthUrlOrNull(String endpoint) {
        if (endpoint == null) return null;
        String v = endpoint.trim();
        if (v.isEmpty()) return null;
        if (!v.startsWith("http://") && !v.startsWith("https://")) v = "http://" + v;
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        try {
            URI u = new URI(v);
            String host = u.getHost();
            if (host == null) return null;
            host = host.toLowerCase();
            boolean local = host.equals("localhost") || host.equals("127.0.0.1");
            if (!local) return null;
        } catch (Exception e) {
            return null;
        }
        return v + "/health";
    }

    private boolean isHealthy(String healthUrl) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(healthUrl))
                    .timeout(Duration.ofSeconds(2))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = healthHttp.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() >= 200 && resp.statusCode() < 300;
        } catch (Exception e) {
            LOG.debug("backend health check failed url={}", healthUrl, e);
            return false;
        }
    }

    private void stopGenerating() {
        // 停止打字机 + 将消息标记为“已停止生成”
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

    /** 新建对话：先中断生成，再清空会话。 */
    public void startNewConversation() {
        stopGenerating();
        resetSession();
    }


    /**
     * 添加 AI 回复消息（带 BuildingSpec）
     * 用于从网络接收到的 BuildingSpec 时调用
     */
    public void addAIMessage(String text, BuildingSpec spec) {
        // 若上一条是“thinking”，先移除，避免残留占位
        while (!messages.isEmpty() && messages.getLast().type == ChatMessage.MessageType.THINKING) {
            messages.removeLast();
        }
        pendingThinkingIndex = -1;
        pendingRequestToken = 0L;
        if (spec != null) {
            messages.add(new ChatMessage(text, false, spec));
        } else {
            messages.add(new ChatMessage(text, false));
        }
        scrollOffset = 0; // 自动滚动到底部
    }

    public void addAIError(String text) {
        while (!messages.isEmpty() && messages.getLast().type == ChatMessage.MessageType.THINKING) {
            messages.removeLast();
        }
        pendingThinkingIndex = -1;
        pendingRequestToken = 0L;
        messages.add(ChatMessage.error(text));
        scrollOffset = 0;
    }

    /**
     * 更新“等待/生成中”的状态文本（不结束请求）。
     * - 如果当前有 THINKING 占位，则直接替换那条消息的文案
     * - 否则新增一条 SYSTEM 信息（兜底）
     */
    public void addAIStatus(String text) {
        String t = (text == null) ? "" : text.trim();
        if (t.isEmpty()) return;

        // 某些链路（Composite/City 预览）不会下发 ResponseBuildSpecPayload，
        // 只会给出“preview ready”提示；若不在此处结束 pending，会在 120s 误报超时。
        String tl = t.toLowerCase();
        boolean isTerminal =
                tl.contains("preview ready") ||
                tl.contains("预览已就绪") ||
                tl.contains("预览就绪") ||
                tl.contains("预览准备完成") ||
                tl.contains("已准备好预览") ||
                tl.contains("本次生成");

        // 复合/城市预览：弹出简单确认 UI（两按钮），避免用户记命令
        boolean isCompositeOrCityPreviewReady =
                tl.contains("composite structure preview ready") ||
                tl.contains("updated city preview ready") ||
                (tl.contains("city") && tl.contains("preview ready"));
        if (isTerminal && isCompositeOrCityPreviewReady) {
            // 避免覆盖单体 BUILD 的 BuildConfirmPanel（那条会带 BuildingSpec）
            if (!BuildConfirmPanel.INSTANCE.isVisible()) {
                BuildConfirmPanel.INSTANCE.showPreviewActions();
            }
        }

        if (pendingThinkingIndex >= 0 && pendingThinkingIndex < messages.size()) {
            ChatMessage cur = messages.get(pendingThinkingIndex);
            if (cur != null && cur.type == ChatMessage.MessageType.THINKING) {
                if (isTerminal) {
                    // 结束本次请求
                    messages.set(pendingThinkingIndex, new ChatMessage(t, false));
                    pendingThinkingIndex = -1;
                    pendingRequestToken = 0L;
                    scrollOffset = 0;
                } else {
                    // 只更新“阶段文案”，等待秒数交给本地 ticker，避免重复“已等待”
                    setPendingPhase(t);
                }
                return;
            }
        }

        // fallback：如果没有 pending thinking，就作为系统消息插入
        messages.add(new ChatMessage(t, false));
        if (isTerminal) {
            pendingThinkingIndex = -1;
            pendingRequestToken = 0L;
        }
        scrollOffset = 0;
    }

    /**
     * 更新等待阶段文案（会剥离对方自带的“（已等待 N 秒）”后缀，秒数由本地 ticker 统一渲染），
     * 并立即刷新一次占位消息。
     */
    private void setPendingPhase(String phase) {
        if (phase == null) return;
        this.pendingPhase = phase.trim();
        renderPendingElapsed();
    }

    /** 把当前占位消息刷新为 “阶段文案（已等待 N 秒）”。不改动 scrollOffset，避免每秒把视图拉到底。 */
    private void renderPendingElapsed() {
        if (pendingRequestToken == 0L) return;
        if (pendingThinkingIndex < 0 || pendingThinkingIndex >= messages.size()) return;
        ChatMessage cur = messages.get(pendingThinkingIndex);
        if (cur == null || cur.type != ChatMessage.MessageType.THINKING) return;
        long sec = Math.max(0, (System.currentTimeMillis() - pendingStartMs) / 1000);
        messages.set(pendingThinkingIndex, ChatMessage.thinking(formatThinkingElapsed(pendingPhase, sec)));
    }

    /** 本地每秒计时；以 token 为准，请求一旦结束/被替换即自动停止。 */
    private void startElapsedTicker(long token) {
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (pendingRequestToken != token) return;
                client.execute(() -> {
                    if (pendingRequestToken != token) return;
                    renderPendingElapsed();
                });
                CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS).execute(this);
            }
        };
        CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS).execute(tick);
    }
}
