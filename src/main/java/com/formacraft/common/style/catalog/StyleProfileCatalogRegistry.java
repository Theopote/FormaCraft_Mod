package com.formacraft.common.style.catalog;

import com.formacraft.FormacraftMod;
import com.formacraft.common.json.JsonUtil;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads StyleProfileCatalog from resources and provides lookup by style_id.
 */
public final class StyleProfileCatalogRegistry {
    private static final String RESOURCE_PATH = "/assets/formacraft/style_profiles/style_profile_catalog_v1.json";

    private static volatile boolean loaded = false;
    private static final Map<String, StyleProfileCatalog.StyleProfileDef> BY_ID = new HashMap<>();

    private StyleProfileCatalogRegistry() {}

    public static synchronized void ensureLoaded() {
        if (loaded) return;
        try (InputStream in = StyleProfileCatalogRegistry.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                FormacraftMod.LOGGER.warn("StyleProfileCatalogRegistry: resource not found: {}", RESOURCE_PATH);
                loaded = true;
                return;
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            StyleProfileCatalog cat = JsonUtil.fromJson(json, StyleProfileCatalog.class);
            if (cat != null && cat.profiles != null) {
                BY_ID.putAll(cat.profiles);
                FormacraftMod.LOGGER.info("StyleProfileCatalogRegistry loaded {} profiles from {}", BY_ID.size(), RESOURCE_PATH);
            } else {
                FormacraftMod.LOGGER.warn("StyleProfileCatalogRegistry: empty catalog from {}", RESOURCE_PATH);
            }
        } catch (Throwable t) {
            FormacraftMod.LOGGER.warn("StyleProfileCatalogRegistry: failed to load {}", RESOURCE_PATH, t);
        } finally {
            loaded = true;
        }
    }

    public static StyleProfileCatalog.StyleProfileDef get(String styleId) {
        ensureLoaded();
        if (styleId == null) return null;
        return BY_ID.get(styleId.trim());
    }

    public static Map<String, StyleProfileCatalog.StyleProfileDef> all() {
        ensureLoaded();
        return Collections.unmodifiableMap(BY_ID);
    }
}


