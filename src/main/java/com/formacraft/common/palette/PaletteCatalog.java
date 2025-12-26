package com.formacraft.common.palette;

import java.util.List;
import java.util.Map;

/**
 * PaletteCatalog (v1):
 * paletteId -> semanticPart -> weighted block ids.
 *
 * JSON format:
 * {
 *   "version": "1.0",
 *   "palettes": {
 *     "PALETTE_STONE_FORTRESS_A": {
 *       "meta": { "display_name": "...", "tags": [...] },
 *       "parts": {
 *         "Wall_Base": [
 *           { "id": "minecraft:stone_bricks", "weight": 70 },
 *           { "id": "minecraft:cracked_stone_bricks", "weight": 15 }
 *         ]
 *       }
 *     }
 *   }
 * }
 */
public final class PaletteCatalog {
    public String version = "1.0";
    public Map<String, PaletteDef> palettes = Map.of();

    public static final class PaletteDef {
        public Meta meta = new Meta();
        public Map<String, List<WeightedBlock>> parts = Map.of();
    }

    public static final class Meta {
        public String display_name = "";
        public List<String> tags = List.of();
        public String description = "";
    }

    public static final class WeightedBlock {
        public String id;
        public int weight = 1;
    }
}


