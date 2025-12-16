package com.formacraft.client.ui.panel.chat;

import com.formacraft.common.model.build.BuildingSpec;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 流式输出控制器（HUD 版，线程安全）
 *
 * 设计原则：
 * - AI 回调线程只负责 appendToken / finish / cancel（无渲染、无主线程依赖）
 * - ChatPanel 每帧调用 tick() 推进“打字机动画”
 * - 输出落到 ChatMessage 列表中（通过替换指定 index 的 ChatMessage 对象）
 */
public class AIStreamPrinter {

    private final List<ChatMessage> messages;
    private final int messageIndex;
    private final Queue<String> tokenQueue = new ConcurrentLinkedQueue<>();

    private final StringBuilder visible = new StringBuilder();
    private final AtomicBoolean closed = new AtomicBoolean(false);     // 不再接收 token（finish）
    private final AtomicBoolean cancelled = new AtomicBoolean(false);  // 中断（清空队列并结束）
    private final AtomicBoolean receivedAnyToken = new AtomicBoolean(false);

    private int charsPerTick = 2;
    private BuildingSpec spec = null;

    public AIStreamPrinter(List<ChatMessage> messages, int messageIndex) {
        this.messages = messages;
        this.messageIndex = messageIndex;
    }

    public void setCharsPerTick(int charsPerTick) {
        this.charsPerTick = Math.max(1, charsPerTick);
    }

    public void setSpec(BuildingSpec spec) {
        this.spec = spec;
    }

    // --------------------
    // 流式接口（AI 回调线程调用）
    // --------------------
    public void appendToken(String token) {
        if (token == null || token.isEmpty()) return;
        if (cancelled.get()) return;
        tokenQueue.add(token);
        // 线程安全：后台线程只入队，不直接改 messages（messages 只由渲染线程 tick 改）
        receivedAnyToken.set(true);
    }

    /** 表示 AI 不会再追加 token（但仍需要把队列打印完） */
    public void finish() {
        closed.set(true);
    }

    /** 立即中断：清空队列并结束输出 */
    public void cancel() {
        cancelled.set(true);
        tokenQueue.clear();
        closed.set(true);
        String finalText = visible.toString();
        if (!finalText.isEmpty()) {
            finalText = finalText + "\n⏹ 已停止生成";
        } else {
            finalText = "⏹ 已停止生成";
        }
        replaceMessage(ChatMessage.cancelled(finalText));
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public boolean isClosed() {
        return closed.get();
    }

    // --------------------
    // Tick（渲染线程每帧调用）
    // --------------------
    public void tick() {
        if (cancelled.get()) return;

        // 没 token：如果已关闭则结束
        if (tokenQueue.isEmpty()) {
            if (closed.get()) {
                finalizeMessage();
            }
            return;
        }

        // 只在渲染线程切换 thinking -> streaming
        if (receivedAnyToken.get()) {
            ensureStreamingMessage();
        }

        int remaining = charsPerTick;
        while (remaining > 0) {
            String token = tokenQueue.peek();
            if (token == null) break;

            if (token.length() <= remaining) {
                visible.append(token);
                tokenQueue.poll();
                remaining -= token.length();
            } else {
                visible.append(token, 0, remaining);
                tokenQueue.poll();
                tokenQueue.add(token.substring(remaining));
                remaining = 0;
            }
        }

        // 刷新消息文本（保持 STREAMING 状态）
        replaceMessage(ChatMessage.streaming(visible.toString(), spec));

        // 如果已经 finish 且队列清空，则完成
        if (closed.get() && tokenQueue.isEmpty()) {
            finalizeMessage();
        }
    }

    public boolean isFinished() {
        if (cancelled.get()) return true;
        if (!closed.get()) return false;
        return tokenQueue.isEmpty();
    }

    // --------------------
    // 内部：消息状态切换
    // --------------------
    private void ensureStreamingMessage() {
        ChatMessage cur = safeGetMessage();
        if (cur == null) return;
        if (cur.type == ChatMessage.MessageType.THINKING) {
            // thinking -> streaming（文本先为空）
            replaceMessage(ChatMessage.streaming(visible.toString(), spec));
        }
    }

    private void finalizeMessage() {
        ChatMessage cur = safeGetMessage();
        if (cur == null) return;

        String finalText = visible.toString();
        ChatMessage finalMsg;
        if (spec != null) {
            finalMsg = new ChatMessage(finalText, false, spec, ChatMessage.MessageType.SPEC);
        } else {
            finalMsg = new ChatMessage(finalText, false, null, ChatMessage.MessageType.TEXT);
        }
        replaceMessage(finalMsg);
    }

    private ChatMessage safeGetMessage() {
        if (messages == null) return null;
        if (messageIndex < 0 || messageIndex >= messages.size()) return null;
        return messages.get(messageIndex);
    }

    private void replaceMessage(ChatMessage msg) {
        if (messages == null) return;
        if (messageIndex < 0 || messageIndex >= messages.size()) return;
        messages.set(messageIndex, msg);
    }
}


