package com.formacraft.common.component.archetype;

import com.formacraft.common.json.JsonUtil;
import com.formacraft.FormacraftMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ComponentArchetypeStorage（构件原型存储）：管理 Archetype 的加载、保存和注册。
 * <p>
 * 存储结构：
 * .minecraft/formacraft/archetypes/
 *     doors/
 *     windows/
 *     columns/
 *     roofs/
 *     decorations/
 * <p>
 * 每个构件：
 * - archetype.json（ComponentArchetype）
 * - 可选：voxel_template.dat / json
 * - 可选：preview.png
 */
public final class ComponentArchetypeStorage {
    private ComponentArchetypeStorage() {}

    // 内存注册表（id -> ComponentArchetype）
    private static final Map<String, ComponentArchetype> registry = new ConcurrentHashMap<>();

    // 默认存储路径
    private static final String DEFAULT_BASE_PATH = "formacraft/archetypes";

    /**
     * 获取存储基础路径
     */
    public static Path getBasePath() {
        // 这里需要根据实际环境获取 .minecraft 目录
        // 暂时返回相对路径
        return Paths.get(DEFAULT_BASE_PATH);
    }

    /**
     * 获取指定分类的存储路径
     */
    public static Path getCategoryPath(String category) {
        return getBasePath().resolve(category.toLowerCase());
    }

    /**
     * 获取指定 Archetype 的文件路径
     */
    public static Path getArchetypePath(String id) {
        // 从 id 推断分类（例如：door.basic -> doors/）
        String category = id.split("\\.")[0];
        String filename = id + ".json";
        return getCategoryPath(category).resolve(filename);
    }

    /**
     * 加载 Archetype（从文件）
     */
    public static ComponentArchetype loadArchetype(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }

        // 先检查内存注册表
        ComponentArchetype cached = registry.get(id);
        if (cached != null) {
            return cached;
        }

        // 从文件加载
        Path path = getArchetypePath(id);
        if (!Files.exists(path)) {
            FormacraftMod.LOGGER.warn("ComponentArchetype not found: {}", id);
            return null;
        }

        try {
            String json = Files.readString(path);
            ComponentArchetype archetype = JsonUtil.fromJson(json, ComponentArchetype.class);
            if (archetype != null) {
                // 注册到内存
                registry.put(id, archetype);
                FormacraftMod.LOGGER.info("Loaded ComponentArchetype: {}", id);
                return archetype;
            }
        } catch (IOException e) {
            FormacraftMod.LOGGER.error("Failed to load ComponentArchetype {}: {}", id, e.getMessage());
        }

        return null;
    }

    /**
     * 保存 Archetype（到文件）
     */
    public static boolean saveArchetype(ComponentArchetype archetype) {
        if (archetype == null || archetype.id == null || archetype.id.isBlank()) {
            return false;
        }

        try {
            Path path = getArchetypePath(archetype.id);
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            String json = JsonUtil.toJson(archetype);
            Files.writeString(path, json);

            // 注册到内存
            registry.put(archetype.id, archetype);

            FormacraftMod.LOGGER.info("Saved ComponentArchetype: {}", archetype.id);
            return true;
        } catch (IOException e) {
            FormacraftMod.LOGGER.error("Failed to save ComponentArchetype {}: {}", archetype.id, e.getMessage());
            return false;
        }
    }

    /**
     * 注册 Archetype（仅内存，不保存到文件）
     */
    public static void register(ComponentArchetype archetype) {
        if (archetype != null && archetype.id != null && !archetype.id.isBlank()) {
            registry.put(archetype.id, archetype);
        }
    }

    /**
     * 获取已注册的 Archetype
     */
    public static ComponentArchetype get(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return registry.get(id);
    }

    /**
     * 获取所有已注册的 Archetype
     */
    public static Map<String, ComponentArchetype> getAll() {
        return Map.copyOf(registry);
    }

    /**
     * 根据分类获取 Archetype 列表
     */
    public static Map<String, ComponentArchetype> getByCategory(String category) {
        Map<String, ComponentArchetype> result = new HashMap<>();
        for (var entry : registry.entrySet()) {
            if (entry.getValue() != null && category.equals(entry.getValue().category)) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    /**
     * 根据语义标签搜索 Archetype
     */
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

    /**
     * 清除注册表（用于重新加载）
     */
    public static void clear() {
        registry.clear();
    }
}
