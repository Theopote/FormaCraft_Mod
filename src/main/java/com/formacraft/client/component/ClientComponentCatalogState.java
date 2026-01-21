package com.formacraft.client.component;

import com.formacraft.common.component.ComponentCatalog;
import com.formacraft.common.json.JsonUtil;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 客户端侧缓存的“世界构件目录”（从服务端同步）。
 * <p>
 * v1：仅用于 PromptAssembler 注入摘要 + 工具 UI 展示。
 */
public final class ClientComponentCatalogState {
    private ClientComponentCatalogState() {}

    private static final AtomicReference<ComponentCatalog> CATALOG = new AtomicReference<>(null);
    private static volatile String lastSummary = "(no player components registered)";

    public static ComponentCatalog getCatalog() {
        return CATALOG.get();
    }

    public static String getSummary() {
        return lastSummary;
    }

    public static void setFromJson(String json) {
        try {
            if (json == null || json.isBlank()) {
                CATALOG.set(null);
                lastSummary = "(no player components registered)";
                return;
            }
            ComponentCatalog cat = JsonUtil.fromJson(json, ComponentCatalog.class);
            CATALOG.set(cat);
            lastSummary = buildSummary(cat);
        } catch (Throwable t) {
            // best-effort：失败时不让 prompt 崩
            CATALOG.set(null);
            lastSummary = "(failed to load player components)";
        }
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
}

