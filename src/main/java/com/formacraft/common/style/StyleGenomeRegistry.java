package com.formacraft.common.style;

import com.formacraft.FormacraftMod;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.model.build.BuildingStyle;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StyleGenomeRegistry：从 resources 加载风格基因配置（json），并按 BuildingStyle 提供默认映射。
 */
public final class StyleGenomeRegistry {
    private StyleGenomeRegistry() {}

    private static final Map<String, StyleGenome> CACHE = new ConcurrentHashMap<>();

    public static StyleGenome forStyle(BuildingStyle style) {
        if (style == null) return load("default");
        return switch (style) {
            case ASIAN -> load("mingqing_official");
            case MEDIEVAL -> load("medieval_stone");
            case MODERN -> load("modern_minimal");
            case FUTURISTIC -> load("futuristic_clean");
            case RUSTIC -> load("rustic_wood");
            case DEFAULT -> load("default");
        };
    }

    public static StyleGenome load(String id) {
        if (id == null || id.isBlank()) id = "default";
        final String key = id.trim();
        return CACHE.computeIfAbsent(key, StyleGenomeRegistry::loadFromResources);
    }

    private static StyleGenome loadFromResources(String id) {
        String path = "/assets/formacraft/style_genomes/" + id + ".json";
        try (InputStream in = StyleGenomeRegistry.class.getResourceAsStream(path)) {
            if (in == null) {
                FormacraftMod.LOGGER.warn("StyleGenome not found on classpath: {}", path);
                StyleGenome g = new StyleGenome();
                g.id = id;
                g.name = id;
                return g;
            }
            StyleGenome g = JsonUtil.get().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), StyleGenome.class);
            if (g != null) {
                if (g.id == null || g.id.isBlank()) g.id = id;
                return g;
            }
        } catch (Throwable t) {
            FormacraftMod.LOGGER.warn("Failed to load StyleGenome: {}", path, t);
        }
        StyleGenome g = new StyleGenome();
        g.id = id;
        g.name = id;
        return g;
    }
}


