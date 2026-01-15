package com.formacraft.common.component.rank;

import com.formacraft.common.component.query.ComponentQuery;
import com.formacraft.common.component.query.ComponentMetadata;

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
        if (q.semantic == null || q.semantic.role == null) {
            return 0.5; // 如果没有语义要求，给中等分数
        }

        if (m.semantic == null || m.semantic.role == null) {
            return 0.0;
        }

        if (!safeEquals(q.semantic.role, m.semantic.role)) {
            return 0.0;
        }

        if (q.semantic.tags == null || q.semantic.tags.isEmpty()) {
            return 1.0;
        }

        if (m.semantic.tags == null || m.semantic.tags.isEmpty()) {
            return 0.5; // 角色匹配但无标签，给部分分
        }

        int hit = 0;
        for (String tag : q.semantic.tags) {
            if (m.semantic.tags.contains(tag)) {
                hit++;
            }
        }

        return (double) hit / q.semantic.tags.size();
    }

    private static double placementScore(ComponentQuery q, ComponentMetadata m) {
        if (q.context == null || q.context.placement == null) {
            return 0.5; // 如果没有上下文要求，给中等分数
        }

        if (m.placementSpec == null || m.placementSpec.allowedPlacements == null) {
            return 0.0;
        }

        if (!m.placementSpec.allowedPlacements.contains(q.context.placement)) {
            return 0.0;
        }

        if (q.context.side != null && !m.placementSpec.isSideAllowed(q.context.side)) {
            return 0.0;
        }

        double score = 1.0;

        if (q.context.edgeCondition != null && m.placementSpec.edgePreference != null) {
            if (safeEquals(q.context.edgeCondition, m.placementSpec.edgePreference)) {
                score += 0.2;
            }
        }

        return Math.min(score, 1.2);
    }

    private static double geometryScore(ComponentQuery q, ComponentMetadata m) {
        if (q.geometry == null) {
            return 0.5; // 如果没有几何要求，给中等分数
        }

        if (m.geometrySpec == null) {
            return 0.0;
        }

        if (q.geometry.requiresOpening && !m.geometrySpec.requiresOpening) {
            return 0.0;
        }

        if (!q.geometry.scalable && (m.geometrySpec.scalableAxes == null || m.geometrySpec.scalableAxes.isEmpty())) {
            return 0.0;
        }

        if (q.geometry.openingWidth != null && q.geometry.openingHeight != null) {
            int dw = Math.abs(q.geometry.openingWidth - m.geometrySpec.getBaseWidth());
            int dh = Math.abs(q.geometry.openingHeight - m.geometrySpec.getBaseHeight());

            int tol = q.geometry.tolerance;
            if (dw > tol || dh > tol) {
                return 0.0;
            }

            return 1.0 - (dw + dh) * 0.1;
        }

        return 0.8;
    }

    private static double styleScore(ComponentQuery q, ComponentMetadata m) {
        if (q.style == null || q.style.styleProfile == null) {
            return 0.5; // 如果没有风格要求，给中等分数
        }

        if (m.styleAffinity == null) {
            return 0.0;
        }

        return m.styleAffinity.getOrDefault(q.style.styleProfile, 0.0);
    }

    private static double usageScore(ComponentQuery q, ComponentMetadata m) {
        if (q.usageHint == null) {
            return 0.5; // 如果没有使用提示，给中等分数
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

    /* ----------------------------
     * 工具
     * ---------------------------- */

    private static boolean safeEquals(String a, String b) {
        return a != null && a.equals(b);
    }

    /* ----------------------------
     * 输出结构
     * ---------------------------- */

    /**
     * 评分结果
     */
    public record ScoredComponent(ComponentMetadata component, double score) {}
}
