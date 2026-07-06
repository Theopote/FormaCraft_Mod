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
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Typology-first routing telemetry — tracks STRUCTURE/typology hits vs deprecated MODULE fallback.
 * <p>
 * Log prefix: {@code [TypologyMetrics]}. grep example:
 * <pre>{@code
 * Select-String "\[TypologyMetrics\]" logs/latest.log
 * }</pre>
 * <p>
 * Build-time paths (Phase 8.16): {@code compositional_hit}, {@code typology_builder_hit},
 * {@code structure_generator_hit}, {@code module_hit}.
 */
public final class TypologyRoutingMetrics {

    private static final Set<String> COMPOSITIONAL_TYPES = Set.of(
            "MASS_MAIN", "MASS_SECONDARY", "MASS_WING", "SIDE_WING",
            "ROOF", "ENTRANCE", "FACADE_WINDOWS", "FOUNDATION", "PAVING",
            "TERRACE", "BALCONY", "DECOR", "COURTYARD_SPACE", "TOWER",
            "TOWER_BASE", "TOWER_MID", "TOWER_TOP", "WALL", "WALL_SEGMENT",
            "KEEP", "BRIDGE", "CONNECTOR", "PRIMITIVE", "GEOMETRY"
    );

    private static final AtomicLong typologyStructureHits = new AtomicLong();
    private static final AtomicLong typologyComponentHits = new AtomicLong();
    private static final AtomicLong legacyModuleRedirects = new AtomicLong();
    private static final AtomicLong deprecatedModuleUse = new AtomicLong();
    private static final AtomicLong compositionalHits = new AtomicLong();
    private static final AtomicLong typologyBuilderHits = new AtomicLong();
    private static final AtomicLong structureGeneratorHits = new AtomicLong();
    private static final AtomicLong moduleHits = new AtomicLong();

    private TypologyRoutingMetrics() {}

    public static void recordTypologyComponentHit(String typologyId) {
        recordTypologyBuilderHit(typologyId, "component_router");
    }

    public static void recordTypologyBuilderHit(String typologyId, String source) {
        if (typologyId == null || typologyId.isBlank()) return;
        typologyComponentHits.incrementAndGet();
        typologyBuilderHits.incrementAndGet();
        log("typology_builder_hit", null, "-", typologyId.trim(), "-", source);
    }

    public static void recordCompositionalHit(String componentType, String source) {
        compositionalHits.incrementAndGet();
        log("compositional_hit", null, componentType != null ? componentType : "-", "-", "-", source);
    }

    public static void recordStructureGeneratorHit(String generatorKey, String source) {
        if (generatorKey == null || generatorKey.isBlank()) return;
        structureGeneratorHits.incrementAndGet();
        log("structure_generator_hit", null, "-", "-", generatorKey.trim(), source);
    }

    public static void recordModuleHit(String moduleId, String source) {
        if (moduleId == null || moduleId.isBlank()) return;
        moduleHits.incrementAndGet();
        log("module_hit", null, "-", "-", moduleId.trim(), source);
    }

    public static void recordLegacyRedirect(String legacyModuleId, String typologyId) {
        if (legacyModuleId == null || typologyId == null) return;
        legacyModuleRedirects.incrementAndGet();
        log("legacy_redirect", null, "-", typologyId, legacyModuleId, "migration_map");
    }

    public static void recordFromPlan(ServerPlayerEntity player, FormaRequest req, LlmPlan plan) {
        if (plan == null || plan.components() == null) return;
        String intent = BuildingSpecRoutingPolicy.userIntentText(req);

        boolean hasTypology = false;
        boolean hasModule = false;
        boolean hasStructureGenerator = false;
        boolean hasCompositional = false;

        for (Component c : plan.components()) {
            if (c == null) continue;

            String typologyId = TypologyComponentRouter.extractTypologyId(c);
            if (typologyId != null && !typologyId.isBlank()) {
                hasTypology = true;
                typologyStructureHits.incrementAndGet();
                log("typology_structure_hit", player, intent, typologyId, "-", "llm_plan");
            }

            String legacyModule = extractLandmarkModuleId(c);
            if (legacyModule != null) {
                String migrated = StructuralTypologyRegistry.typologyForLegacyModule(legacyModule);
                if (migrated != null && !migrated.isBlank()) {
                    deprecatedModuleUse.incrementAndGet();
                    log("deprecated_module_use", player, intent, migrated, legacyModule, "llm_plan");
                } else if (isModuleComponent(c)) {
                    hasModule = true;
                }
            }

            if (!hasTypology && hasStructureGeneratorHint(c)) {
                hasStructureGenerator = true;
            }

            if (isCompositionalComponent(c)) {
                hasCompositional = true;
            }
        }

        recordPlanPrimaryPath(player, intent, hasTypology, hasModule, hasStructureGenerator, hasCompositional);
    }

