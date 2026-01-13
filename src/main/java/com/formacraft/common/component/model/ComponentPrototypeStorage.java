package com.formacraft.common.component.model;

import com.formacraft.common.component.ComponentCategory;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.ComponentStorage;
import com.formacraft.common.component.placement.ComponentPlacementSpec;
import com.formacraft.common.json.JsonUtil;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Prototype/Variant/Instance 的最小存储实现（v1）。
 * <p>
 * 目标：先把三层模型“落盘形态”固定下来，不影响现有 ComponentDefinition(v1) 组件库。
 * <p>
 * 全局（跨存档）目录：
 * <config>/formacraft/components/prototypes/<category>/<id>/prototype.json
 * <config>/formacraft/components/prototypes/<category>/<id>/variants/<variantId>.json
 * <config>/formacraft/components/prototype_catalog.json
 */
public final class ComponentPrototypeStorage {
    private ComponentPrototypeStorage() {}

    public static Path getGlobalPrototypesRoot() {
        return ComponentStorage.getGlobalComponentDir().resolve("prototypes");
    }

    public static Path getGlobalPrototypeCatalogFile() {
        return ComponentStorage.getGlobalComponentDir().resolve("prototype_catalog.json");
    }

    public static Path getPrototypeDir(ComponentCategory category, String prototypeId) {
        String cat = (category != null ? category.name() : ComponentCategory.GENERIC.name());
        return getGlobalPrototypesRoot()
                .resolve(cat.toLowerCase(Locale.ROOT))
                .resolve(safeId(prototypeId));
    }

    public static Path getPrototypeJsonFile(ComponentCategory category, String prototypeId) {
        return getPrototypeDir(category, prototypeId).resolve("prototype.json");
    }

    public static Path getVariantDir(ComponentCategory category, String prototypeId) {
        return getPrototypeDir(category, prototypeId).resolve("variants");
    }

    public static ComponentPrototypeCatalog loadCatalog() {
        Path f = getGlobalPrototypeCatalogFile();
        if (!Files.exists(f)) {
            ComponentPrototypeCatalog c = new ComponentPrototypeCatalog();
            c.prototypes = new ArrayList<>();
            return c;
        }
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            ComponentPrototypeCatalog c = JsonUtil.get().fromJson(r, ComponentPrototypeCatalog.class);
            if (c == null) c = new ComponentPrototypeCatalog();
            if (c.prototypes == null) c.prototypes = new ArrayList<>();
            return c;
        } catch (Throwable ignored) {
            ComponentPrototypeCatalog c = new ComponentPrototypeCatalog();
            c.prototypes = new ArrayList<>();
            return c;
        }
    }

    public static void saveCatalog(ComponentPrototypeCatalog catalog) {
        if (catalog == null) return;
        try {
            Files.createDirectories(ComponentStorage.getGlobalComponentDir());
            Path f = getGlobalPrototypeCatalogFile();
            try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                JsonUtil.get().toJson(catalog, w);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 扫描 prototypes 目录重建 catalog（best-effort）。
     * <p>
     * v1：仅扫描 depth=2（category/id）并读取 prototype.json。
     */
    public static ComponentPrototypeCatalog rebuildCatalog() {
        ComponentPrototypeCatalog out = new ComponentPrototypeCatalog();
        out.prototypes = new ArrayList<>();
        Path root = getGlobalPrototypesRoot();
        if (!Files.exists(root)) return out;

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
                            ComponentPrototypeCatalog.Entry e = new ComponentPrototypeCatalog.Entry();
                            e.id = p.id;
                            e.name = p.name;
                            e.category = p.category != null ? p.category : ComponentCategory.GENERIC;
                            e.tags = p.tags;
                            try { e.updatedAtMs = Files.getLastModifiedTime(pj).toMillis(); } catch (Throwable ignored) {}
                            out.prototypes.add(e);
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

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

        Path pj = getPrototypeJsonFile(hit.category, pid);
        if (!Files.exists(pj)) return null;
        try (Reader r = Files.newBufferedReader(pj, StandardCharsets.UTF_8)) {
            return JsonUtil.get().fromJson(r, ComponentPrototype.class);
        } catch (Throwable ignored) {
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
        } catch (Throwable ignored) {
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
        } catch (Throwable ignored) {}

        return proto;
    }

    public static void saveVariant(ComponentVariant variant, ComponentCategory prototypeCategory) {
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
        } catch (Throwable ignored) {}
    }

    public static ComponentVariant loadVariant(String prototypeId, String variantId, ComponentCategory prototypeCategory) {
        if (prototypeId == null || prototypeId.isBlank()) return null;
        if (variantId == null || variantId.isBlank()) return null;
        ComponentCategory cat = prototypeCategory != null ? prototypeCategory : ComponentCategory.GENERIC;
        Path vf = getVariantDir(cat, prototypeId).resolve(safeId(variantId) + ".json");
        if (!Files.exists(vf)) return null;
        try (Reader r = Files.newBufferedReader(vf, StandardCharsets.UTF_8)) {
            return JsonUtil.get().fromJson(r, ComponentVariant.class);
        } catch (Throwable ignored) {
            return null;
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
        try { p.attachment = spec.attachment != null ? spec.attachment.name() : null; } catch (Throwable ignored) {}
        try { p.spatial_context = spec.spatialContext != null ? spec.spatialContext.name() : null; } catch (Throwable ignored) {}
        try { p.facing_policy = spec.facingPolicy != null ? spec.facingPolicy.name() : null; } catch (Throwable ignored) {}
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
}

