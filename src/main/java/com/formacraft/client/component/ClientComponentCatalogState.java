package com.formacraft.client.component;

import com.formacraft.common.component.ComponentCatalog;
import com.formacraft.common.json.JsonUtil;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 客户端侧缓存的“世界构件目录”（从服务端同步）。
 * <p>
 * v1：工具 UI 展示；服务端另经 BuildPromptContextEnricher 注入后端 prompt。
 */
public final class ClientComponentCatalogState {
    private ClientComponentCatalogState() {}

    private static final AtomicReference<ComponentCatalog> CATALOG = new AtomicReference<>(null);
    private static volatile String lastSummary = "(no player components registered)";
    private static volatile boolean syncPending = false;
    private static volatile boolean syncComplete = false;
    private static boolean allowDiskFallback;

    public static ComponentCatalog getCatalog() {
        return CATALOG.get();
    }

    public static String getSummary() {
        hydrateFromDiskIfEmpty();
        return lastSummary;
    }

    /** 客户端缓存或服务端同步/磁盘上是否存在可用构件。 */
    public static boolean hasRegisteredComponents() {
        ComponentCatalog cat = CATALOG.get();
        if (cat != null && cat.components != null && !cat.components.isEmpty()) {
            return true;
        }
        return hydrateFromDiskIfEmpty();
    }

    public static boolean isSyncPending() {
        return syncPending;
    }

    public static boolean isSyncComplete() {
        return syncComplete;
    }

    public static void markSyncRequested() {
        syncPending = true;
        syncComplete = false;
    }

    /**
     * 当服务端 catalog 尚未到达时，从全局磁盘目录回填（best-effort）。
     */
    public static boolean hydrateFromDiskIfEmpty() {
        if (!allowDiskFallback || syncComplete) return false;
        ComponentCatalog current = CATALOG.get();
        if (current != null && current.components != null && !current.components.isEmpty()) {
            return true;
        }
        try {
            ComponentCatalog disk = com.formacraft.common.component.ComponentStorage.loadCatalogWithSockets(null);
            if (disk != null && disk.components != null && !disk.components.isEmpty()) {
                CATALOG.set(disk);
                lastSummary = buildSummary(disk);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static void beginSession(boolean integrated) {
        CATALOG.set(null); lastSummary = "(no player components registered)";
        syncPending = syncComplete = false; allowDiskFallback = integrated;
    }

    public static void syncFailed() { syncPending = false; syncComplete = false; }

    public static void setFromJson(String json) {
        try {
            ComponentCatalog cat = JsonUtil.fromJson(json, ComponentCatalog.class);
            if (cat == null || !"formacraft.component_catalog.v1".equals(cat.schema) || cat.components == null)
                throw new IllegalArgumentException("Invalid component catalog");
            cat.components.removeIf(e -> e == null || !com.formacraft.common.component.ComponentIds.isSafeId(e.id));
            String summary = buildSummary(cat);
            CATALOG.set(cat); lastSummary = summary;
            syncPending = false; syncComplete = true;
        } catch (RuntimeException invalid) { syncFailed(); }
    }

    /**
     * 从当前 catalog 中移除指定构件（用于立即更新 UI，无需等待服务端响应）
     * @param componentId 要移除的构件 ID
     */
    public static void removeComponent(String componentId) {
        if (componentId == null || componentId.isBlank()) return;
        
        ComponentCatalog current = CATALOG.get();
        if (current == null || current.components == null) return;
        
        // 创建新的 catalog，移除指定条目
        ComponentCatalog updated = new ComponentCatalog();
        updated.schema = current.schema;
        updated.components = new java.util.ArrayList<>();
        
        for (ComponentCatalog.Entry e : current.components) {
            if (e != null && e.id != null && !e.id.equals(componentId)) {
                updated.components.add(e);
            }
        }
        
        CATALOG.set(updated);
        lastSummary = buildSummary(updated);
    }

    private static String buildSummary(ComponentCatalog cat) {
        if (cat == null || cat.components == null || cat.components.isEmpty()) {
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
            if (e.size != null) sb.append(" size=").append(e.size.w).append("x").append(e.size.h).append("x").append(e.size.d);
            sb.append("\n");

            if (e.variantRules != null) {
                sb.append("  - adaptation: materialSwap=").append(e.variantRules.material != null);
                var scaling = e.variantRules.scaling;
                if (scaling != null && scaling.axes != null) for (String axis : new String[]{"X", "Y", "Z"}) {
                    var rule = scaling.axes.get(axis);
                    if (rule == null) continue;
                    sb.append(" ").append(axis).append("=").append(rule.type);
                    if (!"FIXED".equalsIgnoreCase(rule.type)) sb.append("[").append(rule.min)
                            .append("..").append(rule.max).append(",fixedEnds=")
                            .append(rule.fixedStart).append("+").append(rule.fixedEnd).append("]");
                }
                sb.append("\n");
            }

            // placement spec（Attachment/Context/Policy）
            if (e.placementSpec != null) {
                var ps = e.placementSpec;
                sb.append("  - placement")
                        .append(" attachment=").append(ps.attachment)
                        .append(" context=").append(ps.spatialContext)
                        .append(" facingPolicy=").append(ps.facingPolicy);
                if (ps.hasInteriorExterior) sb.append(" interiorExterior=true");
                if (ps.semanticTags != null && !ps.semanticTags.isEmpty()) sb.append(" tags=").append(ps.semanticTags);
                if (ps.aiHint != null && !ps.aiHint.isBlank()) sb.append(" hint=").append(ps.aiHint.trim());
                sb.append("\n");
            }

            // sockets（用于 mount / 自动开洞 / 对齐）
            if (e.sockets != null && !e.sockets.isEmpty()) {
                for (var s : e.sockets) {
                    if (s == null) continue;
                    sb.append("  - socket.")
                            .append(s.id)
                            .append(" role=").append(s.role)
                            .append(" shape=").append(s.shape)
                            .append(" context=").append(s.context)
                            .append(" facingPolicy=").append(s.facingPolicy);
                    appendSocketOrigin(sb, e, s.id);
                    if (s.size != null && s.size.min != null && s.size.min.length > 0) {
                        if (s.size.min.length == 1) {
                            sb.append(" size=[width=").append(s.size.min[0]).append("-").append(s.size.max[0]).append("]");
                        } else {
                            sb.append(" size=[width=").append(s.size.min[0]).append("-").append(s.size.max[0])
                                    .append(" height=").append(s.size.min[1]).append("-").append(s.size.max[1]).append("]");
                        }
                    }
                    if (s.tags != null && !s.tags.isEmpty()) {
                        sb.append(" tags=").append(s.tags);
                    }
                    sb.append("\n");
                }
            }
        }
        return sb.toString().trim();
    }

    private static void appendSocketOrigin(StringBuilder sb, ComponentCatalog.Entry entry, String socketId) {
        if (!allowDiskFallback || entry == null || socketId == null || socketId.isBlank()) {
            return;
        }
        com.formacraft.common.component.ComponentDefinition def =
                com.formacraft.common.component.ComponentStorage.loadComponent(null, entry.id);
        if (def == null || def.socketPlacements == null) {
            return;
        }
        for (var sp : def.socketPlacements) {
            if (sp == null || sp.id == null || !socketId.equals(sp.id)) continue;
            sb.append(" origin=(").append(sp.dx).append(",").append(sp.dy).append(",").append(sp.dz).append(")");
            if (sp.facing != null && !sp.facing.isBlank()) {
                sb.append(" facing=").append(sp.facing);
            }
            return;
        }
    }
}