    private static void recordPlanPrimaryPath(
            ServerPlayerEntity player,
            String intent,
            boolean hasTypology,
            boolean hasModule,
            boolean hasStructureGenerator,
            boolean hasCompositional
    ) {
        if (hasTypology) {
            return;
        }
        if (hasModule) {
            log("module_hit", player, intent, "-", "-", "llm_plan");
            moduleHits.incrementAndGet();
            return;
        }
        if (hasStructureGenerator) {
            log("structure_generator_hit", player, intent, "-", "-", "llm_plan");
            structureGeneratorHits.incrementAndGet();
            return;
        }
        if (hasCompositional) {
            log("compositional_hit", player, intent, "-", "-", "llm_plan");
            compositionalHits.incrementAndGet();
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

    public static Snapshot snapshot() {
        long compositional = compositionalHits.get();
        long typologyBuilder = typologyBuilderHits.get();
        long structureGen = structureGeneratorHits.get();
        long module = moduleHits.get();
        long routed = compositional + typologyBuilder + structureGen + module;
        double typologyFirstRate = routed > 0
                ? 100.0 * (compositional + typologyBuilder) / routed
                : 0.0;
        double structureGeneratorRate = routed > 0
                ? 100.0 * (structureGen + module) / routed
                : 0.0;
        return new Snapshot(
                compositional,
                typologyBuilder,
                structureGen,
                module,
                typologyStructureHits.get(),
                legacyModuleRedirects.get(),
                deprecatedModuleUse.get(),
                typologyFirstRate,
                structureGeneratorRate
        );
    }

    static boolean isCompositionalComponent(Component c) {
        if (c == null) return false;
        String type = normalizeType(c.componentType());
        if ("ASSEMBLY".equals(type)) return true;
        return COMPOSITIONAL_TYPES.contains(type);
    }

    public static boolean isModuleComponent(Component c) {
        if (c == null) return false;
        return "MODULE".equals(normalizeType(c.componentType()));
    }

    static boolean hasStructureGeneratorHint(Component c) {
        if (c == null) return false;
        if (hasFeaturePrefix(c, "structure_generator:")) return true;
        if (hasFeaturePrefix(c, "landmark:") || hasFeaturePrefix(c, "module:")) {
            return !TypologyComponentRouter.hasTypologyHint(c);
        }
        String type = normalizeType(c.componentType());
        if ("STRUCTURE".equals(type) && !TypologyComponentRouter.hasTypologyHint(c)) {
            return true;
        }
        return false;
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

    private static boolean hasFeaturePrefix(Component component, String prefix) {
        if (component == null || prefix == null) return false;
        List<String> features = component.features();
        if (features == null || features.isEmpty()) return false;
        String p = prefix.toLowerCase(Locale.ROOT);
        for (String feature : features) {
            if (feature == null) continue;
            if (feature.toLowerCase(Locale.ROOT).startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeType(String componentType) {
        if (componentType == null) return "";
        return componentType.trim().toUpperCase(Locale.ROOT);
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
                "[TypologyMetrics] event={} source={} player={} intent=\"{}\" typology={} legacy_module={} {}",
                event,
                source,
                playerName,
                truncate(intent, 80),
                typologyId != null ? typologyId : "-",
                legacyModule != null ? legacyModule : "-",
                snapshot()
        );
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        String t = text.replace('\n', ' ').trim();
        if (t.length() <= maxLen) return t;
        return t.substring(0, maxLen) + "…";
    }

    public record Snapshot(
            long compositionalHits,
            long typologyBuilderHits,
            long structureGeneratorHits,
            long moduleHits,
            long typologyStructurePlanHits,
            long legacyRedirects,
            long deprecatedModuleUses,
            double typologyFirstRatePercent,
            double structureGeneratorRatePercent
    ) {
        @Override
        public String toString() {
            return String.format(
                    Locale.ROOT,
                    "stats=compositional:%d typology_builder:%d structure_generator:%d module:%d "
                            + "plan_typology:%d redirect:%d deprecated:%d "
                            + "typology_first_rate=%.1f%% structure_generator_rate=%.1f%%",
                    compositionalHits,
                    typologyBuilderHits,
                    structureGeneratorHits,
                    moduleHits,
                    typologyStructurePlanHits,
                    legacyRedirects,
                    deprecatedModuleUses,
                    typologyFirstRatePercent,
                    structureGeneratorRatePercent
            );
        }
    }
}
