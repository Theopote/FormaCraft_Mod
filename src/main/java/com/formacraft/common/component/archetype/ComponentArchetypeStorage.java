package com.formacraft.common.component.archetype;

import com.formacraft.FormacraftMod;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.json.JsonUtil;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * ComponentArchetypeStorage（构件原型存储）：管理 Archetype 的加载、保存和注册。
 * <p>
 * 目录结构（与构件库并列）：
 * <config>/formacraft/archetypes/&lt;id&gt;.json
 */
public final class ComponentArchetypeStorage {
    private ComponentArchetypeStorage() {}

    private static final Map<String, ComponentArchetype> registry = new ConcurrentHashMap<>();

    /** 兼容旧版相对路径 */
    private static final String LEGACY_BASE_PATH = "formacraft/archetypes";

    public static Path getGlobalArchetypeDir() {
        try {
            return FabricLoader.getInstance().getConfigDir()
                    .resolve("formacraft")
                    .resolve("archetypes");
        } catch (Throwable t) {
            return Path.of("config").resolve("formacraft").resolve("archetypes");
        }
    }

    /** @deprecated 使用 {@link #getGlobalArchetypeDir()} */
    public static Path getBasePath() {
        return getGlobalArchetypeDir();
    }

    public static Path getCategoryPath(String category) {
        return Paths.get(LEGACY_BASE_PATH).resolve(category.toLowerCase());
    }

    public static Path getArchetypePath(String id) {
        if (id == null || id.isBlank()) {
            return getGlobalArchetypeDir().resolve("invalid.json");
        }
        return getGlobalArchetypeDir().resolve(id + ".json");
    }

    private static Path getLegacyArchetypePath(String id) {
        String category = id.split("\\.")[0];
        return getCategoryPath(category).resolve(id + ".json");
    }

    public static ComponentArchetype loadArchetype(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }

        ComponentArchetype cached = registry.get(id);
        if (cached != null) {
            return cached;
        }

        for (Path path : new Path[]{getArchetypePath(id), getLegacyArchetypePath(id)}) {
            ComponentArchetype loaded = readArchetypeFile(path, id);
            if (loaded != null) {
                return loaded;
            }
        }

        FormacraftMod.LOGGER.debug("ComponentArchetype not found on disk: {}", id);
        return null;
    }

    private static ComponentArchetype readArchetypeFile(Path path, String expectedId) {
        if (path == null || !Files.exists(path)) {
            return null;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            ComponentArchetype archetype = JsonUtil.fromJson(json, ComponentArchetype.class);
            if (archetype != null && archetype.id != null && !archetype.id.isBlank()) {
                registry.put(archetype.id, archetype);
                FormacraftMod.LOGGER.debug("Loaded ComponentArchetype: {} from {}", archetype.id, path);
                return archetype;
            }
        } catch (IOException e) {
            FormacraftMod.LOGGER.warn("Failed to load ComponentArchetype {} from {}: {}", expectedId, path, e.getMessage());
        }
        return null;
    }

    public static boolean saveArchetype(ComponentArchetype archetype) {
        if (archetype == null || archetype.id == null || archetype.id.isBlank()) {
            return false;
        }

        try {
            Path path = getArchetypePath(archetype.id);
            Files.createDirectories(path.getParent());
            Files.writeString(path, JsonUtil.toJson(archetype), StandardCharsets.UTF_8);
            registry.put(archetype.id, archetype);
            FormacraftMod.LOGGER.info("Saved ComponentArchetype: {}", archetype.id);
            return true;
        } catch (IOException e) {
            FormacraftMod.LOGGER.error("Failed to save ComponentArchetype {}: {}", archetype.id, e.getMessage());
            return false;
        }
    }

    public static void register(ComponentArchetype archetype) {
        if (archetype != null && archetype.id != null && !archetype.id.isBlank()) {
            registry.put(archetype.id, archetype);
        }
    }

    public static ComponentArchetype get(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return registry.get(id);
    }

    public static ComponentArchetype resolve(ComponentDefinition component) {
        if (component == null) {
            return null;
        }

        String ref = component.archetypeRef;
        if (ref == null || ref.isBlank()) {
            ref = component.id;
        }
        if (ref == null || ref.isBlank()) {
            return null;
        }

        ComponentArchetype archetype = get(ref);
        if (archetype == null) {
            archetype = loadArchetype(ref);
        }
        if (archetype == null) {
            archetype = resolveCategoryDefault(component);
        }
        if (archetype == null) {
            archetype = ComponentArchetypeBridge.fromDefinition(component);
            if (archetype != null) {
                register(archetype);
            }
        }
        return archetype;
    }

    private static ComponentArchetype resolveCategoryDefault(ComponentDefinition component) {
        if (component.category == null) {
            return null;
        }
        return switch (component.category) {
            case DOOR -> get("door.basic");
            case WINDOW -> get("window.basic");
            default -> null;
        };
    }

    /**
     * 启动时从全局目录加载已持久化的 archetype 侧车。
     */
    public static void loadAllFromDisk() {
        Path dir = getGlobalArchetypeDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        int loaded = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path path : files.toList()) {
                if (!Files.isRegularFile(path) || !path.getFileName().toString().endsWith(".json")) {
                    continue;
                }
                String id = path.getFileName().toString().replace(".json", "");
                if (registry.containsKey(id)) {
                    continue;
                }
                if (readArchetypeFile(path, id) != null) {
                    loaded++;
                }
            }
        } catch (IOException e) {
            FormacraftMod.LOGGER.warn("Failed to scan archetype directory {}: {}", dir, e.getMessage());
        }
        if (loaded > 0) {
            FormacraftMod.LOGGER.info("Loaded {} persisted ComponentArchetypes from {}", loaded, dir);
        }
    }

    public static Map<String, ComponentArchetype> getAll() {
        return Map.copyOf(registry);
    }

    public static Map<String, ComponentArchetype> getByCategory(String category) {
        Map<String, ComponentArchetype> result = new HashMap<>();
        for (var entry : registry.entrySet()) {
            if (entry.getValue() != null && category.equals(entry.getValue().category)) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    public static Map<String, ComponentArchetype> searchByTag(String tag) {
        Map<String, ComponentArchetype> result = new HashMap<>();
        for (var entry : registry.entrySet()) {
            ComponentArchetype archetype = entry.getValue();
            if (archetype != null && archetype.semanticTags != null) {
                for (String t : archetype.semanticTags) {
                    if (tag.equalsIgnoreCase(t)) {
                        result.put(entry.getKey(), archetype);
                        break;
                    }
                }
            }
        }
        return result;
    }

    public static void clear() {
        registry.clear();
    }
}
