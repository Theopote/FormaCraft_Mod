package com.formacraft.common.component;

import com.formacraft.common.json.JsonUtil;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/**
 * 构件库存储（world save 下 JSON）。
 *
 * 目录结构：
 * <world>/formacraft/components/
 *   - catalog.json
 *   - <id>.json
 */
public final class ComponentStorage {
    private ComponentStorage() {}

    public static Path getWorldComponentDir(Path worldDir) {
        return worldDir.resolve("formacraft").resolve("components");
    }

    public static ComponentCatalog loadCatalog(Path worldDir) {
        Path dir = getWorldComponentDir(worldDir);
        Path file = dir.resolve("catalog.json");
        if (!Files.exists(file)) {
            ComponentCatalog c = new ComponentCatalog();
            c.components = new ArrayList<>();
            return c;
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            ComponentCatalog c = JsonUtil.get().fromJson(r, ComponentCatalog.class);
            if (c == null) {
                c = new ComponentCatalog();
            }
            if (c.components == null) c.components = new ArrayList<>();
            return c;
        } catch (Exception e) {
            ComponentCatalog c = new ComponentCatalog();
            c.components = new ArrayList<>();
            return c;
        }
    }

    public static void saveCatalog(Path worldDir, ComponentCatalog catalog) {
        try {
            Path dir = getWorldComponentDir(worldDir);
            Files.createDirectories(dir);
            Path file = dir.resolve("catalog.json");
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                JsonUtil.get().toJson(catalog, w);
            }
        } catch (Exception ignored) {
        }
    }

    public static void saveComponent(Path worldDir, ComponentDefinition def) {
        if (def == null || def.id == null || def.id.isBlank()) return;
        try {
            Path dir = getWorldComponentDir(worldDir);
            Files.createDirectories(dir);

            String fileName = def.id + ".json";
            Path file = dir.resolve(fileName);

            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                JsonUtil.get().toJson(def, w);
            }

            // update catalog
            ComponentCatalog cat = loadCatalog(worldDir);
            cat.components.removeIf(e -> e != null && def.id.equals(e.id));

            ComponentCatalog.Entry e = new ComponentCatalog.Entry();
            e.id = def.id;
            e.name = def.name;
            e.category = def.category;
            e.tags = def.tags;
            e.size = def.size;
            e.file = fileName;
            cat.components.add(e);

            saveCatalog(worldDir, cat);
        } catch (Exception ignored) {
        }
    }

    public static ComponentDefinition loadComponent(Path worldDir, String id) {
        if (id == null || id.isBlank()) return null;
        Path dir = getWorldComponentDir(worldDir);
        Path file = dir.resolve(id + ".json");
        if (!Files.exists(file)) return null;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonUtil.get().fromJson(r, ComponentDefinition.class);
        } catch (Exception e) {
            return null;
        }
    }

    public static String buildCatalogSummary(Path worldDir) {
        ComponentCatalog cat = loadCatalog(worldDir);
        if (cat.components == null || cat.components.isEmpty()) {
            return "(no player components registered)";
        }

        StringBuilder sb = new StringBuilder();
        for (ComponentCatalog.Entry e : cat.components) {
            if (e == null) continue;
            sb.append("- ")
                    .append(e.category != null ? e.category.name() : "UNKNOWN")
                    .append(": ")
                    .append(e.name != null ? e.name : e.id)
                    .append(" (id=").append(e.id).append(")");
            if (e.tags != null) sb.append(" tags=").append(e.tags);
            if (e.size != null) {
                sb.append(" size=").append(e.size.w).append("x").append(e.size.h).append("x").append(e.size.d);
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }
}

