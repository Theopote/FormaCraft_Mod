package com.formacraft.server.generator.router;

import com.formacraft.server.generator.*;

/**
 * Maps generatorId (from archetypes_v1.json) to concrete generator instances.
 *
 * Note: Adding a new archetype that reuses an existing generatorId requires NO code change.
 * Adding a brand new generator implementation does require adding a new mapping here.
 */
public final class ArchetypeGeneratorFactory {
    private ArchetypeGeneratorFactory() {}

    public static StructureGenerator fromGeneratorId(String generatorIdLower) {
        if (generatorIdLower == null || generatorIdLower.isBlank()) return null;
        String id = generatorIdLower.trim().toLowerCase();
        return switch (id) {
            case "tulou" -> new TulouGenerator();
            case "eiffel_tower" -> new EiffelTowerGenerator();
            case "temple_of_heaven" -> new TempleOfHeavenGenerator();
            case "great_wall" -> new GreatWallGenerator();
            case "golden_gate_bridge" -> new GoldenGateBridgeGenerator();
            case "giant_wild_goose_pagoda" -> new GiantWildGoosePagodaGenerator();
            case "castle_compound" -> new CastleCompoundGenerator();
            case "office_district" -> new OfficeDistrictGenerator();
            default -> null;
        };
    }
}


