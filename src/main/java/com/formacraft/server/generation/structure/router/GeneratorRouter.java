package com.formacraft.server.generation.structure.router;

import com.formacraft.common.logging.FcaLog;
import com.formacraft.FormacraftMod;
import com.formacraft.common.archetype.ArchetypeCatalog;
import com.formacraft.common.archetype.ArchetypeRegistry;
import com.formacraft.common.generation.StructureRouteCatalog;
import com.formacraft.common.genome.BuildingGenome;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.generation.routing.GenerationCapability;
import com.formacraft.common.generation.routing.LegacyStructureFallbackPolicy;
import com.formacraft.common.generation.routing.StructureRouteDecision;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingType;
import com.formacraft.common.typology.StructuralTypologyRegistry;
import com.formacraft.server.generation.structure.StructureGenerator;
import com.formacraft.server.generation.structure.blueprint.BlueprintCompilerRegistry;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * BuildingSpec → StructureGenerator 路由层。
 * <p>
 * Phase 3：template / styleProfile / buildingType 路由数据驱动
 * （{@link StructureRouteCatalog} + {@link StructureGeneratorRegistry}）。
 * landmark / archetype 仍走 {@link ArchetypeRegistry}。
 */
public final class GeneratorRouter {

    private static final FcaLog LOG = FcaLog.of("GeneratorRouter");
    private GeneratorRouter() {}

    private static final double ARCHETYPE_STRONG_THRESHOLD = 0.85;

    private static final AtomicReference<String> LAST_MATCHED_REGISTRY_KEY = new AtomicReference<>();

    /** Registry key from the last successful {@link #route(BuildingSpec)} / {@link #routeDecision(BuildingSpec)}. */
    public static String lastMatchedRegistryKey() {
        return LAST_MATCHED_REGISTRY_KEY.get();
    }

    public static StructureRouteDecision routeDecision(BuildingSpec spec) {
        LAST_MATCHED_REGISTRY_KEY.set(null);
        if (spec == null || spec.getType() == null) {
            if (LegacyStructureFallbackPolicy.allow(spec)) {
                LAST_MATCHED_REGISTRY_KEY.set("tower");
                return new StructureRouteDecision(
                        GenerationCapability.SUPPORTED, "tower", "legacy_null_spec", "tower");
            }
            FormacraftMod.LOGGER.warn("BuildingSpec or type is null — no structure route (UNSUPPORTED)");
            return new StructureRouteDecision(
                    GenerationCapability.UNSUPPORTED, null, "null_spec_or_type", null);
        }

        MatchedRoute explicit = routeExplicit(spec);
        if (explicit != null) {
            LAST_MATCHED_REGISTRY_KEY.set(explicit.registryKey());
            return new StructureRouteDecision(
                    GenerationCapability.SUPPORTED, explicit.registryKey(), explicit.reason(), null);
        }

        String typeKey = StructureRouteCatalog.matchBuildingType(spec.getType().name());
        if (typeKey != null) {
            LAST_MATCHED_REGISTRY_KEY.set(typeKey);
            return new StructureRouteDecision(
                    GenerationCapability.SUPPORTED, typeKey, "building_type_catalog", null);
        }

        if (LegacyStructureFallbackPolicy.allow(spec)) {
            String legacy = "house";
            LAST_MATCHED_REGISTRY_KEY.set(legacy);
            if (spec.getType() == BuildingType.CUSTOM) {
                FormacraftMod.LOGGER.warn("CUSTOM building type — legacy HouseGenerator (allow_legacy_fallback)");
            }
            return new StructureRouteDecision(
                    GenerationCapability.SUPPORTED, legacy, "legacy_building_type_fallback", legacy);
        }

        return new StructureRouteDecision(
                GenerationCapability.NEEDS_REPLAN, null, "unmatched_building_type", null);
    }

    public static StructureGenerator route(BuildingSpec spec) {
        StructureRouteDecision decision = routeDecision(spec);
        if (decision.hasGeneratorKey()) {
            return StructureGeneratorRegistry.create(decision.generatorRegistryKey());
        }
        FormacraftMod.LOGGER.error(
                "GeneratorRouter: no generator (capability={} reason={})",
                decision.capability(),
                decision.reasonCode());
        return null;
    }

