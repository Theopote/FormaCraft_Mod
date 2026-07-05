package com.formacraft.common.archetype;

import com.formacraft.FormacraftMod;
import com.formacraft.common.json.JsonUtil;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * ArchetypeRegistry (data-driven):
 * - Loads archetype definitions from `assets/formacraft/archetypes/archetypes_v1.json`
 * - Provides lookup by id, alias keyword match, and access to generatorId.
 *
 * Goal: adding a new archetype should mostly be "add JSON + add generator implementation",
 * not editing GeneratorRouter logic.
 */
public final class ArchetypeRegistry {
    private static final String RESOURCE_PATH = "/assets/formacraft/archetypes/archetypes_v1.json";

    private static volatile boolean loaded = false;
    private static final Map<String, ArchetypeCatalog.ArchetypeDef> BY_ID = new HashMap<>();
    private static final Map<String, ArchetypeCatalog.ArchetypeDef> BY_ALIAS = new HashMap<>();

    private ArchetypeRegistry() {}

    public static synchronized void ensureLoaded() {
        if (loaded) return;
        try (InputStream in = ArchetypeRegistry.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                FormacraftMod.LOGGER.warn("ArchetypeRegistry: resource not found: {}", RESOURCE_PATH);
                loaded = true;
                return;
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            ArchetypeCatalog catalog = JsonUtil.fromJson(json, ArchetypeCatalog.class);
            if (catalog == null || catalog.archetypes == null) {
                loaded = true;
                return;
            }
            for (ArchetypeCatalog.ArchetypeDef def : catalog.archetypes) {
                if (def == null || def.id == null) continue;
                String id = def.id.trim().toLowerCase();
                if (id.isEmpty()) continue;
                BY_ID.put(id, def);

                if (def.aliases != null) {
                    for (String a : def.aliases) {
                        String alias = (a == null ? "" : a).trim().toLowerCase();
                        if (alias.isEmpty()) continue;
                        // first win
                        BY_ALIAS.putIfAbsent(alias, def);
                    }
                }
                // also index id itself as alias for convenience
                BY_ALIAS.putIfAbsent(id, def);
            }
            loaded = true;
            FormacraftMod.LOGGER.info("ArchetypeRegistry loaded {} archetypes from {}", BY_ID.size(), RESOURCE_PATH);
        } catch (Throwable t) {
            FormacraftMod.LOGGER.warn("ArchetypeRegistry: failed to load {}", RESOURCE_PATH, t);
            loaded = true;
        }
    }

    public static ArchetypeCatalog.ArchetypeDef getById(String id) {
        ensureLoaded();
        if (id == null) return null;
        return BY_ID.get(id.trim().toLowerCase());
    }

    /**
     * Keyword match by aliases: longest alias wins; broad aliases do not match more specific building names.
     */
    public static ArchetypeCatalog.ArchetypeDef matchByKeyword(String text) {
        ensureLoaded();
        if (text == null || text.isBlank()) return null;
        String lower = text.toLowerCase(Locale.ROOT);
        ArchetypeCatalog.ArchetypeDef best = null;
        int bestLen = 0;
        for (ArchetypeCatalog.ArchetypeDef def : BY_ID.values()) {
            if (def == null || def.id == null) continue;
            LandmarkAliasMatcher.Match match = LandmarkAliasMatcher.matchIntent(
                    lower,
                    def.id,
                    def.aliases
            );
            if (match == null || !match.explicit()) continue;
            int len = match.matchedAlias().length();
            if (len > bestLen) {
                best = def;
                bestLen = len;
            }
        }
        return best;
    }

    public static Collection<ArchetypeCatalog.ArchetypeDef> all() {
        ensureLoaded();
        return Collections.unmodifiableCollection(BY_ID.values());
    }
}


