package com.formacraft.common.network.metrics;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.routing.BuildingSpecRoutingPolicy;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.common.typology.StructuralTypologyRegistry;
import com.formacraft.common.typology.TypologyComponentRouter;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Typology-first routing telemetry — tracks STRUCTURE/typology hits vs deprecated MODULE fallback.
 * <p>
 * Log prefix: {@code [TypologyMetrics]}. grep example:
 * <pre>{@code
 * Select-String "\[TypologyMetrics\]" logs/latest.log
 * }</pre>
 */
public final class TypologyRoutingMetrics {

    private static final AtomicLong typologyStructureHits = new AtomicLong();
    private static final AtomicLong typologyComponentHits = new AtomicLong();
    private static final AtomicLong legacyModuleRedirects = new AtomicLong();
    private static final AtomicLong deprecatedModuleUse = new AtomicLong();

    private TypologyRoutingMetrics() {}

    public static void recordTypologyComponentHit(String typologyId) {
        if (typologyId == null || typologyId.isBlank()) return;
        typologyComponentHits.incrementAndGet();
        log("typology_component_hit", null, "-", typologyId.trim(), "-", "component_router");
    }

    public static void recordLegacyRedirect(String legacyModuleId, String typologyId) {
        if (legacyModuleId == null || typologyId == null) return;
        legacyModuleRedirects.incrementAndGet();
        log("legacy_redirect", null, "-", typologyId, legacyModuleId, "migration_map");
    }

    public static void recordFromPlan(ServerPlayerEntity player, FormaRequest req, LlmPlan plan) {
        if (plan == null || plan.components() == null) return;
        String intent = BuildingSpecRoutingPolicy.userIntentText(req);

        for (Component c : plan.components()) {
            if (c == null) continue;
            String typologyId = TypologyComponentRouter.extractTypologyId(c);
            if (typologyId != null && !typologyId.isBlank()) {
                typologyStructureHits.incrementAndGet();
                log("typology_structure_hit", player, intent, typologyId, "-", "llm_plan");
            }

            String legacyModule = extractLandmarkModuleId(c);
            if (legacyModule != null) {
                String migrated = StructuralTypologyRegistry.typologyForLegacyModule(legacyModule);
                if (migrated != null && !migrated.isBlank()) {
                    deprecatedModuleUse.incrementAndGet();
                    log("deprecated_module_use", player, intent, migrated, legacyModule, "llm_plan");
                }
            }
        }
    }

    public static String playerDeprecationWarningZh(String legacyModuleId) {
        String typology = StructuralTypologyRegistry.typologyForLegacyModule(legacyModuleId);
        if (typology == null || typology.isBlank()) {
            return null;
        }
        return "地标模块 " + legacyModuleId + " 已迁移至结构类型 " + typology
                + "；将自动使用 typology 参数化路径（详见日志 [TypologyMetrics]）";
    }

    private static String extractLandmarkModuleId(Component c) {
        if (c == null) return null;
        List<String> features = c.features();
        if (features != null) {
            for (String feature : features) {
                if (feature == null) continue;
                String lower = feature.toLowerCase(Locale.ROOT);
                if (lower.startsWith("landmark:")) {
                    return feature.substring("landmark:".length()).trim();
                }
                if (lower.startsWith("module:")) {
                    return feature.substring("module:".length()).trim();
                }
            }
        }
        if (c.params() != null && c.params().get("module_id") != null) {
            return String.valueOf(c.params().get("module_id")).trim();
        }
        return null;
    }

    private static void log(
            String event,
            ServerPlayerEntity player,
            String intent,
            String typologyId,
            String legacyModule,
            String source
    ) {
        String playerName = player != null ? player.getName().getString() : "-";
        FormacraftMod.LOGGER.info(
                "[TypologyMetrics] event={} source={} player={} intent=\"{}\" typology={} legacy_module={} "
                        + "stats=structure:{} component:{} redirect:{} deprecated:{}",
                event,
                source,
                playerName,
                truncate(intent, 80),
                typologyId != null ? typologyId : "-",
                legacyModule != null ? legacyModule : "-",
                typologyStructureHits.get(),
                typologyComponentHits.get(),
                legacyModuleRedirects.get(),
                deprecatedModuleUse.get()
        );
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        String t = text.replace('\n', ' ').trim();
        if (t.length() <= maxLen) return t;
        return t.substring(0, maxLen) + "…";
    }
}
