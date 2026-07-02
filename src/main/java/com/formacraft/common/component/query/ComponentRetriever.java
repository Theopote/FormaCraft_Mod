package com.formacraft.common.component.query;

import com.formacraft.common.component.ComponentCatalog;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.ComponentStorage;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ComponentRetriever（构件检索器）：从构件库中检索和排序构件。
 * <p>
 * 核心功能：
 * - 根据查询条件从 ComponentCatalog 检索构件
 * - 使用 ComponentRanker 对构件进行多维评分
 * - 返回排序后的候选构件列表
 * <p>
 * 这是 AI 选择构件的核心逻辑。
 * <p>
 * 流程：
 * 1. 硬过滤阶段（ComponentRetriever）- 从 200 个 → 20 个候选
 * 2. 多维评分阶段（ComponentRanker）- 对候选进行详细评分
 * 3. 排序和过滤 - 返回 Top-N
 */
public final class ComponentRetriever {
    private ComponentRetriever() {}

    /**
     * 检索构件（返回排序后的候选列表）
     * <p>
     * 流程：
     * 1. 硬过滤（ComponentRetriever）- 从 200 个 → 20 个候选
     * 2. 多维评分（ComponentRanker）- 对候选进行详细评分
     * 3. 排序和过滤 - 返回 Top-N
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
        ComponentCatalog catalog = ComponentStorage.loadCatalog(null);
        if (catalog.components == null || catalog.components.isEmpty()) {
            return List.of();
        }

        // 2. 硬过滤阶段（必须通过的条件）
        List<ComponentMetadata> candidates = new ArrayList<>();
        for (ComponentCatalog.Entry entry : catalog.components) {
            if (entry == null || entry.id == null || entry.id.isBlank()) {
                continue;
            }

            // 加载构件定义和 Archetype
            ComponentDefinition component = ComponentStorage.loadComponent(null, entry.id);
            if (component == null) {
                continue;
            }

            // 获取 Archetype（内存 / 磁盘 / 自动生成）
            com.formacraft.common.component.archetype.ComponentArchetype archetype =
                    com.formacraft.common.component.archetype.ComponentArchetypeStorage.resolve(component);

            // 创建元数据
            ComponentMetadata metadata = ComponentMetadata.fromComponent(component, archetype);

            // 硬过滤（必须通过）
            if (hardFilter(metadata, query)) {
                candidates.add(metadata);
            }
        }

        // 3. 多维评分阶段（使用 ComponentRanker）
        List<com.formacraft.common.component.rank.ComponentRanker.ScoredComponent> scored = 
            com.formacraft.common.component.rank.ComponentRanker.rank(query, candidates);

        // 转换为 ComponentScore（向后兼容）
        List<ComponentScore> scores = new ArrayList<>();
        for (var sc : scored) {
            ComponentScore score = new ComponentScore(sc.component().componentId);
            score.totalScore = sc.score();
            // 其他维度分数可以从 ComponentRanker 扩展获取（如果需要）
            scores.add(score);
        }

        // 4. 排序（按总分降序）
        scores.sort(Comparator.comparingDouble((ComponentScore s) -> s.totalScore).reversed());

        // 5. 过滤低分结果（总分 < 0.3）
        scores = scores.stream()
                .filter(s -> s.totalScore >= 0.3)
                .collect(Collectors.toList());

        // 6. 限制结果数量
        if (maxResults > 0 && scores.size() > maxResults) {
            scores = scores.subList(0, maxResults);
        }

        return scores;
    }

    /**
     * 硬过滤（必须通过的条件，没有"评分"，只有"能不能用"）
     * <p>
     * 硬过滤条件：
     * - semantic.role 匹配
     * - placement_spec.allowed_placements 包含
     * - side_policy 不冲突
     * - requires_opening 与 geometry.opening 是否满足
     * - forbidden_tags 不命中
     */
    private static boolean hardFilter(ComponentMetadata metadata, ComponentQuery query) {
        // 1. semantic.role 匹配
        if (query.semantic != null && query.semantic.role != null) {
            if (metadata.semantic == null || metadata.semantic.role == null) {
                return false;
            }
            if (!query.semantic.role.equalsIgnoreCase(metadata.semantic.role)) {
                return false;
            }
        }

        // 2. placement_spec.allowed_placements 包含
        if (query.context != null && query.context.placement != null) {
            if (metadata.placementSpec == null || metadata.placementSpec.allowedPlacements == null) {
                return false;
            }
            boolean placementMatch = metadata.placementSpec.allowedPlacements.stream()
                    .anyMatch(p -> p.equalsIgnoreCase(query.context.placement));
            if (!placementMatch) {
                return false;
            }
        }

        // 3. side_policy 不冲突
        if (query.context != null && query.context.side != null) {
            if (metadata.placementSpec != null && metadata.placementSpec.sidePolicy != null) {
                String sidePolicy = metadata.placementSpec.sidePolicy.toLowerCase();
                String querySide = query.context.side.toLowerCase();
                if (sidePolicy.equals("exterior_only") && !querySide.equals("exterior")) {
                    return false;
                }
                if (sidePolicy.equals("interior_only") && !querySide.equals("interior")) {
                    return false;
                }
            }
        }

        // 4. requires_opening 与 geometry 是否满足
        if (metadata.placementSpec != null && Boolean.TRUE.equals(metadata.placementSpec.requiresOpening)) {
            // 需要开口，且查询提供了开口信息 → 通过
            // 如果查询没有提供开口信息，但构件需要开口 → 可能不匹配，但暂时通过（由评分阶段处理）
        }// 构件需要开口，但查询没有提供 → 可能不匹配，但暂时通过（由评分阶段处理）

        // 5. forbidden_tags 不命中
        if (query.constraints != null && query.constraints.forbiddenTags != null) {
            if (metadata.semantic != null && metadata.semantic.tags != null) {
                for (String forbiddenTag : query.constraints.forbiddenTags) {
                    if (forbiddenTag == null || forbiddenTag.isBlank()) continue;
                    String lowerForbidden = forbiddenTag.toLowerCase();
                    for (String componentTag : metadata.semantic.tags) {
                        if (componentTag != null && componentTag.toLowerCase().contains(lowerForbidden)) {
                            return false; // 命中禁止标签
                        }
                    }
                }
            }
        }

        // 6. must_have 必须命中
        if (query.constraints != null && query.constraints.mustHave != null && !query.constraints.mustHave.isEmpty()) {
            if (metadata.semantic == null || metadata.semantic.tags == null || metadata.semantic.tags.isEmpty()) {
                return false;
            }
            for (String mustHaveTag : query.constraints.mustHave) {
                if (mustHaveTag == null || mustHaveTag.isBlank()) continue;
                String lowerMustHave = mustHaveTag.toLowerCase();
                boolean found = false;
                for (String componentTag : metadata.semantic.tags) {
                    if (componentTag != null && componentTag.toLowerCase().contains(lowerMustHave)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return false; // 缺少必须标签
                }
            }
        }

        return true; // 通过所有硬过滤条件
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

        ComponentScore best = results.getFirst();
        if (best.totalScore < 0.5) {
            // 如果最佳匹配的分数太低，返回 null
            return null;
        }

        return ComponentStorage.loadComponent(null, best.componentId);
    }

    /**
     * 检索最佳匹配的构件并生成变体
     * <p>
     * 这是 AI 使用构件的标准流程：
     * 1. 检索最佳匹配
     * 2. 自动生成变体（用户完全不需要参与）
     * 
     * @param query 查询条件
     * @param random 随机数生成器（用于变体生成）
     * @return 变体结果（包含基础构件和生成的变体）
     */
    public static VariantResult retrieveBestWithVariant(ComponentQuery query, java.util.Random random) {
        ComponentDefinition base = retrieveBest(query);
        if (base == null) {
            return null;
        }

        // 自动生成变体
        com.formacraft.common.component.variant.ComponentVariant variant = 
            com.formacraft.common.component.variant.VariantGenerator.generate(base, query, random);

        return new VariantResult(base, variant);
    }

    /**
     * 变体结果
     */
    public record VariantResult(
            ComponentDefinition base,
            com.formacraft.common.component.variant.ComponentVariant variant
    ) {}

    /**
     * 根据语义标签快速检索
     */
    public static List<ComponentScore> retrieveBySemantic(String... tags) {
        ComponentQuery query = new ComponentQuery();
        query.semantic = new ComponentQuery.Semantic();
        query.semantic.tags = Set.of(tags);
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
