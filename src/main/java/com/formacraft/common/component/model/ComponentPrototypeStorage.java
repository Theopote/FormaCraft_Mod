package com.formacraft.common.component.model;

import com.formacraft.common.component.ComponentCategory;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.ComponentStorage;
import com.formacraft.common.component.placement.ComponentPlacementSpec;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.logging.FcaLog;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.List;

/**
 * Prototype/Variant/Instance 的最小存储实现（v1）。
 * <p>
 * 目标：先把三层模型“落盘形态”固定下来，不影响现有 ComponentDefinition(v1) 组件库。
 * <p>
 * 推荐（跨存档共享）目录（按你的建议：客户端资源库，位于 .minecraft 下）：
 * <gameDir>/formacraft/components/
 *   - catalog.json
 *   - doors/<prototypeId>/prototype.json
 *   - doors/<prototypeId>/structure.nbt (或 structure.json / patch.json / component.json)
 *   - doors/<prototypeId>/thumbnail.png
 *   - doors/<prototypeId>/variants/<variantId>.json  (可选：逐个保存)
 *   - doors/<prototypeId>/variants.json             (可选：预置变体集合，读取支持)
 * <p>
 * 兼容（旧版/开发期）目录（不主动写入，但会读取）：
 * <config>/formacraft/components/prototypes/<category>/<id>/prototype.json
 * <config>/formacraft/components/prototype_catalog.json
 */
public final class ComponentPrototypeStorage {
    private ComponentPrototypeStorage() {}

    private static final FcaLog LOG = FcaLog.of("ComponentPrototypeStorage");

    /**
     * Prototype 库根目录（推荐）：<gameDir>/formacraft/components
     * <p>
     * 可通过 JVM 参数覆盖：-Dformacraft.prototypeLibraryDir=/abs/path
     */
    public static Path getPrototypeLibraryRoot() {
        try {
            String override = System.getProperty("formacraft.prototypeLibraryDir");
            if (override != null && !override.isBlank()) {
                return Path.of(override.trim());
            }
        } catch (Throwable t) {
            LOG.debug("prototypeLibraryDir override invalid", t);
        }
        try {
            return FabricLoader.getInstance().getGameDir().resolve("formacraft").resolve("components");
        } catch (Throwable t) {
            LOG.warn("FabricLoader game dir unavailable, using fallback path", t);
            return Path.of("formacraft").resolve("components");
        }
    }

    /** 旧版 prototype 根目录：<config>/formacraft/components/prototypes */
    public static Path getLegacyPrototypesRoot() {
        return ComponentStorage.getGlobalComponentDir().resolve("prototypes");
    }

    /** 旧版 prototype 目录：<config>/formacraft/components/prototypes/<category>/<id>/ */
    public static Path getLegacyPrototypeDir(ComponentCategory category, String prototypeId) {
        String cat = (category != null ? category.name() : ComponentCategory.GENERIC.name());
        return getLegacyPrototypesRoot()
                .resolve(cat.toLowerCase(Locale.ROOT))
                .resolve(safeId(prototypeId));
    }

    public static Path getLegacyVariantDir(ComponentCategory category, String prototypeId) {
        return getLegacyPrototypeDir(category, prototypeId).resolve("variants");
    }

    /** 推荐 catalog 文件名：catalog.json */
    public static Path getPrototypeCatalogFile() {
        return getPrototypeLibraryRoot().resolve("catalog.json");
    }

    /** 旧版 catalog 文件名：prototype_catalog.json */
    public static Path getLegacyPrototypeCatalogFile() {
        return ComponentStorage.getGlobalComponentDir().resolve("prototype_catalog.json");
    }

    public static Path getPrototypeDir(ComponentCategory category, String prototypeId) {
        return getPrototypeLibraryRoot()
                .resolve(categoryFolder(category))
                .resolve(safeId(prototypeId));
    }

    public static Path getPrototypeJsonFile(ComponentCategory category, String prototypeId) {
        return getPrototypeDir(category, prototypeId).resolve("prototype.json");
    }

    public static Path getVariantDir(ComponentCategory category, String prototypeId) {
        return getPrototypeDir(category, prototypeId).resolve("variants");
    }

