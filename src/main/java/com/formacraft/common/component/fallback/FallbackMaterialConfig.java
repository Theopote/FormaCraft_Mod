package com.formacraft.common.component.fallback;

import com.formacraft.FormacraftMod;
import com.formacraft.common.json.JsonUtil;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class FallbackMaterialConfig {
    private static final String FILE_NAME = "fallback_materials.json";
    private static final Object LOCK = new Object();
    private static volatile FallbackMaterialConfig INSTANCE;

    public Map<String, String> defaults = new LinkedHashMap<>();
    public Map<String, Map<String, String>> tones = new LinkedHashMap<>();
    public Map<String, Map<String, String>> styles = new LinkedHashMap<>();

    private FallbackMaterialConfig() {}

    public static FallbackMaterialConfig get() {
        FallbackMaterialConfig cfg = INSTANCE;
        if (cfg != null) return cfg;
        synchronized (LOCK) {
            if (INSTANCE == null) {
                INSTANCE = load();
            }
            return INSTANCE;
        }
    }

    public String resolveBlockId(String role, String tone, String styleProfile) {
        String r = normalizeRole(role);
        String styleKey = normalizeKey(styleProfile);
        Map<String, String> styleMap = findOverride(styles, styleKey);
        String id = lookupRole(styleMap, r);
        if (id != null) return id;

        String toneKey = normalizeToneKey(tone);
        Map<String, String> toneMap = findOverride(tones, toneKey);
        id = lookupRole(toneMap, r);
        if (id != null) return id;

        return lookupRole(defaults, r);
    }

    public static BlockState resolveBlockState(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return Blocks.OAK_LOG.getDefaultState();
        }
        String id = blockId;
        int bracket = id.indexOf('[');
        if (bracket > 0) id = id.substring(0, bracket);
        Identifier ident = Identifier.tryParse(id.trim());
        if (ident == null) return Blocks.OAK_LOG.getDefaultState();
        Block block = Registries.BLOCK.get(ident);
        if (block == null || block == Blocks.AIR) return Blocks.OAK_LOG.getDefaultState();
        return block.getDefaultState();
    }

    private static FallbackMaterialConfig load() {
        Path file = resolveConfigFile();
        if (file != null && Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                FallbackMaterialConfig cfg = JsonUtil.get().fromJson(r, FallbackMaterialConfig.class);
                if (cfg != null) {
                    backfillDefaults(cfg);
                    return cfg;
                }
            } catch (Exception e) {
                FormacraftMod.LOGGER.warn("FallbackMaterialConfig: failed to read config: {}", file);
            }
        }
        FallbackMaterialConfig def = defaults();
        save(def, file);
        return def;
    }

    private static void save(FallbackMaterialConfig cfg, Path file) {
        if (cfg == null || file == null) return;
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                JsonUtil.get().toJson(cfg, w);
            }
        } catch (Exception e) {
            FormacraftMod.LOGGER.warn("FallbackMaterialConfig: failed to save config: {}", file);
        }
    }

    private static Path resolveConfigFile() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve("formacraft").resolve(FILE_NAME);
        } catch (Throwable t) {
            return Path.of("config").resolve("formacraft").resolve(FILE_NAME);
        }
    }

    private static String normalizeRole(String role) {
        if (role == null) return null;
        String r = role.trim().toLowerCase(Locale.ROOT);
        if ("pillar".equals(r)) return "column";
        return r;
    }

    private static String normalizeKey(String key) {
        if (key == null) return null;
        String k = key.trim();
        return k.isEmpty() ? null : k.toLowerCase(Locale.ROOT);
    }

    private static String normalizeToneKey(String tone) {
        if (tone == null || tone.isBlank()) return null;
        String t = tone.toLowerCase(Locale.ROOT);
        if (t.contains("vermilion")) return "vermilion";
        if (t.contains("red")) return "red";
        if (t.contains("gold")) return "gold";
        if (t.contains("yellow")) return "yellow";
        if (t.contains("white")) return "white";
        if (t.contains("marble")) return "marble";
        return t;
    }

    private static Map<String, String> findOverride(Map<String, Map<String, String>> map, String key) {
        if (map == null || key == null || key.isBlank()) return null;
        Map<String, String> direct = map.get(key);
        if (direct != null) return direct;
        for (Map.Entry<String, Map<String, String>> e : map.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String lookupRole(Map<String, String> map, String role) {
        if (map == null || role == null || role.isBlank()) return null;
        String v = map.get(role);
        if (v != null) return v;
        if ("bracket".equals(role) || "canopy".equals(role)) {
            v = map.get("ornament");
            if (v != null) return v;
        }
        if ("ornament".equals(role)) {
            v = map.get("bracket");
            if (v != null) return v;
        }
        if ("column".equals(role)) {
            v = map.get("pillar");
            if (v != null) return v;
        }
        if ("pillar".equals(role)) {
            v = map.get("column");
            if (v != null) return v;
        }
        return null;
    }

    private static FallbackMaterialConfig defaults() {
        FallbackMaterialConfig cfg = new FallbackMaterialConfig();
        cfg.defaults.put("column", "minecraft:oak_log");
        cfg.defaults.put("railing", "minecraft:oak_fence");
        cfg.defaults.put("ornament", "minecraft:oak_planks");
        cfg.defaults.put("bracket", "minecraft:oak_planks");
        cfg.defaults.put("canopy", "minecraft:oak_planks");
        cfg.defaults.put("door", "minecraft:oak_planks");
        cfg.defaults.put("window", "minecraft:glass_pane");

        Map<String, String> vermilion = new LinkedHashMap<>();
        vermilion.put("column", "minecraft:red_concrete");
        vermilion.put("railing", "minecraft:red_nether_brick_wall");
        vermilion.put("ornament", "minecraft:red_concrete");
        vermilion.put("bracket", "minecraft:red_concrete");
        vermilion.put("canopy", "minecraft:red_concrete");
        vermilion.put("door", "minecraft:red_concrete");
        vermilion.put("window", "minecraft:red_stained_glass_pane");
        cfg.tones.put("vermilion", vermilion);
        cfg.tones.put("red", new LinkedHashMap<>(vermilion));

        Map<String, String> gold = new LinkedHashMap<>();
        gold.put("column", "minecraft:yellow_terracotta");
        gold.put("railing", "minecraft:yellow_terracotta");
        gold.put("ornament", "minecraft:yellow_terracotta");
        gold.put("bracket", "minecraft:yellow_terracotta");
        gold.put("canopy", "minecraft:yellow_terracotta");
        gold.put("door", "minecraft:yellow_terracotta");
        gold.put("window", "minecraft:yellow_stained_glass_pane");
        cfg.tones.put("gold", gold);
        cfg.tones.put("yellow", new LinkedHashMap<>(gold));

        Map<String, String> white = new LinkedHashMap<>();
        white.put("column", "minecraft:quartz_pillar");
        white.put("railing", "minecraft:quartz_pillar");
        white.put("ornament", "minecraft:quartz_block");
        white.put("bracket", "minecraft:quartz_block");
        white.put("canopy", "minecraft:quartz_block");
        white.put("door", "minecraft:quartz_block");
        white.put("window", "minecraft:white_stained_glass_pane");
        cfg.tones.put("white", white);
        cfg.tones.put("marble", new LinkedHashMap<>(white));

        return cfg;
    }

    private static void backfillDefaults(FallbackMaterialConfig cfg) {
        if (cfg.defaults == null) cfg.defaults = new LinkedHashMap<>();
        if (cfg.tones == null) cfg.tones = new LinkedHashMap<>();
        if (cfg.styles == null) cfg.styles = new LinkedHashMap<>();
        FallbackMaterialConfig def = defaults();
        for (Map.Entry<String, String> e : def.defaults.entrySet()) {
            cfg.defaults.putIfAbsent(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, Map<String, String>> e : def.tones.entrySet()) {
            cfg.tones.putIfAbsent(e.getKey(), e.getValue());
        }
    }
}
