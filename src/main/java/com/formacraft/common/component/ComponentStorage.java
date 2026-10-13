package com.formacraft.common.component;

import com.formacraft.FormacraftMod;
import com.formacraft.common.json.JsonUtil;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 构件库存储（v3：全局目录 + 玩家命名空间 + 兼容旧 world save 目录）。
 * <p>
 * 目录结构：
 * Global:
 * <config>/formacraft/components/
 *   - catalog.json
 *   - <id>.json
 *   - <id>.png
 *   - players/<uuid>/  （Dedicated 非 OP 写入）
 * Legacy:
 * <world>/formacraft/components/
 */
public final class ComponentStorage {
    private ComponentStorage() {}

    /** 测试钩子：覆盖全局构件目录（生产代码勿用）。 */
    private static volatile Path globalDirOverride;

    /** 仅供单元测试：覆盖全局构件目录。 */
    public static void setGlobalComponentDirOverrideForTest(Path override) {
        globalDirOverride = override;
    }

    /** 保存上下文：控制写入目标与覆盖策略。 */
    public record SaveContext(
            Path worldDir,
            UUID playerId,
            boolean writeGlobal,
            boolean allowOverwriteGlobal
    ) {
        public static SaveContext global(Path worldDir) {
            return new SaveContext(worldDir, null, true, true);
        }

        public static SaveContext forPlayer(Path worldDir, UUID playerId, boolean dedicated, boolean op) {
            if (playerId == null) {
                return global(worldDir);
            }
            // Dedicated 且非 OP：仅写玩家目录；否则可写全局
            boolean writeGlobal = !dedicated || op;
            return new SaveContext(worldDir, playerId, writeGlobal, writeGlobal);
        }
    }

    /** 全局组件库目录（与存档无关）。 */
    public static Path getGlobalComponentDir() {
        if (globalDirOverride != null) {
            return globalDirOverride;
        }
        try {
            Path cfg = FabricLoader.getInstance().getConfigDir();
            return cfg.resolve("formacraft").resolve("components");
        } catch (Throwable t) {
            FormacraftMod.LOGGER.warn("[ComponentStorage] FabricLoader config dir unavailable, using fallback path", t);
            return Path.of("config").resolve("formacraft").resolve("components");
        }
    }