    public static ComponentPrototypeCatalog loadCatalog() {
        Path preferred = getPrototypeCatalogFile();
        if (Files.exists(preferred)) {
            try (Reader r = Files.newBufferedReader(preferred, StandardCharsets.UTF_8)) {
                ComponentPrototypeCatalog c = JsonUtil.get().fromJson(r, ComponentPrototypeCatalog.class);
                if (c == null) c = new ComponentPrototypeCatalog();
                if (c.prototypes == null) c.prototypes = new ArrayList<>();
                return c;
            } catch (Throwable t) {
                LOG.warn("load prototype catalog failed path={}", preferred, t);
            }
        }

        // fallback: legacy file name/location
        Path legacy = getLegacyPrototypeCatalogFile();
        if (Files.exists(legacy)) {
            try (Reader r = Files.newBufferedReader(legacy, StandardCharsets.UTF_8)) {
                ComponentPrototypeCatalog c = JsonUtil.get().fromJson(r, ComponentPrototypeCatalog.class);
                if (c == null) c = new ComponentPrototypeCatalog();
                if (c.prototypes == null) c.prototypes = new ArrayList<>();
                return c;
            } catch (Throwable t) {
                LOG.warn("load legacy prototype catalog failed path={}", legacy, t);
            }
        }

        ComponentPrototypeCatalog c = new ComponentPrototypeCatalog();
        c.prototypes = new ArrayList<>();
        return c;
    }

