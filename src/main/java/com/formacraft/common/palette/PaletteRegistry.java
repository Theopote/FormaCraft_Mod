package com.formacraft.common.palette;

import com.formacraft.FormacraftMod;
import com.formacraft.common.json.JsonUtil;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * PaletteRegistry (data-driven): loads PaletteCatalog from resources.
 */
public final class PaletteRegistry {
    private static final String RESOURCE_PATH = "/assets/formacraft/palettes/palette_catalog_v1.json";

    private static volatile boolean loaded = false;
    private static final Map<String, PaletteCatalog.PaletteDef> BY_ID = new HashMap<>();

    private PaletteRegistry() {}

    public static synchronized void ensureLoaded() {
        if (loaded) return;
        try (InputStream in = PaletteRegistry.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                FormacraftMod.LOGGER.warn("PaletteRegistry: resource not found: {}", RESOURCE_PATH);
                loaded = true;
                return;
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            PaletteCatalog cat = JsonUtil.fromJson(json, PaletteCatalog.class);
            if (cat != null && cat.palettes != null) {
                BY_ID.putAll(cat.palettes);
                FormacraftMod.LOGGER.info("PaletteRegistry loaded {} palettes from {}", BY_ID.size(), RESOURCE_PATH);
            } else {
                FormacraftMod.LOGGER.warn("PaletteRegistry: empty catalog from {}", RESOURCE_PATH);
            }
        } catch (Throwable t) {
            FormacraftMod.LOGGER.warn("PaletteRegistry: failed to load {}", RESOURCE_PATH, t);
        } finally {
            loaded = true;
        }
    }

    public static PaletteCatalog.PaletteDef get(String paletteId) {
        ensureLoaded();
        if (paletteId == null) return null;
        return BY_ID.get(paletteId.trim());
    }

    public static Map<String, PaletteCatalog.PaletteDef> all() {
        ensureLoaded();
        return Collections.unmodifiableMap(BY_ID);
    }
}


