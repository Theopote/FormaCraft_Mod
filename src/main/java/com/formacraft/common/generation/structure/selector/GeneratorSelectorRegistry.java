package com.formacraft.common.generation.structure.selector;

import com.formacraft.FormacraftMod;
import com.formacraft.common.json.JsonUtil;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * GeneratorSelectorRegistry (data-driven):
 * Loads rules from assets and provides ordered matching.
 */
public final class GeneratorSelectorRegistry {
    private static final String RESOURCE_PATH = "/assets/formacraft/generator_selector/generator_selector_rules_v1.json";

    private static volatile boolean loaded = false;
    private static final List<GeneratorSelectorCatalog.Rule> RULES = new ArrayList<>();

    private GeneratorSelectorRegistry() {}

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        try (InputStream in = GeneratorSelectorRegistry.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                FormacraftMod.LOGGER.warn("GeneratorSelectorRegistry: resource not found: {}", RESOURCE_PATH);
                loaded = true;
                return;
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            GeneratorSelectorCatalog cat = JsonUtil.fromJson(json, GeneratorSelectorCatalog.class);
            if (cat != null && cat.rules != null) {
                RULES.addAll(cat.rules);
            }
            loaded = true;
            FormacraftMod.LOGGER.info("GeneratorSelectorRegistry loaded {} rules from {}", RULES.size(), RESOURCE_PATH);
        } catch (Throwable t) {
            FormacraftMod.LOGGER.warn("GeneratorSelectorRegistry: failed to load {}", RESOURCE_PATH, t);
            loaded = true;
        }
    }

    public static GeneratorSelectorCatalog.Rule match(String cityStyleUpper,
                                                      String zoneTypeUpper,
                                                      String shapeUpper,
                                                      int radius,
                                                      int width,
                                                      int depth) {
        ensureLoaded();
        String cs = cityStyleUpper != null ? cityStyleUpper.trim().toUpperCase(Locale.ROOT) : "";
        String zt = zoneTypeUpper != null ? zoneTypeUpper.trim().toUpperCase(Locale.ROOT) : "";
        String sh = shapeUpper != null ? shapeUpper.trim().toUpperCase(Locale.ROOT) : "";
        int r = Math.max(0, radius);
        int wEff = Math.max(0, width);
        int dEff = Math.max(0, depth);
        int areaEff = (wEff <= 0 || dEff <= 0) ? 0 : (wEff * dEff);

        for (GeneratorSelectorCatalog.Rule rule : RULES) {
            if (rule == null || rule.when == null || rule.then == null) continue;
            GeneratorSelectorCatalog.When w = rule.when;
            if (w.cityStyle != null && !w.cityStyle.isBlank()) {
                if (!cs.equals(w.cityStyle.trim().toUpperCase(Locale.ROOT))) continue;
            }
            if (w.zoneType != null && !w.zoneType.isBlank()) {
                if (!zt.equals(w.zoneType.trim().toUpperCase(Locale.ROOT))) continue;
            }
            if (w.shape != null && !w.shape.isBlank()) {
                if (!sh.equals(w.shape.trim().toUpperCase(Locale.ROOT))) continue;
            }
            if (w.minRadius != null && r < w.minRadius) continue;
            if (w.maxRadius != null && r > w.maxRadius) continue;
            if (w.minWidth != null && wEff < w.minWidth) continue;
            if (w.maxWidth != null && wEff > w.maxWidth) continue;
            if (w.minDepth != null && dEff < w.minDepth) continue;
            if (w.maxDepth != null && dEff > w.maxDepth) continue;
            if (w.minArea != null && areaEff < w.minArea) continue;
            if (w.maxArea != null && areaEff > w.maxArea) continue;
            return rule;
        }
        return null;
    }
}