    public static void saveCatalog(ComponentPrototypeCatalog catalog) {
        if (catalog == null) return;
        try {
            Files.createDirectories(getPrototypeLibraryRoot());
            Path f = getPrototypeCatalogFile();
            try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                JsonUtil.get().toJson(catalog, w);
            }
        } catch (Throwable t) {
            LOG.warn("save prototype catalog failed path={}", getPrototypeCatalogFile(), t);
        }
        // best-effort：也写一份旧文件名，避免某些旧工具/脚本只认识 prototype_catalog.json
        try {
            Files.createDirectories(ComponentStorage.getGlobalComponentDir());
            Path f = getLegacyPrototypeCatalogFile();
            try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                JsonUtil.get().toJson(catalog, w);
            }
        } catch (Throwable t) {
            LOG.debug("save legacy prototype catalog failed path={}", getLegacyPrototypeCatalogFile(), t);
        }
    }

    /**
     * 扫描 prototypes 目录重建 catalog（best-effort）。
     * <p>
     * v1：仅扫描 depth=2（category/id）并读取 prototype.json。
     */
    public static ComponentPrototypeCatalog rebuildCatalog() {
        ComponentPrototypeCatalog out = new ComponentPrototypeCatalog();
        out.prototypes = new ArrayList<>();
        scanRoot(out, getPrototypeLibraryRoot());
        scanRoot(out, getLegacyPrototypesRoot());

        saveCatalog(out);
        return out;
    }

    /**
     * 通过 catalog 定位并加载 prototype.json。
     * 如果 catalog 不存在/缺少条目，会尝试 rebuildCatalog() 再读一次。
     */
    public static ComponentPrototype loadPrototype(String prototypeId) {
        if (prototypeId == null || prototypeId.isBlank()) return null;
        String pid = prototypeId.trim();

        ComponentPrototypeCatalog cat = loadCatalog();
        ComponentPrototypeCatalog.Entry hit = find(cat, pid);
        if (hit == null) {
            cat = rebuildCatalog();
            hit = find(cat, pid);
        }
        if (hit == null) return null;

        // 优先按推荐目录读取；如缺失则 fallback 到 legacy 目录
        Path pj = getPrototypeJsonFile(hit.category, pid);
        if (!Files.exists(pj)) {
            pj = getLegacyPrototypesRoot()
                    .resolve((hit.category != null ? hit.category.name() : ComponentCategory.GENERIC.name()).toLowerCase(Locale.ROOT))
                    .resolve(safeId(pid))
                    .resolve("prototype.json");
        }
        if (!Files.exists(pj)) return null;
        try (Reader r = Files.newBufferedReader(pj, StandardCharsets.UTF_8)) {
            return JsonUtil.get().fromJson(r, ComponentPrototype.class);
        } catch (Throwable t) {
            LOG.warn("load prototype failed componentId={} path={}", pid, pj, t);
            return null;
        }
    }

    public static void savePrototype(ComponentPrototype proto) {
        if (proto == null || proto.id == null || proto.id.isBlank()) return;
        ComponentCategory cat = proto.category != null ? proto.category : ComponentCategory.GENERIC;
        Path dir = getPrototypeDir(cat, proto.id);
        Path pj = dir.resolve("prototype.json");
        try {
            Files.createDirectories(dir);
            try (Writer w = Files.newBufferedWriter(pj, StandardCharsets.UTF_8)) {
                JsonUtil.get().toJson(proto, w);
            }
        } catch (Throwable t) {
            LOG.warn("save prototype failed componentId={} path={}", proto.id, pj, t);
            return;
        }

        // upsert catalog entry（不扫描整个目录）
        ComponentPrototypeCatalog c = loadCatalog();
        if (c.prototypes == null) c.prototypes = new ArrayList<>();
        c.prototypes.removeIf(e -> e != null && proto.id.equals(e.id));
        ComponentPrototypeCatalog.Entry e = new ComponentPrototypeCatalog.Entry();
        e.id = proto.id;
        e.name = proto.name;
        e.category = cat;
        e.tags = proto.tags;
        e.updatedAtMs = System.currentTimeMillis();
        c.prototypes.add(e);
        saveCatalog(c);
    }

    /**
     * 从现有的 ComponentDefinition(v1) 一键导入为 Prototype(v1)。
     * <p>
     * v1 导入策略：
     * - 在 prototype 目录内写入：
     *   - prototype.json（ComponentPrototype）
     *   - component.json（原始 ComponentDefinition JSON，作为 structure 载体）
     * - structure.format = "component_v1_json"
     * - placement 从 def.placementSpec（若存在）桥接
     */
    public static ComponentPrototype importFromComponentDefinition(ComponentDefinition def) {
        if (def == null || def.id == null || def.id.isBlank()) return null;

        ComponentPrototype proto = new ComponentPrototype();
        proto.id = def.id;
        proto.name = def.name;
        proto.category = def.category != null ? def.category : ComponentCategory.GENERIC;
        proto.tags = def.tags;

        // structure -> component.json（v1 兼容路径）
        ComponentPrototype.StructureRef sr = new ComponentPrototype.StructureRef();
        sr.format = "component_v1_json";
        sr.file = "component.json";
        if (def.size != null) {
            sr.bounds = new ComponentPrototype.StructureRef.Bounds();
            sr.bounds.w = def.size.w;
            sr.bounds.h = def.size.h;
            sr.bounds.d = def.size.d;
        }
        if (def.anchor != null) {
            sr.anchor = new ComponentPrototype.StructureRef.Anchor();
            sr.anchor.x = def.anchor.dx;
            sr.anchor.y = def.anchor.dy;
            sr.anchor.z = def.anchor.dz;
            sr.default_facing = def.anchor.facing;
        }
        proto.structure = sr;

        // placement（桥接到 prototype.json 的 snake_case 结构）
        proto.placement = placementFromSpec(def.placementSpec);
        proto.variant_rules = null; // v1：导入时不猜测规则，后续可由 AI/玩家补齐

        // 1) save prototype.json + catalog
        savePrototype(proto);

        // 2) save component.json（原始 v1 结构）
        try {
            Path dir = getPrototypeDir(proto.category, proto.id);
            Files.createDirectories(dir);
            Path f = dir.resolve("component.json");
            try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                JsonUtil.get().toJson(def, w);
            }
        } catch (Throwable t) {
            LOG.warn("save component.json failed componentId={}", proto.id, t);
        }

        return proto;
    }

    public static void saveVariant(PersistedComponentVariant variant, ComponentCategory prototypeCategory) {
        if (variant == null || variant.prototype_id == null || variant.prototype_id.isBlank()) return;
        if (variant.variant_id == null || variant.variant_id.isBlank()) return;
        ComponentCategory cat = prototypeCategory != null ? prototypeCategory : ComponentCategory.GENERIC;
        Path vdir = getVariantDir(cat, variant.prototype_id);
        Path vf = vdir.resolve(safeId(variant.variant_id) + ".json");
        try {
            Files.createDirectories(vdir);
            try (Writer w = Files.newBufferedWriter(vf, StandardCharsets.UTF_8)) {
                JsonUtil.get().toJson(variant, w);
            }
        } catch (Throwable t) {
            LOG.warn("save variant failed prototypeId={} variantId={}", variant.prototype_id, variant.variant_id, t);
        }
    }

    public static PersistedComponentVariant loadVariant(String prototypeId, String variantId, ComponentCategory prototypeCategory) {
        if (prototypeId == null || prototypeId.isBlank()) return null;
        if (variantId == null || variantId.isBlank()) return null;
        ComponentCategory cat = prototypeCategory != null ? prototypeCategory : ComponentCategory.GENERIC;
        Path vf = getVariantDir(cat, prototypeId).resolve(safeId(variantId) + ".json");
        if (!Files.exists(vf)) {
            // legacy fallback
            Path legacyVdir = getLegacyPrototypesRoot()
                    .resolve((cat != null ? cat.name() : ComponentCategory.GENERIC.name()).toLowerCase(Locale.ROOT))
                    .resolve(safeId(prototypeId))
                    .resolve("variants");
            vf = legacyVdir.resolve(safeId(variantId) + ".json");
        }
        if (!Files.exists(vf)) return null;
        try (Reader r = Files.newBufferedReader(vf, StandardCharsets.UTF_8)) {
            return JsonUtil.get().fromJson(r, PersistedPersistedComponentVariant.class);
        } catch (Throwable t) {
            LOG.warn("load variant failed prototypeId={} variantId={} path={}", prototypeId, variantId, vf, t);
            return null;
        }
    }

    /**
     * 读取可选的 variants.json（预置变体集合）。
     * <p>
     * 支持两种 JSON 形态：
     * - 直接数组：[{...variant...}, ...]
     * - 包装对象：{ "variants": [ ... ] }
     */
    public static List<PersistedComponentVariant> loadPresetVariants(String prototypeId, ComponentCategory prototypeCategory) {
        if (prototypeId == null || prototypeId.isBlank()) return List.of();
        ComponentCategory cat = prototypeCategory != null ? prototypeCategory : ComponentCategory.GENERIC;

        Path dir = getPrototypeDir(cat, prototypeId);
        Path f = dir.resolve("variants.json");
        if (!Files.exists(f)) {
            // legacy fallback
            f = getLegacyPrototypeDir(cat, prototypeId).resolve("variants.json");
        }
        if (!Files.exists(f)) return List.of();

        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            ComponentVariantPresetList box = JsonUtil.get().fromJson(r, ComponentVariantPresetList.class);
            if (box != null && box.variants != null && !box.variants.isEmpty()) {
                return box.variants;
            }
        } catch (Throwable t) {
            LOG.warn("load preset variants (object form) failed componentId={} path={}", prototypeId, f, t);
        }

        // fallback: array form
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            PersistedPersistedComponentVariant[] arr = JsonUtil.get().fromJson(r, PersistedPersistedComponentVariant[].class);
            if (arr == null || arr.length == 0) return List.of();
            return Arrays.asList(arr);
        } catch (Throwable t) {
            LOG.warn("load preset variants (array form) failed componentId={} path={}", prototypeId, f, t);
            return List.of();
        }
    }

    private static ComponentPrototypeCatalog.Entry find(ComponentPrototypeCatalog cat, String prototypeId) {
        if (cat == null || cat.prototypes == null) return null;
        for (ComponentPrototypeCatalog.Entry e : cat.prototypes) {
            if (e == null || e.id == null) continue;
            if (e.id.equals(prototypeId)) return e;
        }
        return null;
    }

    private static ComponentPrototype.Placement placementFromSpec(ComponentPlacementSpec spec) {
        if (spec == null) return null;
        ComponentPrototype.Placement p = new ComponentPrototype.Placement();
        p.attachment = spec.attachment != null ? spec.attachment.name() : null;
        p.spatial_context = spec.spatialContext != null ? spec.spatialContext.name() : null;
        p.facing_policy = spec.facingPolicy != null ? spec.facingPolicy.name() : null;
        p.has_interior_exterior = spec.hasInteriorExterior;

        ComponentPrototype.Placement.Constraints c = new ComponentPrototype.Placement.Constraints();
        if (spec.constraints != null) {
            c.requires_attachment = spec.constraints.requiresAttachment;
            c.min_attachments = spec.constraints.minAttachments;
            c.max_attachments = spec.constraints.maxAttachments;
            c.requires_edge = spec.constraints.requiresEdge;
            c.requires_support_below = spec.constraints.requiresSupportBelow;
            c.forbid_interior = spec.constraints.forbidInterior;
            c.respect_protected_zones = spec.constraints.respectProtectedZones;
            c.prefers_continuity = spec.constraints.prefersContinuity;
            c.min_height = spec.constraints.minHeight;
            c.max_height = spec.constraints.maxHeight;
        }
        p.constraints = c;
        return p;
    }

    private static String safeId(String s) {
        if (s == null) return "unknown";
        String t = s.trim();
        if (t.isEmpty()) return "unknown";
        // 文件夹/文件名安全化（尽量不改变可读性）
        t = t.replace('\\', '_').replace('/', '_').replace(':', '_');
        return t;
    }

    private static String categoryFolder(ComponentCategory category) {
        ComponentCategory c = category != null ? category : ComponentCategory.GENERIC;
        // 与建议布局保持一致（复数 + 小写）
        return switch (c) {
            case DOOR -> "doors";
            case WINDOW -> "windows";
            case COLUMN -> "columns";
            case BRACKET -> "brackets";
            case ORNAMENT -> "ornaments";
            case ARCH -> "arches";
            case ROOF_DETAIL -> "roof_details";
            case STAIRS -> "stairs";
            default -> "generic";
        };
    }

    /**
     * 扫描一个 root 目录，将 prototype.json 加入 catalog（best-effort）。
     * <p>
     * - root 是“category folder 的父目录”
     * - depth=2: <root>/<categoryFolder>/<prototypeId>/prototype.json
     */
    private static void scanRoot(ComponentPrototypeCatalog out, Path root) {
        if (out == null || out.prototypes == null) return;
        if (root == null || !Files.exists(root) || !Files.isDirectory(root)) return;
        try (DirectoryStream<Path> cats = Files.newDirectoryStream(root)) {
            for (Path catDir : cats) {
                if (catDir == null || !Files.isDirectory(catDir)) continue;
                try (DirectoryStream<Path> ids = Files.newDirectoryStream(catDir)) {
                    for (Path idDir : ids) {
                        if (idDir == null || !Files.isDirectory(idDir)) continue;
                        Path pj = idDir.resolve("prototype.json");
                        if (!Files.exists(pj)) continue;
                        try (Reader r = Files.newBufferedReader(pj, StandardCharsets.UTF_8)) {
                            ComponentPrototype p = JsonUtil.get().fromJson(r, ComponentPrototype.class);
                            if (p == null || p.id == null || p.id.isBlank()) continue;
                            // 去重：同 id 时，以“先扫描到的”为准；rebuildCatalog() 会先扫描推荐目录再扫 legacy
                            boolean exists = false;
                            for (ComponentPrototypeCatalog.Entry e0 : out.prototypes) {
                                if (e0 != null && p.id.equals(e0.id)) { exists = true; break; }
                            }
                            if (exists) continue;

                            ComponentPrototypeCatalog.Entry e = new ComponentPrototypeCatalog.Entry();
                            e.id = p.id;
                            e.name = p.name;
                            e.category = p.category != null ? p.category : ComponentCategory.GENERIC;
                            e.tags = p.tags;
                            try {
                                e.updatedAtMs = Files.getLastModifiedTime(pj).toMillis();
                            } catch (Throwable t) {
                                LOG.debug("read prototype mtime failed path={}", pj, t);
                            }
                            out.prototypes.add(e);
                        } catch (Throwable t) {
                            LOG.debug("scan prototype entry failed path={}", pj, t);
                        }
                    }
                } catch (Throwable t) {
                    LOG.debug("scan prototype category dir failed path={}", catDir, t);
                }
            }
        } catch (Throwable t) {
            LOG.warn("scan prototype root failed path={}", root, t);
        }
    }
}

