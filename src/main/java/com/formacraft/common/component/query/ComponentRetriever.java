package com.formacraft.common.component.query;

import com.formacraft.common.component.ComponentCatalog;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.ComponentStorage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ComponentRetriever（构件检索器）：从构件库中检索和排序构件。
 * <p>
 * 核心功能：
 * - 根据查询条件从 ComponentLibrary 检索构件
 * - 使用 ComponentScorer 对构件评分
 * - 返回排序后的候选构件列表
 * <p>
 * 这是 AI 选择构件的核心逻辑。
 */
public final class ComponentRetriever {
    private ComponentRetriever() {}

    /**
     * 检索构件（返回排序后的候选列表）
     * 
     * @param query 查询条件
     * @param maxResults 最大结果数量（默认 10）
     * @return 排序后的构件评分列表（按总分降序）
     */
    public static List<ComponentScore> retrieve(ComponentQuery query, int maxResults) {
        if (query == null) {
            return List.of();
        }

        // 1. 从 ComponentCatalog 获取所有构件
        ComponentCatalog catalog = ComponentCatalog.getGlobal();
        if (catalog == null || catalog.components == null || catalog.components.isEmpty()) {
            return List.of();
        }

        // 2. 对每个构件评分
        List<ComponentScore> scores = new ArrayList<>();
        for (ComponentCatalog.Entry entry : catalog.components) {
            if (entry == null || entry.id == null || entry.id.isBlank()) {
                continue;
            }

            // 加载构件定义
            ComponentDefinition component = ComponentStorage.loadComponent(entry.id);
            if (component == null) {
                continue;
            }

            // 评分
            ComponentScore score = ComponentScorer.score(component, query);
            scores.add(score);
        }

        // 3. 排序（按总分降序）
        scores.sort(Comparator.comparingDouble((ComponentScore s) -> s.totalScore).reversed());

        // 4. 过滤低分结果（总分 < 0.3）
        scores = scores.stream()
                .filter(s -> s.totalScore >= 0.3)
                .collect(Collectors.toList());

        // 5. 限制结果数量
        if (maxResults > 0 && scores.size() > maxResults) {
            scores = scores.subList(0, maxResults);
        }

        return scores;
    }

    /**
     * 检索构件（使用默认最大结果数量 10）
     */
    public static List<ComponentScore> retrieve(ComponentQuery query) {
        return retrieve(query, 10);
    }

    /**
     * 检索最佳匹配的构件
     * 
     * @param query 查询条件
     * @return 最佳匹配的构件定义（如果存在），否则返回 null
     */
    public static ComponentDefinition retrieveBest(ComponentQuery query) {
        List<ComponentScore> results = retrieve(query, 1);
        if (results.isEmpty()) {
            return null;
        }

        ComponentScore best = results.get(0);
        if (best.totalScore < 0.5) {
            // 如果最佳匹配的分数太低，返回 null
            return null;
        }

        return ComponentStorage.loadComponent(best.componentId);
    }

    /**
     * 根据语义标签快速检索
     */
    public static List<ComponentScore> retrieveBySemantic(String... tags) {
        ComponentQuery query = ComponentQuery.semantic(tags);
        return retrieve(query);
    }

    /**
     * 获取检索结果的统计信息
     */
    public static RetrievalStats getStats(List<ComponentScore> results) {
        if (results == null || results.isEmpty()) {
            return new RetrievalStats(0, 0.0, 0.0, 0.0);
        }

        double avgScore = results.stream()
                .mapToDouble(s -> s.totalScore)
                .average()
                .orElse(0.0);

        double maxScore = results.stream()
                .mapToDouble(s -> s.totalScore)
                .max()
                .orElse(0.0);

        double minScore = results.stream()
                .mapToDouble(s -> s.totalScore)
                .min()
                .orElse(0.0);

        return new RetrievalStats(results.size(), avgScore, maxScore, minScore);
    }

    /**
     * 检索统计信息
     */
    public record RetrievalStats(
            int count,
            double avgScore,
            double maxScore,
            double minScore
    ) {}
}
