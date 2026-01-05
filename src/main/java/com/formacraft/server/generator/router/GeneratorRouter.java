package com.formacraft.server.generator.router;

import com.formacraft.FormacraftMod;
import com.formacraft.common.archetype.ArchetypeCatalog;
import com.formacraft.common.archetype.ArchetypeRegistry;
import com.formacraft.common.genome.BuildingGenome;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingType;
import com.formacraft.server.generator.blueprint.BlueprintCompilerRegistry;
import com.formacraft.server.generator.blueprint.BlueprintStructureGenerator;
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

        // 0) styleProfileId-based routing (data-driven style wants a dedicated generator)
        // NOTE: Check this BEFORE assembly routing, so specialized generators (like ParametricDeconstructivismGenerator)
        // take precedence over generic MetaAssemblyGenerator when styleProfileId matches.
        StructureGenerator byStyleProfileId = routeByStyleProfileId(spec);
        if (byStyleProfileId != null) return byStyleProfileId;

        // 0) meta-assembly routing (first-principles parametric engine, opt-in via extra.assembly)
        StructureGenerator byAssembly = routeByAssembly(spec);
        if (byAssembly != null) return byAssembly;

        // 0) blueprint-based routing (semantic components -> plan -> blocks)
        // This is the primary path when LLM provides a blueprint.
        StructureGenerator byBlueprint = routeByBlueprint(spec);
        if (byBlueprint != null) return byBlueprint;

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
            case HOUSE, CASTLE -> new HouseGenerator();
            case BRIDGE -> new BridgeGenerator();
            case WALL -> new WallGenerator();
            case CUSTOM -> {
                FormacraftMod.LOGGER.warn("CUSTOM building type not yet implemented, using HouseGenerator");
                yield new HouseGenerator();
            }
        };
    }

    private static StructureGenerator routeByAssembly(BuildingSpec spec) {
        if (spec == null) return null;
        Map<String, Object> extra = spec.getExtra();
        if (extra == null) return null;
        Object a = extra.get("assembly");
        if (a == null) return null;
        // Minimal check: should be a map or a json string that parses into a map (engine will validate deeper).
        if (a instanceof Map<?, ?>) return new MetaAssemblyGenerator();
        try {
            String json = (a instanceof String s) ? s : JsonUtil.toJson(a);
            if (json != null && !json.isBlank()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = JsonUtil.fromJson(json, Map.class);
                if (parsed != null && !parsed.isEmpty()) return new MetaAssemblyGenerator();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static StructureGenerator routeByBlueprint(BuildingSpec spec) {
        if (spec == null) return null;
        Map<String, Object> extra = spec.getExtra();
        if (extra == null) return null;
        Object bp = extra.get("blueprint");
        if (bp == null) return null;
        // If compiler can be resolved, use the generic blueprint generator.
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (bp instanceof Map<?, ?> mm) ? (Map<String, Object>) mm : null;
            if (m == null) {
                // try parse via json
                String json = (bp instanceof String s) ? s : JsonUtil.toJson(bp);
                if (json != null && !json.isBlank()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = JsonUtil.fromJson(json, Map.class);
                    m = parsed;
                }
            }
            if (m != null && BlueprintCompilerRegistry.resolve(spec, m) != null) {
                return new BlueprintStructureGenerator();
            }
        } catch (Throwable ignored) {}
        return null;
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
        if (s.contains("cyberpunk_megablock") || s.contains("cyber_megablock") || s.contains("cyber_slum_tower")
                || s.contains("夜城") || s.contains("赛博巨构") || s.contains("贫民窟塔")) {
            return new CyberpunkMegaBlockGenerator();
        }
        if (s.contains("elven_treehouse") || s.contains("treehouse") || s.contains("elf_treehouse")
                || s.contains("精灵树屋") || s.contains("树屋") || s.contains("树上小屋")) {
            return new ElvenTreehouseGenerator();
        }
        if (s.contains("mushroom_house") || s.contains("mushroom_hut") || s.contains("mushroomhouse")
                || s.contains("蘑菇屋") || s.contains("蘑菇房") || s.contains("蘑菇小屋")) {
            return new ElvenMushroomHouseGenerator();
        }
        if (s.contains("flower_house") || s.contains("flower_hut") || s.contains("flowerhome")
                || s.contains("花朵屋") || s.contains("花屋") || s.contains("花房") || s.contains("花朵小屋")) {
            return new ElvenFlowerHouseGenerator();
        }
        if (s.contains("jiangnan_water_town") || s.contains("water_town") || s.contains("watertown")) {
            return new JiangnanWaterTownGenerator();
        }
        if (s.contains("steampunk_airship") || s.contains("airship") || s.contains("zeppelin") || s.contains("飞艇") || s.contains("飞船")) {
            return new SteampunkAirshipGenerator();
        }
        if (s.contains("steampunk_factory") || s.contains("factory_steampunk") || s.contains("steam_factory") || s.contains("蒸汽工厂") || s.contains("工厂")) {
            return new SteampunkFactoryGenerator();
        }
        if (s.contains("airship_dock") || s.contains("steampunk_dock") || s.contains("airship_port") || s.contains("dock") || s.contains("空港") || s.contains("码头") || s.contains("飞艇码头")) {
            return new SteampunkAirshipDockGenerator();
        }
        if (s.contains("japanese_shrine") || s.contains("shrine") || s.contains("jinja") || s.contains("torii")) {
            return new JapaneseShrineGenerator();
        }
        if (s.contains("japanese_castle_keep") || s.contains("castle_keep") || s.contains("tenshu") || s.contains("天守")) {
            return new JapaneseCastleKeepGenerator();
        }
        if (s.contains("japanese_tea_house") || s.contains("tea_house") || s.contains("teahouse") || s.contains("chashitsu") || s.contains("茶室")) {
            return new JapaneseTeaHouseGenerator();
        }
        if (s.contains("pantheon") || s.contains("万神殿") || s.contains("dome_temple")) {
            return new PantheonGenerator();
        }
        if (s.contains("parthenon") || s.contains("帕特农") || s.contains("classical_temple") || s.contains("greco_roman_temple")) {
            return new ParthenonTempleGenerator();
        }
        if (s.contains("gothic_cathedral") || s.contains("cathedral") || s.contains("notre_dame") || s.contains("cologne") || s.contains("哥特")) {
            return new GothicCathedralGenerator();
        }
        if (s.contains("modern_skyscraper") || s.contains("highrise") || s.contains("skyscraper") || s.contains("摩天") || s.contains("摩天楼")) {
            return new ModernSkyscraperGenerator();
        }
        if (s.contains("modern_office_campus") || s.contains("office_campus") || s.contains("office_park") || s.contains("campus") || s.contains("园区")) {
            return new ModernOfficeCampusGenerator();
        }
        if (s.contains("bauhaus_rowhouse") || s.contains("bauhaus") || s.contains("rowhouse") || s.contains("townhouse") || s.contains("terrace") || s.contains("联排") || s.contains("包豪斯")) {
            return new ModernBauhausRowhouseGenerator();
        }
        if (s.contains("brutalism_megastructure") || s.contains("soviet_megastructure") || s.contains("brutalism") || s.contains("粗野") || s.contains("巨构")) {
            return new BrutalistMegastructureGenerator();
        }
        if (s.contains("deconstructivism") || s.contains("parametric") || s.contains("zaha") || s.contains("gehry") || s.contains("guggenheim") || s.contains("解构") || s.contains("参数化")) {
            return new ParametricDeconstructivismGenerator();
        }
        return null;
    }

    private static StructureGenerator routeByStyleProfileId(BuildingSpec spec) {
        if (spec == null) return null;
        Map<String, Object> extra = spec.getExtra();
        if (extra == null) return null;
        Object spid = extra.get("styleProfileId");
        if (spid == null) return null;
        String s = String.valueOf(spid).trim();
        if (s.isEmpty()) return null;
        // dedicated style -> generator mapping (v1)
        if (s.equals("Chinese_Vernacular_Jiangnan_WaterTown")) return new JiangnanWaterTownGenerator();
        if (s.equals("Gothic_Cathedral")) return new GothicCathedralGenerator();
        if (s.equals("Brutalism")) return new BrutalistMegastructureGenerator();
        if (s.equals("Deconstructivism_Zaha")) return new ParametricDeconstructivismGenerator();
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


