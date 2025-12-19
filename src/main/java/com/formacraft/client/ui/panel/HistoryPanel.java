package com.formacraft.client.ui.panel;

import com.formacraft.ai.ConversationSummaryService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import java.util.concurrent.CompletableFuture;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 对话历史面板
 * 固定左侧栏模式：320px 宽度，包含集成工具栏
 */
public class HistoryPanel extends BasePanel {

    private final MinecraftClient client = MinecraftClient.getInstance();

    private static class HistoryEntry {
        final ChatPanel.ConversationSnapshot snapshot;
        String title;
        String summary;
        boolean summarizing;

        HistoryEntry(ChatPanel.ConversationSnapshot snapshot) {
            this.snapshot = snapshot;
            this.title = snapshot.title;
            this.summary = "";
            this.summarizing = true;
        }
    }

    private final ConversationSummaryService summarizer = new ConversationSummaryService();
    private final List<HistoryEntry> conversations = new ArrayList<>();

    private long lastClickMs = 0L;
    private int lastClickIndex = -1;

    public void addConversation(ChatPanel.ConversationSnapshot snapshot) {
        if (snapshot == null) return;
        // 最近的放最上面
        HistoryEntry entry = new HistoryEntry(snapshot);
        conversations.addFirst(entry);
        // 简单上限，避免无限增长
        if (conversations.size() > 50) {
            conversations.removeLast();
        }

        // 异步总结，不阻塞渲染线程
        CompletableFuture.supplyAsync(() -> summarizer.summarize(snapshot.transcript))
                .whenComplete((res, err) -> client.execute(() -> {
                    entry.summarizing = false;
                    if (err != null || res == null) return;
                    entry.title = res.title;
                    entry.summary = res.summary;
                }));
    }

    @Override
    protected void drawContents(DrawContext ctx) {
        int innerX = panelX + 8;
        int innerY = getContentY() + 10;

        ctx.drawTextWithShadow(client.textRenderer, Text.literal("History"), innerX, innerY, 0xFFFFFFFF);
        innerY += 18;

        if (conversations.isEmpty()) {
            ctx.drawTextWithShadow(client.textRenderer, Text.literal("暂无历史对话"), innerX, innerY, 0xFFAAAAAA);
            return;
        }

        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss");
        int y = innerY;
        int lineH = 18;
        int max = Math.min(conversations.size(), 20);
        for (int i = 0; i < max; i++) {
            HistoryEntry e = conversations.get(i);
            String time = fmt.format(new Date(e.snapshot.timestampMs));
            String title = e.title == null ? "新对话" : e.title;
            String suffix = e.summarizing ? " (总结中…)" : "";
            ctx.drawTextWithShadow(client.textRenderer, Text.literal(title + suffix), innerX, y, 0xFFFFFFFF);
            ctx.drawTextWithShadow(client.textRenderer, Text.literal(time), innerX + 120, y, 0xFF888888);
            y += lineH;

            if (e.summary != null && !e.summary.isEmpty()) {
                String s = e.summary.length() <= 40 ? e.summary : e.summary.substring(0, 39) + "…";
                ctx.drawTextWithShadow(client.textRenderer, Text.literal(s), innerX, y, 0xFFAAAAAA);
                y += (lineH - 4);
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0) return false;

        // 计算列表点击项（与 drawContents 布局一致）
        int innerX = panelX + 8;
        int innerY = getContentY() + 10 + 18;
        if (mouseX < innerX) return false;

        int y = innerY;
        int max = Math.min(conversations.size(), 20);
        for (int i = 0; i < max; i++) {
            HistoryEntry e = conversations.get(i);
            int itemTop = y;
            int itemH = 18 + ((e.summary != null && !e.summary.isEmpty()) ? 14 : 0);
            int itemBottom = itemTop + itemH;
            if (mouseY >= itemTop && mouseY <= itemBottom) {
                long now = System.currentTimeMillis();
                boolean isDouble = (lastClickIndex == i) && (now - lastClickMs <= 280);
                lastClickIndex = i;
                lastClickMs = now;
                if (isDouble) {
                    // 双击：打开对话到 Chat
                    if (!com.formacraft.client.ui.FormaCraftHudOverlay.ensurePanelsReady()) return true;
                    if (com.formacraft.client.ui.FormaCraftHudOverlay.CHAT_PANEL != null) {
                        com.formacraft.client.ui.FormaCraftHudOverlay.CHAT_PANEL.loadConversationSnapshot(e.snapshot);
                        com.formacraft.client.ui.FormaCraftHudOverlay.activePanel = PanelType.CHAT;
                    }
                    return true;
                }
                return true;
            }
            y += itemH;
        }
        return false;
    }
}
