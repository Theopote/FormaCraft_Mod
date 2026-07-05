package com.formacraft.common.network.metrics;

import com.formacraft.FormacraftMod;
import com.formacraft.common.archetype.LandmarkModuleRegistry;
import com.formacraft.common.archetype.LandmarkRoutingPolicy;
import com.formacraft.common.generation.routing.BuildingSpecRoutingPolicy;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.model.request.FormaRequest;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 地标 MODULE 路由指标：暴露「目录无精确匹配、退而求其次」的缺口。
 * <p>
 * 日志前缀：{@code [LandmarkMetrics]}。grep 示例：
 * <pre>{@code
 * Select-String "\[LandmarkMetrics\]" logs/latest.log
 * }</pre>
 */
public final class LandmarkRoutingMetrics {

    private static final AtomicLong explicitMatch = new AtomicLong();
    private static final AtomicLong approxMatch = new AtomicLong();
    private static final AtomicLong unmatchedIntent = new AtomicLong();

    private LandmarkRoutingMetrics() {}

    /**
     * 扫描 LlmPlan 中的 landmark/module feature，与用户意图对比后打点。
     */
    public static void recordFromPlan(ServerPlayerEntity player, FormaRequest req, LlmPlan plan) {
        if (plan == null || plan.components() == null) return;
        String intent = BuildingSpecRoutingPolicy.userIntentText(req);
        String llmModule = extractLandmarkModuleId(plan.components());
        if (llmModule == null) return;

        LandmarkRoutingPolicy.RoutingDecision decision = LandmarkRoutingPolicy.resolveForUserIntent(intent);
        String policyModule = decision != null ? decision.moduleId() : null;
        String intentResolved = LandmarkModuleRegistry.resolveModuleIdFromIntent(intent);

        if (isApproximateLandmarkMatch(intent, llmModule)) {
            approxMatch.incrementAndGet();
            log("landmark_fallback", player, intent, llmModule, policyModule, intentResolved, "approx_match");
        } else if (llmModule.equals(policyModule) || llmModule.equals(intentResolved)) {
            explicitMatch.incrementAndGet();
            log("landmark_match", player, intent, llmModule, policyModule, intentResolved, "exact");
        } else {
            unmatchedIntent.incrementAndGet();
            log("landmark_fallback", player, intent, llmModule, policyModule, intentResolved, "llm_override");
        }
    }

    public static String extractLandmarkModuleId(LlmPlan plan) {
        if (plan == null || plan.components() == null) return null;
        return extractLandmarkModuleId(plan.components());
    }

    private static String extractLandmarkModuleId(List<Component> components) {
        if (components == null) return null;
        for (Component c : components) {
            if (c == null || c.features() == null) continue;
            for (String feature : c.features()) {
                if (feature == null) continue;
                String lower = feature.toLowerCase(Locale.ROOT);
                if (lower.startsWith("landmark:")) {
                    String raw = feature.substring("landmark:".length()).trim();
                    String resolved = LandmarkModuleRegistry.resolveModuleId(raw);
                    return resolved != null ? resolved : raw.toLowerCase(Locale.ROOT);
                }
                if (lower.startsWith("module:")) {
                    String raw = feature.substring("module:".length()).trim();
                    String resolved = LandmarkModuleRegistry.resolveModuleId(raw);
                    return resolved != null ? resolved : raw.toLowerCase(Locale.ROOT);
                }
            }
            if (c.params() != null && c.params().get("module_id") != null) {
                String raw = String.valueOf(c.params().get("module_id"));
                String resolved = LandmarkModuleRegistry.resolveModuleId(raw);
                return resolved != null ? resolved : raw.trim().toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    /**
     * 用户意图比命中的地标别名更具体（如「圣家族大教堂」仅命中别名「大教堂」）时视为近似匹配。
     */
    static boolean isApproximateLandmarkMatch(String intent, String moduleId) {
        if (intent == null || intent.isBlank() || moduleId == null || moduleId.isBlank()) {
            return false;
        }
        String lower = intent.toLowerCase(Locale.ROOT).trim();
        String bestAlias = null;
        for (LandmarkModuleRegistry.LandmarkModule module : LandmarkModuleRegistry.listModules()) {
            if (!moduleId.equalsIgnoreCase(module.moduleId())) continue;
            if (module.aliases() == null) continue;
            for (String alias : module.aliases()) {
                if (alias == null || alias.isBlank()) continue;
                String a = alias.toLowerCase(Locale.ROOT).trim();
                if (a.length() < 2) continue;
                if (lower.contains(a) && (bestAlias == null || a.length() > bestAlias.length())) {
                    bestAlias = a;
                }
            }
            break;
        }
        if (bestAlias == null) {
            return true;
        }
        String remainder = lower.replace(bestAlias, " ").trim();
        remainder = remainder.replaceAll("[的了一座栋生成建造帮我请\\s]+", "").trim();
        return remainder.length() >= 2;
    }

    /** 近似匹配时给玩家的中文提示；精确匹配返回 {@code null}。 */
    public static String playerWarningZh(String intent, String moduleId) {
        if (!isApproximateLandmarkMatch(intent, moduleId)) {
            return null;
        }
        String label = truncate(intent, 40);
        return "未找到「" + label + "」的精确地标模块，已使用近似模板：" + moduleId
                + "（详见日志 [LandmarkMetrics]）";
    }

    private static void log(
            String event,
            ServerPlayerEntity player,
            String intent,
            String matched,
            String policyModule,
            String intentResolved,
            String reason
    ) {
        String playerName = player != null ? player.getName().getString() : "?";
        FormacraftMod.LOGGER.info(
                "[LandmarkMetrics] event={} reason={} player={} requested=\"{}\" matched={} policy={} intent_resolved={} stats=explicit:{} approx:{} override:{}",
                event,
                reason,
                playerName,
                truncate(intent, 80),
                matched,
                policyModule != null ? policyModule : "-",
                intentResolved != null ? intentResolved : "-",
                explicitMatch.get(),
                approxMatch.get(),
                unmatchedIntent.get()
        );
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        String t = text.replace('\n', ' ').trim();
        if (t.length() <= maxLen) return t;
        return t.substring(0, maxLen) + "…";
    }
}
