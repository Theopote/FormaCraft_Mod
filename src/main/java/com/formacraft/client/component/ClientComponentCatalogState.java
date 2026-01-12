package com.formacraft.client.component;

import com.formacraft.common.component.ComponentCatalog;
import com.formacraft.common.json.JsonUtil;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 客户端侧缓存的“世界构件目录”（从服务端同步）。
 *
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
        }
        return sb.toString().trim();
    }
}

