package com.formacraft.server.generator.router;

import com.formacraft.FormacraftMod;
import com.formacraft.common.archetype.ArchetypeCatalog;
import com.formacraft.common.archetype.ArchetypeRegistry;
import com.formacraft.common.genome.BuildingGenome;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingType;
import com.formacraft.server.generator.*;

import java.util.Map;

/**
 * GeneratorRouter：BuildingGenome -> Generator 路由层（v1 骨架）
 *
 * 设计目标：
 * - AI/后端负责生成 BuildingGenome（挂载在 BuildingSpec.extra.genome）
 * - 路由层只做“选择题”：按优先级选出合适 generator
 * - 具体落块仍由 ConcreteGenerator 完成
 *
 * IMPORTANT：当前项目仍以 BuildingSpec 为可执行输入；若无 genome，则行为与旧版一致（按 type 路由）。
 */
public final class GeneratorRouter {
    private GeneratorRouter() {}

    /** archetype >= 0.85 视为强原型，优先走地标/专用 generator */
    private static final double ARCHETYPE_STRONG_THRESHOLD = 0.85;

    public static StructureGenerator route(BuildingSpec spec) {
        if (spec == null || spec.getType() == null) {
            FormacraftMod.LOGGER.warn("BuildingSpec or type is null, using default TowerGenerator");
            return new TowerGenerator();
        }

        // 0) template-based routing (deterministic templates)
        StructureGenerator byTemplate = routeByTemplate(spec);
        if (byTemplate != null) return byTemplate;

        // 0) legacy landmark flag (keeps current behavior)
        StructureGenerator legacy = routeLegacyLandmark(spec);
        if (legacy != null) return legacy;

        BuildingGenome genome = tryGetGenome(spec);

        // 1) archetype -> landmark (strong prototype)
        if (genome != null && genome.archetype != null && genome.archetype.id != null) {
            double conf = genome.archetype.confidence;
            if (conf >= ARCHETYPE_STRONG_THRESHOLD) {
                StructureGenerator g = routeByArchetypeId(genome.archetype.id);
                if (g != null) return g;
            }
        }

        // 2) (reserved) topology/structure routing
        // v1 skeleton: keep fallback to old behavior unless archetype strongly indicates a known family.

        // 3) fallback: old type-based routing
        BuildingType type = spec.getType();
        return switch (type) {
            case TOWER -> new TowerGenerator();
            case HOUSE -> new HouseGenerator();
            case BRIDGE -> new BridgeGenerator();
            case WALL -> new WallGenerator();
            case CASTLE -> new HouseGenerator();
            case CUSTOM -> {
                FormacraftMod.LOGGER.warn("CUSTOM building type not yet implemented, using HouseGenerator");
                yield new HouseGenerator();
            }
        };
    }

    private static StructureGenerator routeByTemplate(BuildingSpec spec) {
        if (spec == null) return null;
        Map<String, Object> extra = spec.getExtra();
        if (extra == null) return null;
        Object t = extra.get("template");
        if (t == null) return null;
        String s = String.valueOf(t).trim().toLowerCase();
        if (s.isEmpty()) return null;
        if (s.contains("mingqing_courtyard") || s.contains("mingqing")) {
            return new MingQingCourtyardGenerator();
        }
        if (s.contains("castle_compound") || s.contains("castle")) {
            return new CastleCompoundGenerator();
        }
        if (s.contains("office_district") || s.contains("office_park") || s.contains("office")) {
            return new OfficeDistrictGenerator();
        }
        if (s.contains("office_block")) {
            return new OfficeBlockGenerator();
        }
        return null;
    }

    private static StructureGenerator routeByArchetypeId(String archetypeId) {
        if (archetypeId == null || archetypeId.isBlank()) return null;
        ArchetypeCatalog.ArchetypeDef def = ArchetypeRegistry.getById(archetypeId);
        if (def == null) return null;
        return ArchetypeGeneratorFactory.fromGeneratorId(def.generatorId);
    }

    private static StructureGenerator routeLegacyLandmark(BuildingSpec spec) {
        if (spec == null) return null;
        Map<String, Object> extra = spec.getExtra();
        if (extra == null) return null;
        Object lm = extra.get("landmark");
        if (lm == null) return null;
        String s = String.valueOf(lm).trim();
        if (s.isEmpty()) return null;

        // legacy field usually already uses id, but allow keyword/alias match as well.
        ArchetypeCatalog.ArchetypeDef def = ArchetypeRegistry.getById(s);
        if (def == null) def = ArchetypeRegistry.matchByKeyword(s);
        if (def == null) return null;
        return ArchetypeGeneratorFactory.fromGeneratorId(def.generatorId);
    }

    private static BuildingGenome tryGetGenome(BuildingSpec spec) {
        try {
            Map<String, Object> extra = spec.getExtra();
            if (extra == null) return null;
            Object g = extra.get("genome");
            if (g == null) return null;

            // extra.genome 通常是 Map（来自 JSON 反序列化）；用 Gson 重新解析为强类型
            String json = (g instanceof String s) ? s : JsonUtil.toJson(g);
            if (json == null || json.isBlank() || "{}".equals(json.trim())) return null;
            return JsonUtil.fromJson(json, BuildingGenome.class);
        } catch (Throwable t) {
            FormacraftMod.LOGGER.warn("Failed to parse BuildingGenome from spec.extra.genome", t);
            return null;
        }
    }

    // NOTE: old hasLandmark helper removed in favor of data-driven ArchetypeRegistry
}


