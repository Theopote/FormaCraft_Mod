package com.formacraft.common.component.rank;

import com.formacraft.common.component.query.ComponentMetadata;
import com.formacraft.common.component.query.ComponentQuery;
import com.formacraft.common.component.query.ComponentQueryMatchUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ComponentRanker
 * <p>
 * 将候选构件按照"合适程度"排序
 * <p>
 * 设计原则：
 * - 完全确定性（Deterministic）
 * - 所有权重集中管理
 * - 可以热插拔评分维度
 * - 输出可解释
 */
public class ComponentRanker {

    /* ----------------------------
     * 权重配置（v1 可硬编码）
     * ---------------------------- */

    private static final double W_SEMANTIC  = 0.30;
    private static final double W_PLACEMENT = 0.25;
    private static final double W_GEOMETRY  = 0.20;
    private static final double W_STYLE     = 0.15;
    private static final double W_USAGE     = 0.10;

    /* ----------------------------
     * 排序入口
     * ---------------------------- */

    /**
     * 对候选构件进行排序
     * 
     * @param query 查询条件
     * @param candidates 候选构件元数据列表
     * @return 排序后的评分结果（按分数降序）
     */
    public static List<ScoredComponent> rank(
            ComponentQuery query,
            List<ComponentMetadata> candidates
    ) {
        List<ScoredComponent> scored = new ArrayList<>();

        for (ComponentMetadata meta : candidates) {
            double score = score(query, meta);
            if (score > 0) {
                scored.add(new ScoredComponent(meta, score));
            }
        }

        scored.sort(Comparator.comparingDouble(ScoredComponent::score).reversed());
        return scored;
    }

    /* ----------------------------
     * 总评分
     * ---------------------------- */

    private static double score(ComponentQuery q, ComponentMetadata m) {
        return
            semanticScore(q, m)  * W_SEMANTIC +
            placementScore(q, m) * W_PLACEMENT +
            geometryScore(q, m)  * W_GEOMETRY +
            styleScore(q, m)     * W_STYLE +
            usageScore(q, m)     * W_USAGE;
    }

    /* ----------------------------
     * 各维度评分
     * ---------------------------- */

    private static double semanticScore(ComponentQuery q, ComponentMetadata m) {
        if (q.semantic == null || q.semantic.role == null || q.semantic.role.isBlank()) {
            return 0.5;
        }

        if (m.semantic == null) {
            return 0.2;
        }

        double score;
        if (m.semantic.role != null
                && ComponentQueryMatchUtil.equalsIgnoreCase(q.semantic.role, m.semantic.role)) {
            score = 1.0;
        } else if (ComponentQueryMatchUtil.roleImpliedByTags(q.semantic.role, m.semantic.tags)) {
            score = 0.72;
        } else if ("decoration".equalsIgnoreCase(m.semantic.role)) {
            score = 0.25;
        } else {
            score = 0.12;
        }

        if (q.semantic.geometryArchetype != null && !q.semantic.geometryArchetype.isBlank()) {
            if (m.semantic.geometryArchetype == null) {
                score *= 0.55;
            } else if (!ComponentQueryMatchUtil.equalsIgnoreCase(q.semantic.geometryArchetype, m.semantic.geometryArchetype)) {
                score *= 0.35;
            }
        }

        if (q.semantic.tags == null || q.semantic.tags.isEmpty()) {
            return score;
        }

        if (m.semantic.tags == null || m.semantic.tags.isEmpty()) {
            return score * 0.45;
        }

        int hit = 0;
        for (String tag : q.semantic.tags) {
            if (ComponentQueryMatchUtil.tagMatches(tag, m.semantic.tags)) {
                hit++;
            }
        }

        return score * (0.35 + 0.65 * ((double) hit / q.semantic.tags.size()));
    }

    private static double placementScore(ComponentQuery q, ComponentMetadata m) {
        if (q.context == null || q.context.placement == null || q.context.placement.isBlank()) {
            return 0.5;
        }

        if (m.placementSpec == null || m.placementSpec.allowedPlacements == null
                || m.placementSpec.allowedPlacements.isEmpty()) {
            return 0.35;
        }

        if (!ComponentQueryMatchUtil.placementMatches(q.context.placement, m.placementSpec.allowedPlacements)) {
            return 0.18;
        }

        double score = 1.0;

        if (q.context.side != null && m.placementSpec.sidePolicy != null) {
            if (!m.placementSpec.isSideAllowed(q.context.side)) {
                score *= 0.45;
            }
        }

        if (q.context.edgeCondition != null && m.placementSpec.edgePreference != null) {
            if (ComponentQueryMatchUtil.equalsIgnoreCase(q.context.edgeCondition, m.placementSpec.edgePreference)) {
                score += 0.2;
            }
        }

        return Math.min(score, 1.2);
    }

    private static double geometryScore(ComponentQuery q, ComponentMetadata m) {
        if (q.geometry == null) {
            return 0.5;
        }

        if (m.geometrySpec == null) {
            return 0.25;
        }

        double score = 0.8;

        if (q.geometry.requiresOpening && !m.geometrySpec.requiresOpening) {
            score *= 0.35;
        }

        if (!q.geometry.scalable && (m.geometrySpec.scalableAxes == null || m.geometrySpec.scalableAxes.isEmpty())) {
            score *= 0.5;
        }

        if (q.geometry.openingWidth != null && q.geometry.openingHeight != null) {
            int dw = Math.abs(q.geometry.openingWidth - m.geometrySpec.getBaseWidth());
            int dh = Math.abs(q.geometry.openingHeight - m.geometrySpec.getBaseHeight());
            int tol = Math.max(1, q.geometry.tolerance);

            if (dw <= tol && dh <= tol) {
                score = Math.max(score, 1.0 - (dw + dh) * 0.08);
            } else {
                double penalty = (dw + dh) * 0.12;
                score = Math.max(0.15, 0.85 - penalty);
            }
        }

        return score;
    }

    private static double styleScore(ComponentQuery q, ComponentMetadata m) {
        if (q.style == null || q.style.styleProfile == null) {
            return 0.5;
        }

        if (m.styleAffinity == null || m.styleAffinity.isEmpty()) {
            return 0.25;
        }

        double direct = m.styleAffinity.getOrDefault(q.style.styleProfile, 0.0);
        if (direct > 0) {
            return direct;
        }
        for (var entry : m.styleAffinity.entrySet()) {
            if (entry.getKey() != null
                    && ComponentQueryMatchUtil.equalsIgnoreCase(entry.getKey(), q.style.styleProfile)) {
                return entry.getValue();
            }
        }
        return 0.0;
    }

    private static double usageScore(ComponentQuery q, ComponentMetadata m) {
        if (q.usageHint == null) {
            return 0.5;
        }

        double score = 1.0;

        if ("primary".equals(q.usageHint.frequency)) {
            score += m.isPrimary ? 0.2 : -0.1;
        }

        if ("high".equals(q.usageHint.visibility)) {
            score += m.isVisuallyStrong ? 0.2 : 0.0;
        }

        return Math.max(0.0, Math.min(score, 1.2));
    }

    /**
     * 评分结果
     */
    public record ScoredComponent(ComponentMetadata component, double score) {}
}
