package com.formacraft.common.component;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * v1：本地规则匹配（category + tags overlap + size hint）。
 */
public final class ComponentLibrary {
    private ComponentLibrary() {}

    public static ComponentDefinition findBest(Path worldDir, ComponentRequest req) {
        ComponentCatalog cat = ComponentStorage.loadCatalog(worldDir);
        if (cat.components == null || cat.components.isEmpty()) return null;

        List<ComponentCatalog.Entry> candidates = cat.components.stream()
                .filter(Objects::nonNull)
                .filter(e -> req == null || req.category == null || e.category == req.category)
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            candidates = cat.components.stream().filter(Objects::nonNull).toList();
        }

        double bestScore = Double.NEGATIVE_INFINITY;
        ComponentCatalog.Entry best = null;

        for (ComponentCatalog.Entry e : candidates) {
            double s = 0;

            // tag overlap（权重大）
            if (req != null && req.tags != null && e.tags != null) {
                Set<String> a = lower(req.tags);
                Set<String> b = lower(new HashSet<>(e.tags));
                int inter = 0;
                for (String t : a) if (b.contains(t)) inter++;
                s += inter * 10.0;
            }

            // size hint（惩罚绝对差）
            if (req != null && e.size != null) {
                if (req.approxW > 0) s -= Math.abs(req.approxW - e.size.w);
                if (req.approxH > 0) s -= Math.abs(req.approxH - e.size.h) * 0.5;
                if (req.approxD > 0) s -= Math.abs(req.approxD - e.size.d);
            }

            if (s > bestScore) {
                bestScore = s;
                best = e;
            }
        }

        if (best == null) return null;
        return ComponentStorage.loadComponent(worldDir, best.id);
    }

    private static Set<String> lower(Set<String> s) {
        Set<String> out = new HashSet<>();
        if (s == null) return out;
        for (String x : s) {
            if (x == null) continue;
            String t = x.trim();
            if (!t.isEmpty()) out.add(t.toLowerCase());
        }
        return out;
    }
}

