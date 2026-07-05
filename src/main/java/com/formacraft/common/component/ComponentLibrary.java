package com.formacraft.common.component;

import com.formacraft.common.component.query.ComponentQuery;
import com.formacraft.common.component.query.ComponentQueryMatchUtil;
import com.formacraft.common.component.query.ComponentRequestConverter;
import com.formacraft.common.component.query.ComponentRetriever;
import com.formacraft.common.component.variant.ComponentVariantApplier;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * 构件库检索（legacy API）：优先走 {@link ComponentRetriever}，失败时回退到本地打分。
 */
public final class ComponentLibrary {
    private ComponentLibrary() {}

    public static ComponentDefinition findBest(Path worldDir, ComponentRequest req) {
        ComponentDefinition viaRetriever = findBestViaRetriever(worldDir, req);
        if (viaRetriever != null) {
            return viaRetriever;
        }
        return findBestLegacyScore(worldDir, req);
    }

    private static ComponentDefinition findBestViaRetriever(Path worldDir, ComponentRequest req) {
        ComponentQuery query = ComponentRequestConverter.fromRequest(req);
        if (query == null) {
            return null;
        }
        Random rng = new Random(ComponentRequestConverter.stableSeed(req));
        ComponentRetriever.VariantResult vr = ComponentRetriever.retrieveBestWithVariant(query, rng);
        if (vr == null || vr.base() == null) {
            return null;
        }
        ComponentDefinition applied = ComponentVariantApplier.apply(vr.base(), vr.variant());
        return applied != null ? applied : vr.base();
    }

    /** legacy：category + fuzzy tags + size hint 打分（Retriever 无结果时使用）。 */
    private static ComponentDefinition findBestLegacyScore(Path worldDir, ComponentRequest req) {
        ComponentCatalog cat = ComponentStorage.loadCatalog(worldDir);
        if (cat.components == null || cat.components.isEmpty()) return null;

        List<ComponentCatalog.Entry> candidates = cat.components.stream()
                .filter(Objects::nonNull)
                .toList();

        double bestScore = Double.NEGATIVE_INFINITY;
        ComponentCatalog.Entry best = null;

        for (ComponentCatalog.Entry e : candidates) {
            double s = scoreEntry(e, req);
            if (s > bestScore) {
                bestScore = s;
                best = e;
            }
        }

        if (best == null || bestScore < 0) return null;
        return ComponentStorage.loadComponent(worldDir, best.id);
    }

    private static double scoreEntry(ComponentCatalog.Entry e, ComponentRequest req) {
        double s = 0;

        if (req != null && req.category != null && e.category != null) {
            if (req.category == e.category) {
                s += 25;
            } else if (relatedCategory(req.category, e.category)) {
                s += 10;
            } else {
                s -= 8;
            }
        }

        if (req != null && req.tags != null && e.tags != null) {
            int hit = 0;
            for (String tag : req.tags) {
                if (ComponentQueryMatchUtil.tagMatches(tag, e.tags)) {
                    hit++;
                }
            }
            s += hit * 10.0;
        }

        if (req != null && e.size != null) {
            if (req.approxW > 0) s -= Math.abs(req.approxW - e.size.w);
            if (req.approxH > 0) s -= Math.abs(req.approxH - e.size.h) * 0.5;
            if (req.approxD > 0) s -= Math.abs(req.approxD - e.size.d);
        }

        return s;
    }

    private static boolean relatedCategory(ComponentCategory a, ComponentCategory b) {
        if (a == b) return true;
        Set<ComponentCategory> edgeFamily = Set.of(
                ComponentCategory.RAILING, ComponentCategory.BALCONY, ComponentCategory.PANEL);
        if (edgeFamily.contains(a) && edgeFamily.contains(b)) {
            return true;
        }
        Set<ComponentCategory> ornamentFamily = Set.of(
                ComponentCategory.ORNAMENT, ComponentCategory.BRACKET, ComponentCategory.ROOF_DETAIL, ComponentCategory.ARCH);
        return ornamentFamily.contains(a) && ornamentFamily.contains(b);
    }
}