    /** Routes without building-type catalog / house fallback (for capability analysis). */
    static StructureGenerator routeMatched(BuildingSpec spec) {
        if (spec == null || spec.getType() == null) {
            return null;
        }
        MatchedRoute explicit = routeExplicit(spec);
        return explicit != null ? StructureGeneratorRegistry.create(explicit.registryKey()) : null;
    }

    private record MatchedRoute(String registryKey, String reason) {}

    private static MatchedRoute routeExplicit(BuildingSpec spec) {
        MatchedRoute byStyle = routeByStyleProfileIdKey(spec);
        if (byStyle != null) {
            return byStyle;
        }

        StructureGenerator byAssembly = routeByAssembly(spec);
        if (byAssembly != null) {
            return new MatchedRoute("meta_assembly", "assembly");
        }

        StructureGenerator byBlueprint = routeByBlueprint(spec);
        if (byBlueprint != null) {
            return new MatchedRoute("blueprint_structure", "blueprint");
        }

        MatchedRoute byTypology = routeByTypologyKey(spec);
        if (byTypology != null) {
            return byTypology;
        }

        MatchedRoute byTemplate = routeByTemplateKey(spec);
        if (byTemplate != null) {
            return byTemplate;
        }

        MatchedRoute legacy = routeLegacyLandmarkKey(spec);
        if (legacy != null) {
            return legacy;
        }

        BuildingGenome genome = tryGetGenome(spec);
        if (genome != null && genome.archetype != null && genome.archetype.id != null) {
            double conf = genome.archetype.confidence;
            if (conf >= ARCHETYPE_STRONG_THRESHOLD) {
                MatchedRoute archetype = routeByArchetypeIdKey(genome.archetype.id);
                if (archetype != null) {
                    return archetype;
                }
            }
        }
        return null;
    }

    private static StructureGenerator routeByAssembly(BuildingSpec spec) {
        if (spec == null) return null;
        Map<String, Object> extra = spec.getExtra();
        if (extra == null) return null;
        Object a = extra.get("assembly");
        if (a == null) return null;
        if (a instanceof Map<?, ?>) {
            return StructureGeneratorRegistry.create("meta_assembly");
        }
        try {
            String json = (a instanceof String s) ? s : JsonUtil.toJson(a);
            if (json != null && !json.isBlank()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = JsonUtil.fromJson(json, Map.class);
                if (parsed != null && !parsed.isEmpty()) {
                    return StructureGeneratorRegistry.create("meta_assembly");
                }
            }
        } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
        return null;
    }

