package com.formacraft.client.ui;

import com.formacraft.ai.AIResult;
import com.formacraft.ai.AIService;
import com.formacraft.ai.AIServiceManager;
import com.formacraft.ai.BuildingRequest;
import com.formacraft.common.builder.AutoBuilder;
import com.formacraft.common.builder.BuildingBlueprint;
import com.formacraft.common.builder.BuildingPlanner;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

public class FormaCraftChatScreen extends Screen {

    private static class ChatMessage {
        final String text;
        final boolean fromPlayer;

        ChatMessage(String text, boolean fromPlayer) {
            this.text = text;
            this.fromPlayer = fromPlayer;
        }
    }

    private final AIService aiService = new AIServiceManager();
    private final BuildingPlanner planner = new BuildingPlanner();
    private final AutoBuilder autoBuilder = new AutoBuilder();

    private TextFieldWidget input;
    private final List<ChatMessage> messages = new ArrayList<>();
    private final List<String> materialLines = new ArrayList<>();
    private int buildProgress = 0; // 0-100
    private String buildStatus = "等待生成方案...";
    private int scrollOffsetLines = 0;
    private String sessionId = java.util.UUID.randomUUID().toString();

    private int windowX = 40;
    private int windowY = 40;
    private int windowWidth = 260;
    private int windowHeight = 200;

    private boolean dragging = false;
    private int dragOffsetX;
    private int dragOffsetY;

    public FormaCraftChatScreen() {
        super(Text.translatable("formacraft.chat.title"));
    }

    @Override
    protected void init() {
        super.init();
        // 限制窗口大小不超过屏幕的一半
        int maxWidth = this.width / 2;
        int maxHeight = this.height / 2;
        if (windowWidth > maxWidth) {
            windowWidth = maxWidth;
        }
        if (windowHeight > maxHeight) {
            windowHeight = maxHeight;
        }
        // 确保窗口不会超出屏幕
        if (windowX + windowWidth > this.width) {
            windowX = this.width - windowWidth - 10;
        }
        if (windowY + windowHeight > this.height) {
            windowY = this.height - windowHeight - 10;
        }
        updateWidgetPositions();
    }
    
    /**
     * 检查鼠标是否在 FormaCraft 面板范围内
     */
    public boolean isMouseOverPanel(double mouseX, double mouseY) {
        return mouseX >= windowX && mouseX <= windowX + windowWidth
                && mouseY >= windowY && mouseY <= windowY + windowHeight;
    }
    
    private void updateWidgetPositions() {
        // 清除旧的控件
        this.clearChildren();
        
        int padding = 6;

        int inputWidth = windowWidth - padding * 2 - 50;
        int inputX = windowX + padding;
        int inputY = windowY + windowHeight - padding - 18;

        input = new TextFieldWidget(this.textRenderer, inputX, inputY, inputWidth, 18, Text.empty());
        addDrawableChild(input);

        addDrawableChild(ButtonWidget.builder(Text.translatable("formacraft.button.send"), b -> sendMessage())
                .dimensions(inputX + inputWidth + 4, inputY, 40, 18)
                .build());

        // 新建会话按钮：位于标题栏右上角，点击后清空当前对话与材料/进度
        int titleBarHeight = 16;
        int newBtnWidth = 36;
        int newBtnHeight = 12;
        int newBtnX = windowX + windowWidth - newBtnWidth - 4;
        int newBtnY = windowY + (titleBarHeight - newBtnHeight) / 2;
        addDrawableChild(ButtonWidget.builder(Text.translatable("formacraft.chat.new_session"), b -> resetSession())
                .dimensions(newBtnX, newBtnY, newBtnWidth, newBtnHeight)
                .build());
    }

    private void resetSession() {
        messages.clear();
        materialLines.clear();
        buildProgress = 0;
        buildStatus = "等待生成方案...";
        scrollOffsetLines = 0;
        sessionId = java.util.UUID.randomUUID().toString();
    }

