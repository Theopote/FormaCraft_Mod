package com.formacraft.client.ui.panel.chat;

import com.formacraft.client.ui.panel.ChatPanel;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import com.formacraft.client.ai.prompt.PromptMode;
import com.formacraft.client.ai.prompt.PromptModeResolver;
import com.formacraft.client.component.ClientComponentCatalogState;
import com.formacraft.client.network.FormaCraftClientNetworking;
import com.formacraft.client.preview.BuildingPreviewState;
import com.formacraft.client.preview.OutlinePreviewState;
import com.formacraft.client.preview.PromptModeState;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.config.SettingsConfig;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;

public final class ChatSendSupport {
    private ChatSendSupport() {}
    public static void sendCurrentMessage(ChatPanel host) {

        if (host.readingReferenceImage) return;
        String text = host.inputBox.getText().trim();
        if (text.isEmpty()) return;

        if (host.client.player == null || host.client.world == null) return;
        if (host.client.getNetworkHandler() == null) {
            host.messages.add(ChatMessage.error(Text.translatable("formacraft.chat.error.not_connected").getString()));
            host.scrollOffset = 0;
            return;
        }

        // 预览状态下的“位置微调”指令：不走 AI 请求
        if (tryHandlePreviewAdjust(host, text)) {
            host.inputBox.clear();
            return;
        }

        // 如果上一条还在生成，先真中断，避免并发覆盖 UI（本地流式）
        stopGenerating(host);

        // UI 不再展示模式按钮：从用户原文推断 BUILD / PATCH / MODIFY_REGION，
        // 避免「只改局部」仍走整栋重建（Phase 1 A2）。
        PromptMode effectiveMode = PromptModeResolver.fromUserText(text);
        host.promptMode = effectiveMode;

        // 记录本次使用的模式（用于 Prompt/BuildContext/PatchFilter 统一解析）
        PromptModeState.setLastMode(effectiveMode);

        // C2S sends raw user text + structured constraints. System prompt is assembled
        // by the Python orchestrator (avoids host.client injection and "USER REQUEST:" truncation).
        List<String> history = getStrings(host);

        FormaRequest req = com.formacraft.client.ui.panel.chat.ChatBuildRequestFactory.fromChat(
                text, effectiveMode, host.sessionId, history);
        java.util.UUID imageToken = host.referenceImageFile == null ? null : java.util.UUID.randomUUID();
        if (host.hasReferenceImage()) {
            var references = new ArrayList<com.formacraft.common.model.request.ReferenceInput>();
            var attachment = imageToken == null
                    ? new com.formacraft.common.model.request.ReferenceInput("image_url", host.referenceImageUrl, null)
                    : new com.formacraft.common.model.request.ReferenceInput(
                            com.formacraft.common.network.ReferenceImageChunk.REFERENCE_TYPE, imageToken.toString(), null);
            references.add(attachment);
            for (var reference : req.getReferences()) {
                if (!attachment.getContent().equals(reference.getContent())) references.add(reference);
            }
            req.setReferences(references);
        }
        if (!FormaCraftClientNetworking.sendBuildRequest(req, imageToken, host.referenceImageFile)) return;
        host.referenceImageUrl = null;
        host.referenceImageFile = null;

        // 追加玩家消息（显示原始输入，避免聊天内容被“系统拼接”污染）
        host.messages.add(new ChatMessage(text, true));
        host.scrollOffset = 0; // 回到底部，显示最新消息

        // 记录输入历史（去重：与上一条完全相同则不重复入栈）
        if (host.inputHistory.isEmpty() || !host.inputHistory.getLast().equals(text)) {
            host.inputHistory.add(text);
            // 简单上限，避免无限增长
            if (host.inputHistory.size() > 50) {
                host.inputHistory.removeFirst();
            }
        }
        host.historyIndex = -1;
        host.historyDraft = "";

        notifyComponentCatalogStatus(host, text);

        BlockPos origin = req.getPlayerPos();

        // 记录 origin，确保 confirm 时与预览一致
        BuildingPreviewState.setPendingOrigin(origin);

        // 添加 AI thinking 占位消息（等服务端返回 ResponseBuildSpecPayload / Error 时会补上 AI 消息）
        host.pendingRequestToken = System.currentTimeMillis();
        host.pendingThinkingIndex = host.messages.size();
        // 给用户可见性：显示本次使用的 provider/model（不暴露 API Key）
        String basePhase = ChatPanel.getString(req);
        host.pendingPhase = basePhase;
        host.pendingStartMs = System.currentTimeMillis();
        host.messages.add(ChatMessage.thinking(formatThinkingElapsed(basePhase, 0)));
        host.scrollOffset = 0;

        // 本地等待计时：第 1 秒即开始跳动，不依赖服务端心跳
        host.startElapsedTicker(host.pendingRequestToken);

        // 软超时：服务端/AI 可能在生成，15s 不应直接报错（避免误导）
        final long token = host.pendingRequestToken;
        CompletableFuture.delayedExecutor(ChatPanel.SOFT_TIMEOUT_SEC, TimeUnit.SECONDS).execute(() -> {
            // 仅在“本次请求仍在等待”时触发
            if (host.pendingRequestToken != token) return;
            if (host.pendingThinkingIndex < 0 || host.pendingThinkingIndex >= host.messages.size()) return;
            ChatMessage cur = host.messages.get(host.pendingThinkingIndex);
            if (cur == null || cur.type != ChatMessage.MessageType.THINKING) return;

            // 本地 endpoint 时做一次 /health 快速判断，让提示更准确
            String endpoint = SettingsConfig.INSTANCE.orchestratorEndpoint;
            String healthUrl = buildHealthUrlOrNull(endpoint);
            if (healthUrl != null) {
                CompletableFuture.supplyAsync(() -> isHealthy(host, healthUrl))
                        .thenAccept(healthy -> host.client.execute(() -> {
                            if (host.pendingRequestToken != token) return;
                            if (host.pendingThinkingIndex < 0 || host.pendingThinkingIndex >= host.messages.size()) return;
                            ChatMessage cur2 = host.messages.get(host.pendingThinkingIndex);
                            if (cur2 == null || cur2.type != ChatMessage.MessageType.THINKING) return;

                            if (healthy) {
                                host.setPendingPhase(Text.translatable("formacraft.chat.phase.still_generating_healthy").getString());
                            } else {
                                host.setPendingPhase(Text.translatable("formacraft.chat.phase.still_waiting_backend").getString());
                            }
                        }));
            } else {
                host.client.execute(() -> {
                    if (host.pendingRequestToken != token) return;
                    if (host.pendingThinkingIndex < 0 || host.pendingThinkingIndex >= host.messages.size()) return;
                    ChatMessage cur2 = host.messages.get(host.pendingThinkingIndex);
                    if (cur2 == null || cur2.type != ChatMessage.MessageType.THINKING) return;
                    host.setPendingPhase(Text.translatable("formacraft.chat.phase.still_waiting_server").getString());
                });
            }
        });

        // 硬超时：长时间无回包才报错（避免把“生成慢”误判成“后端没跑”）
        CompletableFuture.delayedExecutor(ChatPanel.HARD_TIMEOUT_SEC, TimeUnit.SECONDS).execute(() -> host.client.execute(() -> {
            if (host.pendingRequestToken != token) return;
            if (host.pendingThinkingIndex < 0 || host.pendingThinkingIndex >= host.messages.size()) return;
            ChatMessage cur = host.messages.get(host.pendingThinkingIndex);
            if (cur == null || cur.type != ChatMessage.MessageType.THINKING) return;
            host.messages.set(host.pendingThinkingIndex, ChatMessage.error(
                    Text.translatable("formacraft.chat.error.timeout", ChatPanel.HARD_TIMEOUT_SEC).getString()
            ));
            host.scrollOffset = 0;
        }));

        // 清空输入框
        host.inputBox.clear();
    
    }