    private static StructureGenerator routeByBlueprint(BuildingSpec spec) {
        if (spec == null) return null;
        Map<String, Object> extra = spec.getExtra();
        if (extra == null) return null;
        Object bp = extra.get("blueprint");
        if (bp == null) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (bp instanceof Map<?, ?> mm) ? (Map<String, Object>) mm : null;
            if (m == null) {
                String json = (bp instanceof String s) ? s : JsonUtil.toJson(bp);
                if (json != null && !json.isBlank()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = JsonUtil.fromJson(json, Map.class);
                    m = parsed;
                }
            }
            if (m != null && BlueprintCompilerRegistry.resolve(spec, m) != null) {
                return StructureGeneratorRegistry.create("blueprint_structure");
            }
        } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
        return null;
    }

    private static MatchedRoute routeByTypologyKey(BuildingSpec spec) {
        if (spec == null) return null;
        Map<String, Object> extra = spec.getExtra();
        if (extra == null) return null;

        String typologyId = firstNonBlank(
                stringValue(extra.get("typology_id")),
                stringValue(extra.get("structural_typology"))
        );
        if (typologyId == null) return null;

        String interpreter = StructuralTypologyRegistry.resolveInterpreterId(typologyId);
        if (interpreter != null && StructureGeneratorRegistry.has(interpreter)) {
            return new MatchedRoute(interpreter, "typology");
        }
        return null;
    }

    private static StructureGenerator routeByTypology(BuildingSpec spec) {
        MatchedRoute key = routeByTypologyKey(spec);
        return key != null ? StructureGeneratorRegistry.create(key.registryKey()) : null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static String stringValue(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private static MatchedRoute routeByTemplateKey(BuildingSpec spec) {
        if (spec == null) return null;
        Map<String, Object> extra = spec.getExtra();
        if (extra == null) return null;
        Object t = extra.get("template");
        if (t == null) return null;
        String key = StructureRouteCatalog.matchTemplate(String.valueOf(t));
        return key != null ? new MatchedRoute(key, "template") : null;
    }

    private static StructureGenerator routeByTemplate(BuildingSpec spec) {
        MatchedRoute key = routeByTemplateKey(spec);
        return key != null ? StructureGeneratorRegistry.create(key.registryKey()) : null;
    }

    private static MatchedRoute routeByStyleProfileIdKey(BuildingSpec spec) {
        if (spec == null) return null;
        Map<String, Object> extra = spec.getExtra();
        if (extra == null) return null;
        Object spid = extra.get("styleProfileId");
        if (spid == null) return null;
        String key = StructureRouteCatalog.matchStyleProfile(String.valueOf(spid));
        return key != null ? new MatchedRoute(key, "style_profile") : null;
    }

    private static StructureGenerator routeByStyleProfileId(BuildingSpec spec) {
        MatchedRoute key = routeByStyleProfileIdKey(spec);
        return key != null ? StructureGeneratorRegistry.create(key.registryKey()) : null;
    }

    private static MatchedRoute routeByArchetypeIdKey(String archetypeId) {
        if (archetypeId == null || archetypeId.isBlank()) return null;
        MatchedRoute migrated = routeMigratedLandmarkKey(archetypeId);
        if (migrated != null) return migrated;

        ArchetypeCatalog.ArchetypeDef def = ArchetypeRegistry.getById(archetypeId);
        if (def == null) return null;
        if (Boolean.TRUE.equals(def.researchOnly)) return null;
        return new MatchedRoute(def.generatorId, "archetype");
    }

    private static StructureGenerator routeByArchetypeId(String archetypeId) {
        MatchedRoute key = routeByArchetypeIdKey(archetypeId);
        return key != null ? StructureGeneratorRegistry.create(key.registryKey()) : null;
    }

    private static MatchedRoute routeMigratedLandmarkKey(String landmarkOrArchetypeId) {
        if (landmarkOrArchetypeId == null || landmarkOrArchetypeId.isBlank()) return null;
        String typologyId = StructuralTypologyRegistry.typologyForLegacyModule(landmarkOrArchetypeId.trim());
        if (typologyId == null) return null;
        String interpreter = StructuralTypologyRegistry.resolveInterpreterId(typologyId);
        if (interpreter == null || !StructureGeneratorRegistry.has(interpreter)) return null;
        return new MatchedRoute(interpreter, "migrated_landmark");
    }

    private static StructureGenerator routeMigratedLandmark(String landmarkOrArchetypeId) {
        MatchedRoute key = routeMigratedLandmarkKey(landmarkOrArchetypeId);
        return key != null ? StructureGeneratorRegistry.create(key.registryKey()) : null;
    }

    private static MatchedRoute routeLegacyLandmarkKey(BuildingSpec spec) {
        if (spec == null) return null;
        Map<String, Object> extra = spec.getExtra();
        if (extra == null) return null;
        Object lm = extra.get("landmark");
        if (lm == null) return null;
        String s = String.valueOf(lm).trim();
        if (s.isEmpty()) return null;

        MatchedRoute migrated = routeMigratedLandmarkKey(s);
        if (migrated != null) {
            return migrated;
        }

        ArchetypeCatalog.ArchetypeDef def = ArchetypeRegistry.getById(s);
        if (def == null) def = ArchetypeRegistry.matchByKeyword(s);
        if (def == null) return null;
        if (Boolean.TRUE.equals(def.researchOnly)) return null;
        return new MatchedRoute(def.generatorId, "landmark");
    }

    private static StructureGenerator routeLegacyLandmark(BuildingSpec spec) {
        MatchedRoute key = routeLegacyLandmarkKey(spec);
        return key != null ? StructureGeneratorRegistry.create(key.registryKey()) : null;
    }

    private static BuildingGenome tryGetGenome(BuildingSpec spec) {
        try {
            Map<String, Object> extra = spec.getExtra();
            if (extra == null) return null;
            Object g = extra.get("genome");
            if (g == null) return null;

            String json = (g instanceof String s) ? s : JsonUtil.toJson(g);
            if (json == null || json.isBlank() || "{}".equals(json.trim())) return null;
            return JsonUtil.fromJson(json, BuildingGenome.class);
        } catch (Throwable t) {
            FormacraftMod.LOGGER.warn("Failed to parse BuildingGenome from spec.extra.genome", t);
            return null;
        }
    }
}
