package com.formacraft.common.network.metrics;

import com.formacraft.FormacraftMod;
import com.formacraft.common.model.request.FormaRequest;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LlmPlan 预览链路指标（进程内累计 + 结构化日志）。
 * <p>
 * 日志前缀：{@code [LlmPlanMetrics]}。
 * <p>
 * <b>语义（2026-11-15 PR-5）</b>：假 BuildingSpec 回退已退出。预览失败记 {@code error}；
 * 显式 buildingspec 兼容预览记 {@code structure_direct}。
 * 已删除无生产调用者的 {@code fallback} / {@code structure_after_fallback} 记账 API。
 */
public final class LlmPlanRoutingMetrics {

    private static final AtomicLong taggedRequests = new AtomicLong();
    private static final AtomicLong llmSuccess = new AtomicLong();
    private static final AtomicLong llmError = new AtomicLong();
    private static final AtomicLong directStructure = new AtomicLong();

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

    /** 从未标记 LlmPlan，直接走整栋预览（显式 buildingspec compat） */
    public static void recordDirectStructurePreview(String player, FormaRequest req) {
        directStructure.incrementAndGet();
        log("structure_direct", null, player, req);
    }

    /** Test / diagnostics: reset process-local counters. */
    public static void resetForTest() {
        taggedRequests.set(0);
        llmSuccess.set(0);
        llmError.set(0);
        directStructure.set(0);
    }

    public static Snapshot snapshot() {
        long tagged = taggedRequests.get();
        long success = llmSuccess.get();
        double successRate = tagged > 0 ? (100.0 * success / tagged) : 0.0;
        return new Snapshot(
                tagged,
                success,
                llmError.get(),
                successRate,
                directStructure.get()
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
            double successRatePercent,
            long directStructure
    ) {
        @Override
        public @NotNull String toString() {
            return String.format(
                    Locale.ROOT,
                    "tagged=%d success=%d errors=%d success_rate=%.1f%% direct_structure=%d",
                    tagged, success, errors, successRatePercent, directStructure
            );
        }
    }
}
