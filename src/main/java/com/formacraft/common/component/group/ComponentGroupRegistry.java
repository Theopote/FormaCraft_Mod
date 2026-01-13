package com.formacraft.common.component.group;

import com.formacraft.common.component.socket.ComponentSocket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ComponentGroupRegistry：注册可用的 ComponentGroup。
 *
 * 说明：
 * - v1：代码侧注册（后续可扩展到 world save / datapack）
 * - 用于 PromptAssembler 注入“可用 group 列表”，让 LLM 只做选组规划
 */
public final class ComponentGroupRegistry {
    private ComponentGroupRegistry() {}

    private static final Map<String, ComponentGroup> REG = new ConcurrentHashMap<>();

    public static void register(ComponentGroup group) {
        if (group == null || group.getId() == null || group.getId().isBlank()) return;
        REG.put(norm(group.getId()), group);
    }

    public static ComponentGroup get(String id) {
        if (id == null || id.isBlank()) return null;
        return REG.get(norm(id));
    }

    public static java.util.List<ComponentGroup> list() {
        if (REG.isEmpty()) return java.util.List.of();
        ArrayList<ComponentGroup> out = new ArrayList<>(REG.values());
        out.sort(Comparator.comparing(g -> norm(g.getId())));
        return Collections.unmodifiableList(out);
    }

    public static String summary() {
        java.util.List<ComponentGroup> groups = list();
        if (groups.isEmpty()) return "(no component groups registered)";

        StringBuilder sb = new StringBuilder();
        for (ComponentGroup g : groups) {
            if (g == null) continue;
            sb.append("- group: ").append(g.getId());
            if (g.getDisplayName() != null && !g.getDisplayName().isBlank()) {
                sb.append(" (").append(g.getDisplayName().trim()).append(")");
            }
            int n = (g.getComponents() != null) ? g.getComponents().size() : 0;
            sb.append(" components=").append(n).append("\n");

            if (g.getSockets() != null && !g.getSockets().isEmpty()) {
                for (ComponentSocket s : g.getSockets()) {
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

    private static String norm(String s) {
        return s.trim().toUpperCase(Locale.ROOT);
    }
}