    private void sendMessage() {
        String text = input.getText();
        if (text == null || text.isEmpty()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;

        messages.add(new ChatMessage(text, true));

        World world = client.world;
        BlockPos start = client.player.getBlockPos().add(2, 0, 2);
        String dimensionId = world.getRegistryKey().getValue().toString();

        java.util.List<String> history = new java.util.ArrayList<>();
        for (ChatMessage m : messages) {
            history.add((m.fromPlayer ? "Player: " : "AI: ") + (m.text == null ? "" : m.text));
        }
        BuildingRequest request = new BuildingRequest(text, start, dimensionId, sessionId, history);
        AIResult result = aiService.generateBuildingPlan(request);
        messages.add(new ChatMessage(result.getRawResponse(), false));

        BuildingBlueprint blueprint = planner.plan(request, result);
        // TODO: 未来可根据 blueprint 计算真实材料需求
        materialLines.clear();
        materialLines.add("占位: 石头 x 128");
        materialLines.add("占位: 玻璃 x 32");
        buildProgress = 0;
        buildStatus = "正在建造...";
        autoBuilder.build(world, blueprint);
        buildProgress = 100;
        buildStatus = "建造完成";

        input.setText("");
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // 重写此方法以禁用默认的模糊背景
        // 不调用 super.renderBackground()，这样就不会渲染模糊背景
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (this.client == null) return;

        // 不绘制全屏背景，让用户能看到 Minecraft 场景
        
        // 半透明窗口背景
        int bgColor = 0xAA000000;
        context.fill(windowX, windowY, windowX + windowWidth, windowY + windowHeight, bgColor);

        // 标题栏
        int titleBarHeight = 16;
        int titleBarColor = 0xCC222222;
        context.fill(windowX, windowY, windowX + windowWidth, windowY + titleBarHeight, titleBarColor);
        context.drawText(this.textRenderer, this.title, windowX + 6, windowY + 4, 0xFFFFFF, false);

        int padding = 6;
        int innerX = windowX + padding;
        int innerY = windowY + titleBarHeight + padding;
        int innerWidth = windowWidth - padding * 2;
        int innerHeight = windowHeight - titleBarHeight - padding * 2;

        // 左右分栏：左侧聊天，右侧材料+进度
        int rightPanelWidth = 90;
        int chatWidth = innerWidth - rightPanelWidth - 4;
        int chatHeight = innerHeight - 24;

        // 对话区域：从下往上绘制
        int lineStep = this.textRenderer.fontHeight + 2;
        int visibleLines = Math.max(1, chatHeight / lineStep);
        int maxExtra = Math.max(0, messages.size() - visibleLines);
        if (scrollOffsetLines > maxExtra) {
            scrollOffsetLines = maxExtra;
        }
        // 防止在 messages 为空时访问索引
        if (!messages.isEmpty()) {
            int startIndex = Math.max(0, messages.size() - 1 - scrollOffsetLines);
            int y = innerY + chatHeight - this.textRenderer.fontHeight;
            int drawn = 0;
            for (int i = startIndex; i >= 0 && y >= innerY && drawn < visibleLines; i--) {
                ChatMessage msg = messages.get(i);
                int color = msg.fromPlayer ? 0x66CCFFFF : 0xFFFFFFFF;
                String line = msg.text;
                int lineWidth = this.textRenderer.getWidth(line);
                int drawX = msg.fromPlayer ? innerX + Math.max(0, chatWidth - lineWidth) : innerX;
                context.drawText(this.textRenderer, line, drawX, y, color, false);
                y -= lineStep;
                drawn++;
            }
        }

        // 右侧材料与进度面板
        int panelX = innerX + chatWidth + 4;
        int panelBg = 0x55222222;
        context.fill(panelX, innerY, panelX + rightPanelWidth, innerY + innerHeight, panelBg);

        int textY = innerY + 4;
        context.drawText(this.textRenderer, Text.translatable("formacraft.chat.materials"), panelX + 4, textY, 0xFFFFFF, false);
        textY += this.textRenderer.fontHeight + 2;
        for (String line : materialLines) {
            context.drawText(this.textRenderer, line, panelX + 4, textY, 0xDDDDDD, false);
            textY += this.textRenderer.fontHeight + 1;
        }

        textY += 4;
        context.drawText(this.textRenderer, Text.translatable("formacraft.chat.progress"), panelX + 4, textY, 0xFFFFFF, false);
        textY += this.textRenderer.fontHeight + 2;
        // 简单进度条
        int barWidth = rightPanelWidth - 8;
        int barX = panelX + 4;
        int barY = textY;
        int barHeight = 6;
        context.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF444444);
        int filled = (int) (barWidth * (buildProgress / 100.0f));
        if (filled > 0) {
            context.fill(barX, barY, barX + filled, barY + barHeight, 0xFF00FF00);
        }
        textY += barHeight + 4;
        context.drawText(this.textRenderer, buildStatus, panelX + 4, textY, 0xDDDDDD, false);
        
        // 调用父类方法渲染子控件（按钮、输入框等）
        super.render(context, mouseX, mouseY, delta);
    }

    // 注意：在 Minecraft 1.21.10 中，鼠标事件处理方法的签名已更改
    // 暂时移除拖动功能，确保控件可以正常显示
    // TODO: 后续需要实现正确的鼠标事件处理以支持窗口拖动

    @Override
    public boolean shouldPause() {
        return false;
    }
}
