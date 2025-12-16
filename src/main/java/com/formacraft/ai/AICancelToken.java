package com.formacraft.ai;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 取消令牌（用于“真中断”）
 *
 * 设计目标：
 * - Stop 按钮按下后：token.cancel() 立即生效
 * - AI 调用层/网络层可定期检查 token.isCancelled()
 * - 与 CompletableFuture.cancel(true) / 线程中断配合使用
 */
public class AICancelToken {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }
}