    public static Path getPlayerComponentDir(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("playerId is null");
        }
        return getGlobalComponentDir().resolve("players").resolve(playerId.toString());
    }

    public static Path getWorldComponentDir(Path worldDir) {
        return worldDir.resolve("formacraft").resolve("components");
    }

    public static ComponentCatalog loadCatalog(Path worldDir) {
        return loadCatalog(worldDir, null);
    }

    /**
     * 合并 catalog：legacy &lt; global &lt; player（同 id 后写覆盖前写）。
     */
    public static ComponentCatalog loadCatalog(Path worldDir, UUID viewerPlayerId) {
        Map<String, ComponentCatalog.Entry> byId = new LinkedHashMap<>();

        ComponentCatalog legacy = (worldDir != null) ? loadCatalogFromDir(getWorldComponentDir(worldDir)) : null;
        if (legacy != null && legacy.components != null) {
            for (ComponentCatalog.Entry e : legacy.components) {
                putEntry(byId, e);
            }
        }

        ComponentCatalog global = loadCatalogFromDir(getGlobalComponentDir());
        if (global.components != null) {
            for (ComponentCatalog.Entry e : global.components) {
                putEntry(byId, e);
            }
        }

        if (viewerPlayerId != null) {
            ComponentCatalog playerCat = loadCatalogFromDir(getPlayerComponentDir(viewerPlayerId));
            if (playerCat.components != null) {
                for (ComponentCatalog.Entry e : playerCat.components) {
                    putEntry(byId, e);
                }
            }
        }

        ComponentCatalog out = new ComponentCatalog();
        out.components = new ArrayList<>(byId.values());
        return out;
    }

    private static void putEntry(Map<String, ComponentCatalog.Entry> byId, ComponentCatalog.Entry e) {
        if (e == null || e.id == null || e.id.isBlank()) return;
        String loadId = ComponentIds.tryNormalizeLoadId(e.id);
        if (loadId == null) return;
        byId.put(loadId, e);
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

            for (ComponentCatalog.Entry e : c.components) {
                if (e == null || e.id == null || e.id.isBlank()) continue;
                if (e.updatedAtMs != null) continue;
                try {
                    Path defFile = ComponentIds.tryResolveComponentFile(dir, e.id, ".json");
                    if (defFile != null && Files.exists(defFile)) {
                        e.updatedAtMs = Files.getLastModifiedTime(defFile).toMillis();
                    }
                } catch (Throwable t) {
                    FormacraftMod.LOGGER.debug("[ComponentStorage] catalog backfill updatedAtMs failed componentId={}", e.id, t);
                }
                if (e.thumbnail == null || e.thumbnail.isBlank()) {
                    try {
                        Path thumb = ComponentIds.tryResolveComponentFile(dir, e.id, ".png");
                        if (thumb != null && Files.exists(thumb)) {
                            e.thumbnail = e.id + ".png";
                        }
                    } catch (Throwable t) {
                        FormacraftMod.LOGGER.debug("[ComponentStorage] catalog backfill thumbnail failed componentId={}", e.id, t);
                    }
                }
            }
            return c;
        } catch (Exception e) {
            FormacraftMod.LOGGER.warn("[ComponentStorage] Failed to load catalog from {}", file, e);
            ComponentCatalog c = new ComponentCatalog();
            c.components = new ArrayList<>();
            return c;
        }
    }

    public static ComponentCatalog loadCatalogWithSockets(Path worldDir) {
        return loadCatalogWithSockets(worldDir, null);
    }

    public static ComponentCatalog loadCatalogWithSockets(Path worldDir, UUID viewerPlayerId) {
        ComponentCatalog cat = loadCatalog(worldDir, viewerPlayerId);
        if (cat.components == null || cat.components.isEmpty()) return cat;

        for (ComponentCatalog.Entry e : cat.components) {
            if (e == null) continue;
            boolean needSockets = (e.sockets == null || e.sockets.isEmpty());
            boolean needSpec = (e.placementSpec == null);
            boolean needRules = e.variantRules == null;
            if (e.id == null || e.id.isBlank()) continue;
            if (!needSockets && !needSpec && !needRules) continue;
            ComponentDefinition def = loadComponent(worldDir, e.id, viewerPlayerId);
            if (def != null) {
                if (needRules) e.variantRules = def.variantRules;
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
            writeJsonAtomic(file, catalog);
        } catch (Exception e) {
            FormacraftMod.LOGGER.error("[ComponentStorage] Failed to save catalog to {}", dir, e);
            throw new RuntimeException("Failed to save catalog to " + dir, e);
        }
    }

    /** 兼容旧调用：写入全局库。 */
    public static void saveComponent(Path worldDir, ComponentDefinition def) {
        saveComponentWithContext(SaveContext.global(worldDir), def);
    }

    /**
     * 保存构件：副本上 autofix → 验证 ERROR 阻断 → 原子写入。
     *
     * @throws InvalidComponentException 验证失败
     * @throws IllegalArgumentException 非法 ID / 冲突
     * @throws RuntimeException IO 失败
     */
    public static void saveComponentWithContext(SaveContext ctx, ComponentDefinition def) {
        if (def == null) {
            throw new InvalidComponentException("Component is null");
        }
        String id = ComponentIds.validateComponentId(def.id);
        def.id = id;

        if (def.blocks != null && def.blocks.size() > ComponentIds.MAX_BLOCKS) {
            throw new InvalidComponentException(
                    "Too many blocks (" + def.blocks.size() + " > " + ComponentIds.MAX_BLOCKS + ")");
        }

        Path worldDir = ctx != null ? ctx.worldDir() : null;
        boolean writeGlobal = ctx == null || ctx.writeGlobal();
        UUID playerId = ctx != null ? ctx.playerId() : null;

        // 冲突：非全局写入时，不得覆盖已有全局同名构件
        if (!writeGlobal && playerId != null) {
            Path globalFile = ComponentIds.resolveComponentFile(getGlobalComponentDir(), id, ".json");
            if (Files.exists(globalFile)) {
                throw new IllegalArgumentException(
                        "Component id '" + id + "' already exists in the global library. Choose another id.");
            }
        }
        if (writeGlobal && ctx != null && !ctx.allowOverwriteGlobal()) {
            Path globalFile = ComponentIds.resolveComponentFile(getGlobalComponentDir(), id, ".json");
            if (Files.exists(globalFile)) {
                throw new IllegalArgumentException(
                        "Component id '" + id + "' already exists. Overwrite not allowed.");
            }
        }

        // 在副本上 autofix，避免污染调用方对象
        ComponentDefinition toSave = deepCopy(def);
        toSave.id = id;

        com.formacraft.common.component.semantic.ComponentSemanticInference.ensureSemanticFields(toSave);
        com.formacraft.common.component.archetype.ComponentArchetypeBridge.sync(toSave);

        var fixReport = com.formacraft.common.component.autofix.ComponentAutoFix.apply(toSave);
        if (!fixReport.empty()) {
            FormacraftMod.LOGGER.info("[ComponentStorage] 保存构件 {} 时应用了自动修复：", toSave.id);
            logIssueSample(fixReport.fixes().stream().map(f -> f.toString()).toList(), 5);
        }

        var validationResult = com.formacraft.common.component.validate.ComponentValidator.validate(toSave);
        if (!validationResult.ok()) {
            FormacraftMod.LOGGER.warn("[ComponentStorage] 拒绝保存构件 {}：{} 个验证错误",
                    toSave.id, validationResult.errors().size());
            logIssueSample(validationResult.errors().stream().map(Object::toString).toList(), 5);
            throw new InvalidComponentException(validationResult.errors());
        }
        if (!validationResult.warnings().isEmpty()) {
            FormacraftMod.LOGGER.info("[ComponentStorage] 保存构件 {} 时发现 {} 个验证警告：",
                    toSave.id, validationResult.warnings().size());
            logIssueSample(validationResult.warnings().stream().map(Object::toString).toList(), 3);
        }

        Path targetDir = writeGlobal ? getGlobalComponentDir() : getPlayerComponentDir(playerId);
        try {
            Files.createDirectories(targetDir);

            Path file = ComponentIds.resolveComponentFile(targetDir, id, ".json");
            Path thumb = ComponentIds.resolveComponentFile(targetDir, id, ".png");

            writeJsonAtomic(file, toSave);

            try {
                com.formacraft.common.component.thumbnail.ComponentThumbnailGenerator.generate(toSave, thumb, 128);
            } catch (Throwable t) {
                FormacraftMod.LOGGER.debug("[ComponentStorage] Thumbnail generation skipped componentId={}", id, t);
            }

            updateCatalogAfterSave(targetDir, worldDir, writeGlobal, toSave);

            // legacy world copy 仅全局写入时保留
            if (writeGlobal && worldDir != null) {
                try {
                    Path legacyDir = getWorldComponentDir(worldDir);
                    Files.createDirectories(legacyDir);
                    Path legacyFile = ComponentIds.resolveComponentFile(legacyDir, id, ".json");
                    writeJsonAtomic(legacyFile, toSave);
                    try {
                        Path lf = ComponentIds.resolveComponentFile(legacyDir, id, ".png");
                        if (!Files.exists(lf)) {
                            com.formacraft.common.component.thumbnail.ComponentThumbnailGenerator.generate(toSave, lf, 128);
                        }
                    } catch (Throwable t) {
                        FormacraftMod.LOGGER.debug("[ComponentStorage] Legacy thumbnail generation skipped componentId={}", id, t);
                    }
                } catch (Throwable t) {
                    FormacraftMod.LOGGER.warn("[ComponentStorage] Legacy world copy failed componentId={}", id, t);
                }
            }
        } catch (InvalidComponentException | IllegalArgumentException | SecurityException e) {
            throw e;
        } catch (Exception e) {
            FormacraftMod.LOGGER.error("[ComponentStorage] Failed to save component componentId={}", id, e);
            throw new RuntimeException("Failed to save component " + id + ": " + e.getMessage(), e);
        }
    }

    private static void updateCatalogAfterSave(
            Path targetDir,
            Path worldDir,
            boolean writeGlobal,
            ComponentDefinition toSave
    ) {
        String id = toSave.id;
        String fileName = id + ".json";
        String thumbName = id + ".png";

        ComponentCatalog cat = loadCatalogFromDir(targetDir);
        cat.components.removeIf(e -> e != null && id.equals(e.id));

        ComponentCatalog.Entry e = new ComponentCatalog.Entry();
        e.id = id;
        e.name = toSave.name;
        e.category = toSave.category;
        e.tags = toSave.tags;
        e.size = toSave.size;
        e.variantRules = toSave.variantRules;
        e.file = fileName;
        e.thumbnail = thumbName;
        e.updatedAtMs = System.currentTimeMillis();
        if (toSave.sockets != null && !toSave.sockets.isEmpty()) {
            e.sockets = toSave.sockets;
        }
        if (toSave.placementSpec != null) {
            e.placementSpec = toSave.placementSpec;
        }
        cat.components.add(e);

        saveCatalogToDir(targetDir, cat);

        // 全局写入时同步合并后的 world/global catalog 视图
        if (writeGlobal) {
            ComponentCatalog merged = loadCatalog(worldDir, null);
            merged.components.removeIf(en -> en != null && id.equals(en.id));
            merged.components.add(e);
            saveCatalog(worldDir, merged);
        }
    }

    /**
     * 将客户端提供的缩略图写入与构件相同的目标目录。
     */
    public static void saveThumbnail(SaveContext ctx, String componentId, byte[] pngBytes) throws Exception {
        String id = ComponentIds.validateComponentId(componentId);
        if (pngBytes == null || pngBytes.length == 0) return;
        boolean writeGlobal = ctx == null || ctx.writeGlobal();
        UUID playerId = ctx != null ? ctx.playerId() : null;
        Path targetDir = writeGlobal ? getGlobalComponentDir() : getPlayerComponentDir(playerId);
        Files.createDirectories(targetDir);
        Path thumbFile = ComponentIds.resolveComponentFile(targetDir, id, ".png");
        Files.write(thumbFile, pngBytes);
    }

    public static ComponentDefinition loadComponent(Path worldDir, String id) {
        return loadComponent(worldDir, id, null);
    }

    public static ComponentDefinition loadComponent(Path worldDir, String id, UUID viewerPlayerId) {
        String loadId = ComponentIds.tryNormalizeLoadId(id);
        if (loadId == null) return null;

        // 玩家目录优先（可见集）
        if (viewerPlayerId != null) {
            ComponentDefinition fromPlayer = readComponentFile(
                    ComponentIds.tryResolveComponentFile(getPlayerComponentDir(viewerPlayerId), loadId, ".json"),
                    loadId, worldDir);
            if (fromPlayer != null) return fromPlayer;
        }

        ComponentDefinition fromGlobal = readComponentFile(
                ComponentIds.tryResolveComponentFile(getGlobalComponentDir(), loadId, ".json"),
                loadId, worldDir);
        if (fromGlobal != null) return fromGlobal;

        if (worldDir != null) {
            return readComponentFile(
                    ComponentIds.tryResolveComponentFile(getWorldComponentDir(worldDir), loadId, ".json"),
                    loadId, worldDir);
        }
        return null;
    }

    private static ComponentDefinition readComponentFile(Path file, String id, Path worldDir) {
        if (file == null || !Files.exists(file)) return null;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            ComponentDefinition def = JsonUtil.get().fromJson(r, ComponentDefinition.class);
            if (def != null) {
                validateLoadedComponent(def, id, worldDir);
            }
            return def;
        } catch (Exception e) {
            FormacraftMod.LOGGER.warn("[ComponentStorage] Failed to load component componentId={} from {}", id, file, e);
            return null;
        }
    }

    private static void validateLoadedComponent(ComponentDefinition def, String id, Path worldDir) {
        var fixReport = com.formacraft.common.component.autofix.ComponentAutoFix.apply(def);
        if (!fixReport.empty()) {
            FormacraftMod.LOGGER.info("[ComponentStorage] 加载构件 {} 时应用了自动修复：", id);
            logIssueSample(fixReport.fixes().stream().map(f -> f.toString()).toList(), 5);
            if (shouldPersistGeometryFix(fixReport)) {
                persistComponentSilently(worldDir, def);
            }
        }

        var validationResult = com.formacraft.common.component.validate.ComponentValidator.validate(def);
        if (!validationResult.ok()) {
            FormacraftMod.LOGGER.warn("[ComponentStorage] 加载构件 {} 时发现 {} 个验证错误：",
                    id, validationResult.errors().size());
            logIssueSample(validationResult.errors().stream().map(Object::toString).toList(), 3);
        }
        if (!validationResult.warnings().isEmpty()) {
            FormacraftMod.LOGGER.info("[ComponentStorage] 加载构件 {} 时发现 {} 个验证警告：",
                    id, validationResult.warnings().size());
            logIssueSample(validationResult.warnings().stream().map(Object::toString).toList(), 3);
        }
    }

    private static boolean shouldPersistGeometryFix(com.formacraft.common.component.autofix.AutoFixReport fixReport) {
        for (var fix : fixReport.fixes()) {
            if ("blocks".equals(fix.path) && fix.message.startsWith("Normalized block bounds")) {
                return true;
            }
        }
        return false;
    }

    private static void persistComponentSilently(Path worldDir, ComponentDefinition def) {
        try {
            // 仅写回全局（历史修复语义）；ERROR 时 saveComponent 会抛错并被吞掉
            if (!ComponentIds.isSafeId(def.id)) {
                FormacraftMod.LOGGER.warn("[ComponentStorage] 跳过写回：非法 id {}", def.id);
                return;
            }
            saveComponentWithContext(SaveContext.global(worldDir), def);
            FormacraftMod.LOGGER.info("[ComponentStorage] 已自动写回修复后的构件 {}", def.id);
        } catch (Exception e) {
            FormacraftMod.LOGGER.warn("[ComponentStorage] 自动写回构件 {} 失败", def.id, e);
        }
    }

    private static void logIssueSample(Collection<String> issues, int maxLines) {
        if (issues == null || issues.isEmpty()) return;
        int total = issues.size();
        int limit = Math.min(maxLines, total);
        int i = 0;
        for (String issue : issues) {
            if (i >= limit) break;
            FormacraftMod.LOGGER.info("  {}", issue);
            i++;
        }
        if (total > limit) {
            FormacraftMod.LOGGER.info("  ... 另有 {} 条未显示", total - limit);
        }
    }

    public static boolean deleteComponent(Path worldDir, String componentId) {
        return deleteComponentWithContext(SaveContext.global(worldDir), componentId);
    }

    public static boolean deleteComponentWithContext(SaveContext ctx, String componentId) {
        String id;
        try {
            id = ComponentIds.validateComponentId(componentId);
        } catch (IllegalArgumentException e) {
            // 允许删除历史含点号 id（仅当路径安全）
            id = ComponentIds.tryNormalizeLoadId(componentId);
            if (id == null) return false;
        }

        Path worldDir = ctx != null ? ctx.worldDir() : null;
        boolean writeGlobal = ctx == null || ctx.writeGlobal();
        UUID playerId = ctx != null ? ctx.playerId() : null;

        try {
            if (writeGlobal) {
                deleteFromDir(getGlobalComponentDir(), id);
                if (worldDir != null) {
                    deleteFromDir(getWorldComponentDir(worldDir), id);
                }
                ComponentCatalog cat = loadCatalog(worldDir, null);
                if (cat.components != null) {
                    String finalId = id;
                    cat.components.removeIf(e -> e != null && finalId.equals(e.id));
                    saveCatalog(worldDir, cat);
                }
            } else if (playerId != null) {
                Path playerDir = getPlayerComponentDir(playerId);
                deleteFromDir(playerDir, id);
                ComponentCatalog cat = loadCatalogFromDir(playerDir);
                if (cat.components != null) {
                    String finalId = id;
                    cat.components.removeIf(e -> e != null && finalId.equals(e.id));
                    saveCatalogToDir(playerDir, cat);
                }
            }
            return true;
        } catch (Exception e) {
            FormacraftMod.LOGGER.error("[ComponentStorage] Failed to delete component componentId={}", componentId, e);
            return false;
        }
    }

    private static void deleteFromDir(Path dir, String id) {
        Path jsonFile = ComponentIds.tryResolveComponentFile(dir, id, ".json");
        Path thumbFile = ComponentIds.tryResolveComponentFile(dir, id, ".png");
        try {
            if (jsonFile != null && Files.exists(jsonFile)) Files.delete(jsonFile);
        } catch (Exception e) {
            FormacraftMod.LOGGER.warn("[ComponentStorage] Failed to delete json componentId={}", id, e);
        }
        try {
            if (thumbFile != null && Files.exists(thumbFile)) Files.delete(thumbFile);
        } catch (Exception e) {
            FormacraftMod.LOGGER.warn("[ComponentStorage] Failed to delete thumbnail componentId={}", id, e);
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

    private static ComponentDefinition deepCopy(ComponentDefinition def) {
        return JsonUtil.fromJson(JsonUtil.toJson(def), ComponentDefinition.class);
    }

    private static void writeJsonAtomic(Path target, Object value) throws Exception {
        Path dir = target.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = Files.createTempFile(dir != null ? dir : Path.of("."), "fc-", ".tmp");
        try {
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                JsonUtil.get().toJson(value, w);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception ignored) {
            }
            throw e;
        }
    }
}
