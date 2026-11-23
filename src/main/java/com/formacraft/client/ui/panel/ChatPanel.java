package com.formacraft.client.ui.panel;

import com.formacraft.client.ai.prompt.PromptMode;
import com.formacraft.client.ui.widget.MultilineTextInput;
import com.formacraft.client.ui.panel.chat.AIStreamPrinter;
import com.formacraft.client.ui.panel.chat.ChatMessage;
import com.formacraft.client.ui.text.SelectableTextBlock;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.client.ui.UiTheme;
import com.formacraft.common.logging.FcaLog;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.net.http.HttpClient;
import java.time.Duration;

public class ChatPanel extends BasePanel {
    public boolean superMouseClicked(double x, double y, int button) { return super.mouseClicked(x, y, button); }
    public boolean superMouseReleased(double x, double y, int button) { return super.mouseReleased(x, y, button); }

    public static final FcaLog LOG = FcaLog.of("ChatPanel");

    public final MinecraftClient client = MinecraftClient.getInstance();

    // 已切换为“服务端预览 + 确认后建造”的流程：
    // ChatPanel 只负责发起请求（FormaCraftClientNetworking.sendBuildRequest），不再本地直接规划/建造。

    // ==== 对话状态 ====
    public final List<ChatMessage> messages = new ArrayList<>();
    public String sessionId = java.util.UUID.randomUUID().toString();

    // 多行输入组件
    public MultilineTextInput inputBox;

    // 输入历史（↑/↓）：只在光标位于首/末行时触发，避免与多行光标移动冲突
    public final List<String> inputHistory = new ArrayList<>();
    public int historyIndex = -1;     // -1 表示未浏览历史（编辑当前草稿）
    public String historyDraft = "";  // 进入历史浏览前的草稿

    // 滚动偏移：从底部往上偏移多少像素（平滑滚动，避免按“整条消息”跳页）
    public int scrollOffset = 0;
    public double scrollRemainder = 0.0;

    public int chatMaxScrollOffset = 0;

    // ========== AI 输出流式打印（token 队列 + typewriter） ==========
    public AIStreamPrinter currentPrinter = null;

    // ========== 服务端请求状态（用于更准确的“思考中/超时/错误”展示） ==========
    public long pendingRequestToken = 0L;
    public int pendingThinkingIndex = -1;
    // 本地等待计时：从发出请求即刻开始，每秒刷新“已等待 N 秒”，不依赖服务端心跳
    public long pendingStartMs = 0L;
    public String pendingPhase = "";
    public static final long SOFT_TIMEOUT_SEC = 15;
    // 复杂复合结构/城市规划可能很慢（deepseek/reasoner 尤其），默认给 10 分钟避免误报超时
    public static final long HARD_TIMEOUT_SEC = 600;

    // 轻量 health check（仅用于本地 localhost 情况下的“更准确提示”）
    public final HttpClient healthHttp = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .build();

    // ========== 输出区文字选择（拖拽选区 + Ctrl+C） ==========
    public final SelectableTextBlock selectable = new SelectableTextBlock(client);
    public int selectableMsgIndex = -1;
    public boolean selectingText = false;

    public static class RenderedMessage {
        public int msgIndex;
        public int bubbleX, bubbleTop, bubbleBottom, bubbleW;
        public int textX, textY, maxWidthPx, lineHeight;
        public String displayText;
        public boolean selectable;
    }

    public final List<RenderedMessage> renderedMessages = new ArrayList<>();

    public static final int CHAT_PADDING_H = 1;
    public static final int CHAT_PADDING_V = 1;

    public static final int CHAT_BUBBLE_INSET = 1;

    public static final int CHAT_BUBBLE_TEXT_PAD = 2;

    public static final int CHAT_BOTTOM_INSET = 2;

    public static final int CHAT_MESSAGE_GAP = 2;

    public static final int CHAT_INPUT_MIN_VISIBLE_LINES = 3;
    public static final int CHAT_INPUT_MAX_VISIBLE_LINES = 8;

    public static final int CHAT_INPUT_BOX_VERTICAL_PAD = 8;

    public static final int CHAT_INPUT_MAX_CHARS = 1_048_576;
    public static final int CHAT_INPUT_MAX_LINES = 10_000;

    public static final int CHAT_DIVIDER_HEIGHT = 1;

