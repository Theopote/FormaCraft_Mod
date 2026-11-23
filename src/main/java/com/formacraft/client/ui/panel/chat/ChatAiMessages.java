package com.formacraft.client.ui.panel.chat;

import com.formacraft.client.ui.panel.BuildConfirmPanel;
import com.formacraft.client.ui.panel.ChatPanel;
import com.formacraft.common.model.build.BuildingSpec;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class ChatAiMessages {
    private ChatAiMessages() {}
    public static void addAIMessage(ChatPanel host, String text, BuildingSpec spec) {

        // 若上一条是“thinking”，先移除，避免残留占位
        while (!host.messages.isEmpty() && host.messages.getLast().type == ChatMessage.MessageType.THINKING) {
            host.messages.removeLast();
        }
        host.pendingThinkingIndex = -1;
        host.pendingRequestToken = 0L;
        if (spec != null) {
            host.messages.add(new ChatMessage(text, false, spec));
        } else {
            host.messages.add(new ChatMessage(text, false));
        }
        host.scrollOffset = 0; // 自动滚动到底部
    
    }

    public static void addAIError(ChatPanel host, String text) {

        while (!host.messages.isEmpty() && host.messages.getLast().type == ChatMessage.MessageType.THINKING) {
            host.messages.removeLast();
        }
        host.pendingThinkingIndex = -1;
        host.pendingRequestToken = 0L;
        host.messages.add(ChatMessage.error(text));
        host.scrollOffset = 0;
    
    }

    public static void addAIStatus(ChatPanel host, String text) {

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

        if (host.pendingThinkingIndex >= 0 && host.pendingThinkingIndex < host.messages.size()) {
            ChatMessage cur = host.messages.get(host.pendingThinkingIndex);
            if (cur != null && cur.type == ChatMessage.MessageType.THINKING) {
                if (isTerminal) {
                    // 结束本次请求
                    host.messages.set(host.pendingThinkingIndex, new ChatMessage(t, false));
                    host.pendingThinkingIndex = -1;
                    host.pendingRequestToken = 0L;
                    host.scrollOffset = 0;
                } else {
                    // 只更新“阶段文案”，等待秒数交给本地 ticker，避免重复“已等待”
                    setPendingPhase(host, t);
                }
                return;
            }
        }

        // fallback：如果没有 pending thinking，就作为系统消息插入
        host.messages.add(new ChatMessage(t, false));
        if (isTerminal) {
            host.pendingThinkingIndex = -1;
            host.pendingRequestToken = 0L;
        }
        host.scrollOffset = 0;
    
    }

    public static void setPendingPhase(ChatPanel host, String phase) {

        if (phase == null) return;
        host.pendingPhase = phase.trim();
        renderPendingElapsed(host);
    
    }

    public static void renderPendingElapsed(ChatPanel host) {

        if (host.pendingRequestToken == 0L) return;
        if (host.pendingThinkingIndex < 0 || host.pendingThinkingIndex >= host.messages.size()) return;
        ChatMessage cur = host.messages.get(host.pendingThinkingIndex);
        if (cur == null || cur.type != ChatMessage.MessageType.THINKING) return;
        long sec = Math.max(0, (System.currentTimeMillis() - host.pendingStartMs) / 1000);
        host.messages.set(host.pendingThinkingIndex, ChatMessage.thinking(ChatSendSupport.formatThinkingElapsed(host.pendingPhase, sec)));
    
    }

    public static void startElapsedTicker(ChatPanel host, long token) {

        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (host.pendingRequestToken != token) return;
                host.client.execute(() -> {
                    if (host.pendingRequestToken != token) return;
                    renderPendingElapsed(host);
                });
                CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS).execute(this);
            }
        };
        CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS).execute(tick);
    
    }
}
