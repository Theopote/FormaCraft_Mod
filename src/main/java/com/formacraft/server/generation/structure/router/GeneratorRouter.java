package com.formacraft.server.generation.structure.router;

import com.formacraft.common.logging.FcaLog;
import com.formacraft.FormacraftMod;
import com.formacraft.common.archetype.ArchetypeCatalog;
import com.formacraft.common.archetype.ArchetypeRegistry;
import com.formacraft.common.generation.StructureRouteCatalog;
import com.formacraft.common.genome.BuildingGenome;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingType;
import com.formacraft.server.generation.structure.StructureGenerator;
import com.formacraft.server.generation.structure.blueprint.BlueprintCompilerRegistry;

import java.util.Map;

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

    public static StructureGenerator route(BuildingSpec spec) {
        if (spec == null || spec.getType() == null) {
            FormacraftMod.LOGGER.warn("BuildingSpec or type is null, using default TowerGenerator");
            return StructureGeneratorRegistry.create("tower");
        }

        StructureGenerator byStyleProfileId = routeByStyleProfileId(spec);
        if (byStyleProfileId != null) return byStyleProfileId;

        StructureGenerator byAssembly = routeByAssembly(spec);
        if (byAssembly != null) return byAssembly;

        StructureGenerator byBlueprint = routeByBlueprint(spec);
        if (byBlueprint != null) return byBlueprint;

        StructureGenerator byTemplate = routeByTemplate(spec);
        if (byTemplate != null) return byTemplate;

        StructureGenerator legacy = routeLegacyLandmark(spec);
        if (legacy != null) return legacy;

        BuildingGenome genome = tryGetGenome(spec);
        if (genome != null && genome.archetype != null && genome.archetype.id != null) {
            double conf = genome.archetype.confidence;
            if (conf >= ARCHETYPE_STRONG_THRESHOLD) {
                StructureGenerator g = routeByArchetypeId(genome.archetype.id);
                if (g != null) return g;
            }
        }

        return routeByBuildingType(spec.getType());
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

    private static StructureGenerator routeByTemplate(BuildingSpec spec) {
        if (spec == null) return null;
        Map<String, Object> extra = spec.getExtra();
        if (extra == null) return null;
        Object t = extra.get("template");
        if (t == null) return null;
        String key = StructureRouteCatalog.matchTemplate(String.valueOf(t));
        return key != null ? StructureGeneratorRegistry.create(key) : null;
    }

    private static StructureGenerator routeByStyleProfileId(BuildingSpec spec) {
        if (spec == null) return null;
        Map<String, Object> extra = spec.getExtra();
        if (extra == null) return null;
        Object spid = extra.get("styleProfileId");
        if (spid == null) return null;
        String key = StructureRouteCatalog.matchStyleProfile(String.valueOf(spid));
        return key != null ? StructureGeneratorRegistry.create(key) : null;
    }

    private static StructureGenerator routeByArchetypeId(String archetypeId) {
        if (archetypeId == null || archetypeId.isBlank()) return null;
        ArchetypeCatalog.ArchetypeDef def = ArchetypeRegistry.getById(archetypeId);
        if (def == null) return null;
        return StructureGeneratorRegistry.create(def.generatorId);
    }

    private static StructureGenerator routeLegacyLandmark(BuildingSpec spec) {
        if (spec == null) return null;
        Map<String, Object> extra = spec.getExtra();
        if (extra == null) return null;
        Object lm = extra.get("landmark");
        if (lm == null) return null;
        String s = String.valueOf(lm).trim();
        if (s.isEmpty()) return null;

        ArchetypeCatalog.ArchetypeDef def = ArchetypeRegistry.getById(s);
        if (def == null) def = ArchetypeRegistry.matchByKeyword(s);
        if (def == null) return null;
        return StructureGeneratorRegistry.create(def.generatorId);
    }

    private static StructureGenerator routeByBuildingType(BuildingType type) {
        if (type == null) {
            return StructureGeneratorRegistry.create("tower");
        }
        String key = StructureRouteCatalog.matchBuildingType(type.name());
        StructureGenerator generator = key != null ? StructureGeneratorRegistry.create(key) : null;
        if (generator != null) {
            return generator;
        }
        if (type == BuildingType.CUSTOM) {
            FormacraftMod.LOGGER.warn("CUSTOM building type not yet implemented, using HouseGenerator");
        }
        return StructureGeneratorRegistry.create("house");
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
