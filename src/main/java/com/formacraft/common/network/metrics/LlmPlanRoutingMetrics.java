package com.formacraft.common.network.metrics;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.routing.BuildingSpecRoutingPolicy;
import com.formacraft.common.model.request.FormaRequest;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LlmPlan 预览链路指标（进程内累计 + 结构化日志，便于从 {@code logs/latest.log} 统计回退率）。
 * <p>
 * 日志前缀：{@code [LlmPlanMetrics]}。grep 示例：
 * <pre>{@code
 * Select-String "\[LlmPlanMetrics\]" logs/latest.log
 * }</pre>
 * <p>
 * <b>回退率</b> = {@code fallback / tagged}（仅统计 {@link com.formacraft.common.orchestrator.AiPlanResult.LlmPlan} 分支）。
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
    public static void recordTaggedAttempt(ServerPlayerEntity player, FormaRequest req) {
        taggedRequests.incrementAndGet();
        log("attempt", null, player, req);
    }

    public static void recordSuccess(ServerPlayerEntity player, FormaRequest req, int patchCount, int blockCount) {
        llmSuccess.incrementAndGet();
        log("success", "patches=" + patchCount + " blocks=" + blockCount, player, req);
    }

    public static void recordError(ServerPlayerEntity player, FormaRequest req, String errorKind) {
        llmError.incrementAndGet();
        log("error", errorKind, player, req);
    }

    /** LlmPlan 主动放弃预览，回退整栋 {@code GenerationHub.routeStructure()} */
    public static void recordFallback(
            FallbackReason reason,
            ServerPlayerEntity player,
            FormaRequest req
    ) {
        llmFallback.incrementAndGet();
        log("fallback", reason.name(), player, req);
    }

    /** 从未标记 LlmPlan，直接走整栋预览 */
    public static void recordDirectStructurePreview(ServerPlayerEntity player, FormaRequest req) {
        directStructure.incrementAndGet();
        log("structure_direct", null, player, req);
    }

    /** LlmPlan 返回 false 后，实际进入整栋预览（与 {@link #recordFallback} 成对出现） */
    public static void recordStructureAfterFallback(ServerPlayerEntity player, FormaRequest req) {
        structureAfterFallback.incrementAndGet();
        log("structure_after_fallback", null, player, req);
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

    private static void log(String event, String detail, ServerPlayerEntity player, FormaRequest req) {
        String playerName = player != null ? player.getName().getString() : "?";
        String prompt = truncate(BuildingSpecRoutingPolicy.userIntentText(req), 80);
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
        public String toString() {
            return String.format(
                    Locale.ROOT,
                    "tagged=%d success=%d errors=%d fallback=%d fallback_rate=%.1f%% success_rate=%.1f%% direct_structure=%d structure_after_fallback=%d",
                    tagged, success, errors, fallback, fallbackRatePercent, successRatePercent,
                    directStructure, structureAfterFallback
            );
        }
    }
}
