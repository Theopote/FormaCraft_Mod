package com.formacraft.common.style.profile;

import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.style.StyleGenome;
import com.formacraft.common.style.StyleGenomeRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StyleProfileRegistry (v1):
 * - Backed by existing StyleGenome JSON assets.
 * - Provides a stable runtime API for generators/interpreters.
 */
public final class StyleProfileRegistry {
    private StyleProfileRegistry() {}

    private static final Map<String, StyleProfile> CACHE = new ConcurrentHashMap<>();

    public static StyleProfile forStyle(BuildingStyle style) {
        StyleGenome g = StyleGenomeRegistry.forStyle(style);
        String key = (g != null && g.id != null && !g.id.isBlank()) ? g.id : "default";
        return CACHE.computeIfAbsent(key, k -> new GenomeStyleProfile(g, style));
    }

    public static StyleProfile load(String id, BuildingStyle fallbackStyle) {
        StyleGenome g = StyleGenomeRegistry.load(id);
        String key = (g != null && g.id != null && !g.id.isBlank()) ? g.id : (id == null ? "default" : id.trim());
        return CACHE.computeIfAbsent(key, k -> new GenomeStyleProfile(g, fallbackStyle));
    }
}


