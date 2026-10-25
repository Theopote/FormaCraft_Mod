package com.formacraft.server.edit;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Maps player/AI material phrases to a canonical block id (planks/base). */
public final class RoofMaterialIntent {
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("dark_oak", "minecraft:dark_oak_planks"),
            Map.entry("dark oak", "minecraft:dark_oak_planks"),
            Map.entry("深色橡木", "minecraft:dark_oak_planks"),
            Map.entry("黑橡木", "minecraft:dark_oak_planks"),
            Map.entry("oak", "minecraft:oak_planks"),
            Map.entry("橡木", "minecraft:oak_planks"),
            Map.entry("spruce", "minecraft:spruce_planks"),
            Map.entry("云杉", "minecraft:spruce_planks"),
            Map.entry("birch", "minecraft:birch_planks"),
            Map.entry("白桦", "minecraft:birch_planks"),
            Map.entry("jungle", "minecraft:jungle_planks"),
            Map.entry("丛林", "minecraft:jungle_planks"),
            Map.entry("acacia", "minecraft:acacia_planks"),
            Map.entry("金合欢", "minecraft:acacia_planks"),
            Map.entry("mangrove", "minecraft:mangrove_planks"),
            Map.entry("cherry", "minecraft:cherry_planks"),
            Map.entry("stone brick", "minecraft:stone_bricks"),
            Map.entry("stone_brick", "minecraft:stone_bricks"),
            Map.entry("石砖", "minecraft:stone_bricks"),
            Map.entry("deepslate", "minecraft:deepslate_tiles"),
            Map.entry("深板岩", "minecraft:deepslate_tiles")
    );

    private RoofMaterialIntent() {}

    public static Optional<String> resolveBlockId(String rawIntent) {
        if (rawIntent == null || rawIntent.isBlank()) return Optional.empty();
        String text = rawIntent.trim();
        if (text.contains(":")) {
            return Optional.of(text.toLowerCase(Locale.ROOT));
        }
        String lower = text.toLowerCase(Locale.ROOT);
        // Exact key first (case-insensitive for latin keys).
        for (var e : ALIASES.entrySet()) {
            if (lower.equals(e.getKey().toLowerCase(Locale.ROOT)) || text.equals(e.getKey())) {
                return Optional.of(e.getValue());
            }
        }
        // Longest substring wins so "深色橡木" is not swallowed by "橡木".
        String bestKey = null;
        String bestValue = null;
        for (var e : ALIASES.entrySet()) {
            String key = e.getKey();
            if (!(lower.contains(key.toLowerCase(Locale.ROOT)) || text.contains(key))) continue;
            if (bestKey == null || key.length() > bestKey.length()) {
                bestKey = key;
                bestValue = e.getValue();
            }
        }
        if (bestValue != null) return Optional.of(bestValue);
        // Bare path like dark_oak_planks
        if (lower.matches("[a-z0-9_]+")) {
            return Optional.of("minecraft:" + lower);
        }
        return Optional.empty();
    }
}