    public record ChatLayout(
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

    public ChatLayout computeChatLayout() { return com.formacraft.client.ui.panel.chat.ChatLayoutSupport.computeChatLayout(this); }

    public record InputBounds(
            int inputBoxX,
            int inputBoxY,
            int inputBoxW,
            int inputBoxH,
            int btnX,
            int btnY,
            int stopY
    ) {}

    public InputBounds computeInputBounds(ChatLayout layout) { return com.formacraft.client.ui.panel.chat.ChatLayoutSupport.computeInputBounds(this, layout); }

    public static void enableScissor(DrawContext ctx, int x0, int y0, int x1, int y1) { com.formacraft.client.ui.panel.chat.ChatLayoutSupport.enableScissor(ctx, x0, y0, x1, y1); }

    public static void disableScissor(DrawContext ctx, int x0, int y0, int x1, int y1) { com.formacraft.client.ui.panel.chat.ChatLayoutSupport.disableScissor(ctx, x0, y0, x1, y1); }

    // 统一控件高度：与 SettingsPanel 输入/按钮一致（16）
    public static final int SEND_BUTTON_SIZE = 16;
    public static final int STOP_BUTTON_SIZE = 16;

    // 原版风格按钮（与 SettingsPanel 保持一致：ButtonWidget 渲染）
    public ButtonWidget sendButton;
    public ButtonWidget stopButton;
    public ButtonWidget referenceButton;
    public String referenceImageUrl;
    public byte[] referenceImageFile;
    public boolean readingReferenceImage;

    // Prompt 模式：默认 BUILD（不再显示按钮，直接使用默认模式）
    public PromptMode promptMode = PromptMode.BUILD;

    public ChatPanel() {
        // 初始输入框，在第一次 render 时会根据当前面板尺寸重新 setBounds
        this.inputBox = new MultilineTextInput(0, 0, 10, 48);
        // 聊天输入：UI 侧尽量放宽；网络发送时 FormaRequestCompactor 会再压缩
        this.inputBox.setMaxChars(CHAT_INPUT_MAX_CHARS);
        this.inputBox.setMaxLines(CHAT_INPUT_MAX_LINES);
        initWidgets();
    }

    public void initWidgets() { com.formacraft.client.ui.panel.chat.ChatInputSupport.initWidgets(this); }

    public void toggleReferenceImage() { com.formacraft.client.ui.panel.chat.ChatInputSupport.toggleReferenceImage(this); }

    public boolean hasReferenceImage() { return com.formacraft.client.ui.panel.chat.ChatInputSupport.hasReferenceImage(this); }

    public void chooseReferenceImageFile() { com.formacraft.client.ui.panel.chat.ChatInputSupport.chooseReferenceImageFile(this); }

    private static boolean isReferenceImageUrl(String value) { return com.formacraft.client.ui.panel.chat.ChatInputSupport.isReferenceImageUrl(value); }

    public double getScaledMouseX() { return com.formacraft.client.ui.panel.chat.ChatLayoutSupport.getScaledMouseX(this); }

    public double getScaledMouseY() { return com.formacraft.client.ui.panel.chat.ChatLayoutSupport.getScaledMouseY(this); }

    public static final int CHAT_SPEC_SUMMARY_HEIGHT = 50;

    public static int chatLineHeight() { return com.formacraft.client.ui.panel.chat.ChatLayoutSupport.chatLineHeight(); }

    @Override
    protected void drawContents(DrawContext ctx) {
        ensureLayout();
        // 拖拽更新选区（不依赖额外鼠标事件：利用 InputRouter.leftDown + 每帧更新）
        tickTextSelectionDrag();
        tickInputSelectionDrag();

        // 每帧推进流式打印（不会阻塞）
        if (currentPrinter != null) {
            currentPrinter.tick();
            if (currentPrinter.isFinished()) {
                currentPrinter = null;
            }
        }

        int lineHeight = chatLineHeight();

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
            drawMessages(ctx, innerX, innerY, innerW, chatAreaBottom, lineHeight);
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

    private void drawMessages(DrawContext ctx, int innerX, int chatTop, int innerW, int chatBottom, int lineHeight) { com.formacraft.client.ui.panel.chat.ChatMessageList.drawMessages(this, ctx, innerX, chatTop, innerW, chatBottom, lineHeight); }

    private void tickTextSelectionDrag() { com.formacraft.client.ui.panel.chat.ChatMessageList.tickTextSelectionDrag(this); }

    private void tickInputSelectionDrag() { com.formacraft.client.ui.panel.chat.ChatMessageList.tickInputSelectionDrag(this); }

    private void drawSpecSummary(DrawContext ctx, BuildingSpec spec, int x, int y, int w, int lineHeight) { com.formacraft.client.ui.panel.chat.ChatMessageList.drawSpecSummary(this, ctx, spec, x, y, w, lineHeight); }

    private void drawInputArea(DrawContext ctx, ChatLayout layout) { com.formacraft.client.ui.panel.chat.ChatInputSupport.drawInputArea(this, ctx, layout); }

    public int getInputHeight(int selectionHintHeight) { return com.formacraft.client.ui.panel.chat.ChatInputSupport.getInputHeight(this, selectionHintHeight); }

    @Override
    protected boolean drawCustomTooltip(DrawContext ctx, double mouseX, double mouseY) { return com.formacraft.client.ui.panel.chat.ChatInteraction.drawCustomTooltip(this, ctx, mouseX, mouseY); }

    // ==========================
    //  输入&滚动事件
    // ==========================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) { return com.formacraft.client.ui.panel.chat.ChatInteraction.mouseClicked(this, mouseX, mouseY, button); }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) { return com.formacraft.client.ui.panel.chat.ChatInteraction.mouseDragged(this, mouseX, mouseY, button, deltaX, deltaY); }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) { return com.formacraft.client.ui.panel.chat.ChatInteraction.mouseReleased(this, mouseX, mouseY, button); }

    private boolean tryStartSelection(double mouseX, double mouseY) { return com.formacraft.client.ui.panel.chat.ChatInteraction.tryStartSelection(this, mouseX, mouseY); }

    @Override
    public void mouseScrolled(double mouseX, double mouseY, double amount) { com.formacraft.client.ui.panel.chat.ChatInteraction.mouseScrolled(this, mouseX, mouseY, amount); }

    @Override
    public void keyPressed(int keyCode) {
        keyPressed(keyCode, 0, 0);
    }

    @Override
    public void keyPressed(int keyCode, int scanCode, int modifiers) { com.formacraft.client.ui.panel.chat.ChatInteraction.keyPressed(this, keyCode, scanCode, modifiers); }

    @Override
    public void charTyped(char chr) { com.formacraft.client.ui.panel.chat.ChatInteraction.charTyped(this, chr); }

    @Override
    public boolean wantsKeyboardInput() {
        // 聊天面板：仅当输入框真正处于焦点时才声明需要键盘（与其它面板语义一致）
        return inputBox != null && inputBox.isFocused();
    }

    @Override
    public void blurKeyboardFocus() {
        blurInput();
    }

    public void blurInput() {
        if (inputBox != null) {
            inputBox.setFocused(false);
        }
        historyIndex = -1;
    }

    // ==========================
    //  核心逻辑：发送消息 & 调 AI
    // ==========================

    public void sendCurrentMessage() { com.formacraft.client.ui.panel.chat.ChatSendSupport.sendCurrentMessage(this); }

    private @NotNull List<String> getStrings() { return com.formacraft.client.ui.panel.chat.ChatSendSupport.getStrings(this); }

    private void notifyComponentCatalogStatus(String userText) { com.formacraft.client.ui.panel.chat.ChatSendSupport.notifyComponentCatalogStatus(this, userText); }

    private static boolean mightReferencePlayerComponents(String text) { return com.formacraft.client.ui.panel.chat.ChatSendSupport.mightReferencePlayerComponents(text); }

    public static @NotNull String getString(FormaRequest req) {
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

    private static String formatThinkingElapsed(String phase, long seconds) { return com.formacraft.client.ui.panel.chat.ChatSendSupport.formatThinkingElapsed(phase, seconds); }

    private boolean tryHandlePreviewAdjust(String text) { return com.formacraft.client.ui.panel.chat.ChatSendSupport.tryHandlePreviewAdjust(this, text); }

    public record PreviewAdjustCommand(int dx, int dy, int dz, String hint) {
        public static PreviewAdjustCommand parse(String raw, Direction facing) {
            var c = com.formacraft.client.ui.panel.chat.ChatPreviewAdjustCommand.parse(raw, facing);
            return c == null ? null : new PreviewAdjustCommand(c.dx(), c.dy(), c.dz(), c.hint());
        }
    }

    private static String buildHealthUrlOrNull(String endpoint) { return com.formacraft.client.ui.panel.chat.ChatSendSupport.buildHealthUrlOrNull(endpoint); }

    private boolean isHealthy(String healthUrl) { return com.formacraft.client.ui.panel.chat.ChatSendSupport.isHealthy(this, healthUrl); }

    public void stopGenerating() { com.formacraft.client.ui.panel.chat.ChatSendSupport.stopGenerating(this); }

    public void resetSession() {
        messages.clear();
        scrollOffset = 0;
        inputBox.clear();
        sessionId = java.util.UUID.randomUUID().toString();
    }

    public void startNewConversation() {
        stopGenerating();
        resetSession();
    }

    public void addAIMessage(String text, BuildingSpec spec) { com.formacraft.client.ui.panel.chat.ChatAiMessages.addAIMessage(this, text, spec); }

    public void addAIError(String text) { com.formacraft.client.ui.panel.chat.ChatAiMessages.addAIError(this, text); }

    public void addAIStatus(String text) { com.formacraft.client.ui.panel.chat.ChatAiMessages.addAIStatus(this, text); }

    public void setPendingPhase(String phase) { com.formacraft.client.ui.panel.chat.ChatAiMessages.setPendingPhase(this, phase); }

    public void renderPendingElapsed() { com.formacraft.client.ui.panel.chat.ChatAiMessages.renderPendingElapsed(this); }

    public void startElapsedTicker(long token) { com.formacraft.client.ui.panel.chat.ChatAiMessages.startElapsedTicker(this, token); }
}
