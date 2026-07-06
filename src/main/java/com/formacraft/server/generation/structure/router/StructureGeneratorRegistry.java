package com.formacraft.server.generation.structure.router;

import com.formacraft.FormacraftMod;
import com.formacraft.server.generation.structure.*;
import com.formacraft.server.generation.structure.blueprint.BlueprintStructureGenerator;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 整栋生成器统一注册表（Phase 3）。
 * <p>
 * 所有 {@code generatorKey}（来自 {@link com.formacraft.common.generation.StructureRouteCatalog}、
 * {@link ArchetypeRegistry}、archetypes_v1.json）在此实例化。
 */
public final class StructureGeneratorRegistry {

    private static final Map<String, Supplier<StructureGenerator>> REGISTRY = new HashMap<>();

    static {
        // Building-type fallbacks
        register("tower", TowerGenerator::new);
        register("house", HouseGenerator::new);
        register("bridge", BridgeGenerator::new);
        register("wall", WallGenerator::new);

        // Meta / blueprint wrappers
        register("meta_assembly", MetaAssemblyGenerator::new);
        register("blueprint_structure", BlueprintStructureGenerator::new);

        // Landmarks & templates (archetypes + structure_routes_v1.json)
        register("tulou", TulouGenerator::new);
        register("eiffel_tower", EiffelTowerGenerator::new);
        register("temple_of_heaven", TempleOfHeavenGenerator::new);
        register("great_wall", GreatWallGenerator::new);
        register("golden_gate_bridge", GoldenGateBridgeGenerator::new);
        register("giant_wild_goose_pagoda", GiantWildGoosePagodaGenerator::new);
        register("famen_pagoda", FamenPagodaGenerator::new);
        register("foguang_temple_hall", FoguangTempleHallGenerator::new);
        register("castle_compound", CastleCompoundGenerator::new);
        register("office_district", OfficeDistrictGenerator::new);
        register("mingqing_courtyard", MingQingCourtyardGenerator::new);
        register("office_block", OfficeBlockGenerator::new);
        register("cyberpunk_megablock", CyberpunkMegaBlockGenerator::new);
        register("elven_treehouse", ElvenTreehouseGenerator::new);
        register("elven_mushroom_house", ElvenMushroomHouseGenerator::new);
        register("elven_flower_house", ElvenFlowerHouseGenerator::new);
        register("jiangnan_water_town", JiangnanWaterTownGenerator::new);
        register("steampunk_airship", SteampunkAirshipGenerator::new);
        register("steampunk_factory", SteampunkFactoryGenerator::new);
        register("steampunk_airship_dock", SteampunkAirshipDockGenerator::new);
        register("japanese_shrine", JapaneseShrineGenerator::new);
        register("japanese_castle_keep", JapaneseCastleKeepGenerator::new);
        register("japanese_tea_house", JapaneseTeaHouseGenerator::new);
        register("pantheon", PantheonGenerator::new);
        register("parthenon", ParthenonTempleGenerator::new);
        register("gothic_cathedral", GothicCathedralGenerator::new);
        register("modern_skyscraper", ModernSkyscraperGenerator::new);
        register("modern_office_campus", ModernOfficeCampusGenerator::new);
        register("modern_bauhaus_rowhouse", ModernBauhausRowhouseGenerator::new);
        register("brutalist_megastructure", BrutalistMegastructureGenerator::new);
        register("parametric_deconstructivism", ParametricDeconstructivismGenerator::new);
        register("birds_nest_stadium", BirdsNestStadiumGenerator::new);
    }

    private StructureGeneratorRegistry() {}

    public static void register(String generatorKey, Supplier<StructureGenerator> factory) {
        if (generatorKey == null || factory == null) return;
        REGISTRY.put(generatorKey.trim().toLowerCase(Locale.ROOT), factory);
    }

    public static StructureGenerator create(String generatorKey) {
        if (generatorKey == null || generatorKey.isBlank()) {
            return null;
        }
        Supplier<StructureGenerator> factory = REGISTRY.get(generatorKey.trim().toLowerCase(Locale.ROOT));
        if (factory == null) {
            FormacraftMod.LOGGER.warn("StructureGeneratorRegistry: unknown generatorKey '{}'", generatorKey);
            return null;
        }
        return factory.get();
    }

    public static boolean has(String generatorKey) {
        if (generatorKey == null || generatorKey.isBlank()) return false;
        return REGISTRY.containsKey(generatorKey.trim().toLowerCase(Locale.ROOT));
    }
}