    public static List<String> getStrings(ChatPanel host) {

        List<String> history = new ArrayList<>();
        int histFrom = Math.max(0, host.messages.size() - 8);
        for (int i = histFrom; i < host.messages.size(); i++) {
            ChatMessage m = host.messages.get(i);
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

    public static void notifyComponentCatalogStatus(ChatPanel host, String userText) {

        ClientComponentCatalogState.hydrateFromDiskIfEmpty();

        if (ClientComponentCatalogState.isSyncPending() && !ClientComponentCatalogState.isSyncComplete()) {
            FormaCraftClientNetworking.sendComponentCatalogRequest();
            host.messages.add(ChatMessage.system(
                    Text.translatable("formacraft.chat.catalog.sync_pending").getString()));
            host.scrollOffset = 0;
            return;
        }

        if (!ClientComponentCatalogState.hasRegisteredComponents() && mightReferencePlayerComponents(userText)) {
            host.messages.add(ChatMessage.system(
                    Text.translatable("formacraft.chat.catalog.no_components").getString()));
            host.scrollOffset = 0;
        }
    
    }

    public static boolean mightReferencePlayerComponents(String text) {

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

    public static String formatThinkingElapsed(String phase, long seconds) {

        String p = (phase == null || phase.isBlank())
                ? Text.translatable("formacraft.chat.phase.generating_default").getString()
                : phase.trim();
        return Text.translatable("formacraft.chat.thinking.elapsed", p, seconds).getString();
    
    }

    public static boolean tryHandlePreviewAdjust(ChatPanel host, String text) {

        if (!(BuildingPreviewState.isActive() || OutlinePreviewState.active) || text == null) {
            return false;
        }
        ChatPanel.PreviewAdjustCommand cmd = ChatPanel.PreviewAdjustCommand.parse(text, host.client.player != null ? host.client.player.getHorizontalFacing() : null);
        if (cmd == null) {
            return false;
        }
        host.messages.add(new ChatMessage(text, true));
        host.scrollOffset = 0;

        if (host.inputHistory.isEmpty() || !host.inputHistory.getLast().equals(text)) {
            host.inputHistory.add(text);
            if (host.inputHistory.size() > 50) {
                host.inputHistory.removeFirst();
            }
        }
        host.historyIndex = -1;
        host.historyDraft = "";

        host.messages.add(ChatMessage.system(cmd.hint()));
        host.scrollOffset = 0;
        FormaCraftClientNetworking.sendPreviewAdjust(cmd.dx(), cmd.dy(), cmd.dz());
        return true;
    
    }

    public static String buildHealthUrlOrNull(String endpoint) {

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

    public static boolean isHealthy(ChatPanel host, String healthUrl) {

        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(healthUrl))
                    .timeout(Duration.ofSeconds(2))
                    .header("Accept", "application/json")
                    .GET();
            com.formacraft.common.network.OrchestratorSecretHeaders.apply(
                    b, com.formacraft.common.network.OrchestratorSecretHeaders.fromEnvironment());
            HttpResponse<String> resp = host.healthHttp.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() >= 200 && resp.statusCode() < 300;
        } catch (Exception e) {
            ChatPanel.LOG.debug("backend health check failed url={}", healthUrl, e);
            return false;
        }
    
    }

    public static void stopGenerating(ChatPanel host) {

        // 停止打字机 + 将消息标记为“已停止生成”
        if (host.currentPrinter != null) {
            host.currentPrinter.cancel();
            host.currentPrinter = null;
            host.scrollOffset = 0;
        }
    
    }
}
