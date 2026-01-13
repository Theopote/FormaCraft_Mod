package com.formacraft.common.component;

import com.formacraft.common.json.JsonUtil;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 构件库存储（v2：全局目录 + 兼容旧 world save 目录）。
 * <p>
 * 目录结构：
 * Global（推荐）:
 * <config>/formacraft/components/
 *   - catalog.json
 *   - <id>.json
 *   - <id>.png (thumbnail)
 *
 * Legacy（兼容旧版）:
 * <world>/formacraft/components/
 *   - catalog.json
 *   - <id>.json
 */
public final class ComponentStorage {
    private ComponentStorage() {}

    /** 全局组件库目录（与存档无关）。 */
    public static Path getGlobalComponentDir() {
        try {
            Path cfg = FabricLoader.getInstance().getConfigDir();
            return cfg.resolve("formacraft").resolve("components");
        } catch (Throwable t) {
            // fallback：退化到当前进程目录下（极少数环境）
            return Path.of("config").resolve("formacraft").resolve("components");
        }
    }

    public static Path getWorldComponentDir(Path worldDir) {
        return worldDir.resolve("formacraft").resolve("components");
    }

    public static ComponentCatalog loadCatalog(Path worldDir) {
        ComponentCatalog global = loadCatalogFromDir(getGlobalComponentDir());
        ComponentCatalog legacy = (worldDir != null) ? loadCatalogFromDir(getWorldComponentDir(worldDir)) : null;

        // merge：global 优先，其次 legacy（按 id 去重）
        Map<String, ComponentCatalog.Entry> byId = new LinkedHashMap<>();
        if (legacy != null && legacy.components != null) {
            for (ComponentCatalog.Entry e : legacy.components) {
                if (e == null || e.id == null || e.id.isBlank()) continue;
                byId.put(e.id, e);
            }
        }
        if (global != null && global.components != null) {
            for (ComponentCatalog.Entry e : global.components) {
                if (e == null || e.id == null || e.id.isBlank()) continue;
                byId.put(e.id, e);
            }
        }

        ComponentCatalog out = new ComponentCatalog();
        out.components = new ArrayList<>(byId.values());
        return out;
    }

    private static ComponentCatalog loadCatalogFromDir(Path dir) {
        if (dir == null) {
            ComponentCatalog c = new ComponentCatalog();
            c.components = new ArrayList<>();
            return c;
        }
        Path file = dir.resolve("catalog.json");
        if (!Files.exists(file)) {
            ComponentCatalog c = new ComponentCatalog();
            c.components = new ArrayList<>();
            return c;
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            ComponentCatalog c = JsonUtil.get().fromJson(r, ComponentCatalog.class);
            if (c == null) c = new ComponentCatalog();
            if (c.components == null) c.components = new ArrayList<>();
            return c;
        } catch (Exception e) {
            ComponentCatalog c = new ComponentCatalog();
            c.components = new ArrayList<>();
            return c;
        }
    }

    /**
     * 加载 catalog，并尽力把每个 entry 的 sockets 补齐（便于 Prompt/LLM mount 直接使用）。
     * <p>
     * - 兼容旧 catalog.json（entry.sockets 为空）
     * - best-effort：某个组件文件缺失/解析失败不会影响其他条目
     */
    public static ComponentCatalog loadCatalogWithSockets(Path worldDir) {
        ComponentCatalog cat = loadCatalog(worldDir);
        if (cat.components == null || cat.components.isEmpty()) return cat;

        for (ComponentCatalog.Entry e : cat.components) {
            if (e == null) continue;
            boolean needSockets = (e.sockets == null || e.sockets.isEmpty());
            boolean needSpec = (e.placementSpec == null);
            if (e.id == null || e.id.isBlank()) continue;
            if (!needSockets && !needSpec) continue;
            ComponentDefinition def = loadComponent(worldDir, e.id);
            if (def != null) {
                if (needSockets && def.sockets != null && !def.sockets.isEmpty()) {
                    e.sockets = def.sockets;
                }
                if (needSpec && def.placementSpec != null) {
                    e.placementSpec = def.placementSpec;
                }
            }
        }
        return cat;
    }

    public static void saveCatalog(Path worldDir, ComponentCatalog catalog) {
        // v2：写入全局目录（主），并尽量写一份 legacy world copy（兼容旧逻辑/方便迁移）
        saveCatalogToDir(getGlobalComponentDir(), catalog);
        if (worldDir != null) {
            saveCatalogToDir(getWorldComponentDir(worldDir), catalog);
        }
    }

    private static void saveCatalogToDir(Path dir, ComponentCatalog catalog) {
        try {
            if (dir == null) return;
            Files.createDirectories(dir);
            Path file = dir.resolve("catalog.json");
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                JsonUtil.get().toJson(catalog, w);
            }
        } catch (Exception ignored) {}
    }

    public static void saveComponent(Path worldDir, ComponentDefinition def) {
        if (def == null || def.id == null || def.id.isBlank()) return;
        try {
            Path globalDir = getGlobalComponentDir();
            Files.createDirectories(globalDir);

            String fileName = def.id + ".json";
            String thumbName = def.id + ".png";

            Path file = globalDir.resolve(fileName);

            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                JsonUtil.get().toJson(def, w);
            }

            // v1 thumbnail（best-effort）
            try {
                com.formacraft.common.component.thumbnail.ComponentThumbnailGenerator.generate(def, globalDir.resolve(thumbName), 128);
            } catch (Throwable ignored) {}

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
            e.thumbnail = thumbName;
            if (def.sockets != null && !def.sockets.isEmpty()) {
                e.sockets = def.sockets;
            }
            if (def.placementSpec != null) {
                e.placementSpec = def.placementSpec;
            }
            cat.components.add(e);

            saveCatalog(worldDir, cat);

            // legacy copy：仍写一份到 worldDir（兼容旧世界 & 便于手工迁移）
            try {
                if (worldDir != null) {
                    Path legacyDir = getWorldComponentDir(worldDir);
                    Files.createDirectories(legacyDir);
                    try (Writer w = Files.newBufferedWriter(legacyDir.resolve(fileName), StandardCharsets.UTF_8)) {
                        JsonUtil.get().toJson(def, w);
                    }
                    // thumbnail 也尽量拷贝一份（不强制）
                    try {
                        Path lf = legacyDir.resolve(thumbName);
                        if (!Files.exists(lf)) {
                            com.formacraft.common.component.thumbnail.ComponentThumbnailGenerator.generate(def, lf, 128);
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        } catch (Exception ignored) {
        }
    }

    public static ComponentDefinition loadComponent(Path worldDir, String id) {
        if (id == null || id.isBlank()) return null;
        String fileName = id + ".json";
        // v2：优先全局，再 legacy world
        Path g = getGlobalComponentDir().resolve(fileName);
        if (Files.exists(g)) {
            try (Reader r = Files.newBufferedReader(g, StandardCharsets.UTF_8)) {
                return JsonUtil.get().fromJson(r, ComponentDefinition.class);
            } catch (Exception ignored) {}
        }
        if (worldDir != null) {
            Path w = getWorldComponentDir(worldDir).resolve(fileName);
            if (Files.exists(w)) {
                try (Reader r = Files.newBufferedReader(w, StandardCharsets.UTF_8)) {
                    return JsonUtil.get().fromJson(r, ComponentDefinition.class);
                } catch (Exception ignored) {}
            }
        }
        return null;
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

