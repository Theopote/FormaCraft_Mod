package com.formacraft.common.network.metrics;

import com.formacraft.FormacraftMod;
import com.formacraft.common.model.request.FormaRequest;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LlmPlan 预览链路指标（进程内累计 + 结构化日志）。
 * <p>
 * 日志前缀：{@code [LlmPlanMetrics]}。grep 示例：
 * <pre>{@code
 * Select-String "\[LlmPlanMetrics\]" logs/latest.log
 * }</pre>
 * <p>
 * <b>语义（2026-11-15）</b>：LlmPlan 预览失败<strong>不再</strong>回退假 BuildingSpec。
 * 未产出预览时记 {@code error}（如 {@code preview_unhandled_no_structure_fallback}），
 * <strong>不要</strong>记 {@code structure_after_fallback}。
 * {@link #recordFallback} / {@link #recordStructureAfterFallback} 仅在真实发生整栋回退时使用；
 * 现行主干不应再调用后者。迁移决策勿使用修复前被污染的历史 fallback 率。
 */
public final class LlmPlanRoutingMetrics {

    public enum FallbackReason {
        ROUTING_POLICY,
        EMPTY_OUTPUT
    }

    private static final AtomicLong taggedRequests = new AtomicLong();
    private static final AtomicLong llmSuccess = new AtomicLong();
    private static final AtomicLong llmError = new AtomicLong();
    private static final AtomicLong llmFallback = new AtomicLong();
    private static final AtomicLong directStructure = new AtomicLong();
    private static final AtomicLong structureAfterFallback = new AtomicLong();

    private LlmPlanRoutingMetrics() {}

    /** 进入 {@link com.formacraft.common.orchestrator.AiPlanResult.LlmPlan} 处理分支 */
    public static void recordTaggedAttempt(String player, FormaRequest req) {
        taggedRequests.incrementAndGet();
        log("attempt", null, player, req);
    }

    public static void recordSuccess(String player, FormaRequest req, int patchCount, int blockCount) {
        llmSuccess.incrementAndGet();
        log("success", "patches=" + patchCount + " blocks=" + blockCount, player, req);
    }

    public static void recordError(String player, FormaRequest req, String errorKind) {
        llmError.incrementAndGet();
        log("error", errorKind, player, req);
    }

    /**
     * 主动放弃 LlmPlan 并<strong>实际</strong>进入整栋预览前记录。
     * 现行路径已取消假 BuildingSpec 回退；无真实回退时请用 {@link #recordError}。
     */
    public static void recordFallback(
            FallbackReason reason,
            String player,
            FormaRequest req
    ) {
        llmFallback.incrementAndGet();
        log("fallback", reason.name(), player, req);
    }

    /** 从未标记 LlmPlan，直接走整栋预览（显式 buildingspec compat） */
    public static void recordDirectStructurePreview(String player, FormaRequest req) {
        directStructure.incrementAndGet();
        log("structure_direct", null, player, req);
    }

    /**
     * 仅在 {@link #recordFallback} 之后<strong>确实</strong>执行了整栋预览时调用。
     * 预览失败且无回退时禁止调用——改用 {@link #recordError}。
     */
    public static void recordStructureAfterFallback(String player, FormaRequest req) {
        structureAfterFallback.incrementAndGet();
        log("structure_after_fallback", null, player, req);
    }

    /** Test / diagnostics: reset process-local counters. */
    public static void resetForTest() {
        taggedRequests.set(0);
        llmSuccess.set(0);
        llmError.set(0);
        llmFallback.set(0);
        directStructure.set(0);
        structureAfterFallback.set(0);
    }

    public static Snapshot snapshot() {
        long tagged = taggedRequests.get();
        long fallback = llmFallback.get();
        double fallbackRate = tagged > 0 ? (100.0 * fallback / tagged) : 0.0;
        long success = llmSuccess.get();
        double successRate = tagged > 0 ? (100.0 * success / tagged) : 0.0;
        return new Snapshot(
                tagged,
                success,
                llmError.get(),
                fallback,
                fallbackRate,
                successRate,
                directStructure.get(),
                structureAfterFallback.get()
        );
    }

    private static void log(String event, String detail, String player, FormaRequest req) {
        String playerName = player != null ? player : "?";
        String prompt = truncate(com.formacraft.common.model.request.RequestIntentText.resolve(req), 80);
        if (detail == null || detail.isBlank()) {
            FormacraftMod.LOGGER.info(
                    "[LlmPlanMetrics] event={} player={} prompt=\"{}\" {}",
                    event, playerName, prompt, snapshot()
            );
        } else {
            FormacraftMod.LOGGER.info(
                    "[LlmPlanMetrics] event={} detail={} player={} prompt=\"{}\" {}",
                    event, detail, playerName, prompt, snapshot()
            );
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        String t = text.replace('\n', ' ').trim();
        if (t.length() <= maxLen) {
            return t;
        }
        return t.substring(0, maxLen) + "…";
    }

    public record Snapshot(
            long tagged,
            long success,
            long errors,
            long fallback,
            double fallbackRatePercent,
            double successRatePercent,
            long directStructure,
            long structureAfterFallback
    ) {
        @Override
        public @NotNull String toString() {
            return String.format(
                    Locale.ROOT,
                    "tagged=%d success=%d errors=%d fallback=%d fallback_rate=%.1f%% success_rate=%.1f%% direct_structure=%d structure_after_fallback=%d",
                    tagged, success, errors, fallback, fallbackRatePercent, successRatePercent,
                    directStructure, structureAfterFallback
            );
        }
    }
}
