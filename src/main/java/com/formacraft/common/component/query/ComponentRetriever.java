package com.formacraft.common.component.query;

import com.formacraft.common.component.ComponentCatalog;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.ComponentStorage;

import java.util.*;
import java.util.UUID;
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

    /** 当前检索可见的玩家（含私人命名空间）；服务端请求线程设置。 */
    private static final ThreadLocal<UUID> VIEWER_PLAYER = new ThreadLocal<>();

    public static void setViewerPlayer(UUID playerId) {
        if (playerId == null) {
            VIEWER_PLAYER.remove();
        } else {
            VIEWER_PLAYER.set(playerId);
        }
    }

    public static void clearViewerPlayer() {
        VIEWER_PLAYER.remove();
    }

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

        UUID viewer = VIEWER_PLAYER.get();

        // 1. 从 ComponentCatalog 获取所有构件（含当前玩家私人库）
        ComponentCatalog catalog = ComponentStorage.loadCatalog(null, viewer);
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
            ComponentDefinition component = ComponentStorage.loadComponent(null, entry.id, viewer);
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
            score.breakdown = com.formacraft.common.component.rank.ComponentRanker.explain(query, sc.component());
            score.semanticScore = score.breakdown.semantic();
            score.contextScore = score.breakdown.placement();
            score.styleScore = score.breakdown.style();
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
     * 硬过滤（仅保留真正“不可用”的条件）。
     * <p>
     * placement / side / role 等语义约束改由 {@link com.formacraft.common.component.rank.ComponentRanker} 软评分，
     * 避免用户构件因 metadata 不完整被整批剔除。
     */
    static boolean hardFilter(ComponentMetadata metadata, ComponentQuery query) {
        // 1. forbidden_tags 不命中
        if (query.constraints != null && query.constraints.forbiddenTags != null) {
            if (metadata.semantic != null && metadata.semantic.tags != null) {
                for (String forbiddenTag : query.constraints.forbiddenTags) {
                    if (forbiddenTag == null || forbiddenTag.isBlank()) continue;
                    if (ComponentQueryMatchUtil.tagIdentityMatches(forbiddenTag, metadata.semantic.tags)) {
                        return false;
                    }
                }
            }
        }

        // 2. must_have 必须命中
        if (query.constraints != null && query.constraints.mustHave != null && !query.constraints.mustHave.isEmpty()) {
            if (metadata.semantic == null || metadata.semantic.tags == null || metadata.semantic.tags.isEmpty()) {
                return false;
            }
            for (String mustHaveTag : query.constraints.mustHave) {
                if (mustHaveTag == null || mustHaveTag.isBlank()) continue;
                if (!ComponentQueryMatchUtil.tagIdentityMatches(mustHaveTag, metadata.semantic.tags)) {
                    return false;
                }
            }
        }

        return true;
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
        if (best.totalScore < 0.35) {
            // 软评分体系下阈值略降，避免唯一候选被误拒
            return null;
        }

        return ComponentStorage.loadComponent(null, best.componentId, VIEWER_PLAYER.get());
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
        for (ComponentScore score : retrieve(query, 20)) {
            if (score.totalScore < 0.35) continue;
            ComponentDefinition base = ComponentStorage.loadComponent(null, score.componentId, VIEWER_PLAYER.get());
            if (base == null) continue;
            var variant = com.formacraft.common.component.variant.VariantGenerator.generate(base, query, random);
            if (com.formacraft.common.component.model.ComponentModelApi.fitToQuery(base, query, variant) != null) {
                return new VariantResult(base, variant);
            }
        }
        return null;
    }

    /**
     * Distinguishes NO_CANDIDATE (nothing loadable/scored) from ADAPT_FAILED (candidates existed but none fit).
     * On SUCCESS, {@link ComponentResolveResult#definition()} is the fitted definition.
     */
    public static ComponentResolveResult retrieveBestResolved(ComponentQuery query, java.util.Random random) {
        return retrieveResolvedReport(query, random).result();
    }

    /** Ordered lookup evidence. SUCCESS means a permitted fit, not necessarily the selected fit. */
    public record CandidateAttempt(String sourceId, double score,
                                   ComponentResolveResult.Status status, ComponentFitScore fitScore) {
        public CandidateAttempt(String sourceId, double score, ComponentResolveResult.Status status) {
            this(sourceId, score, status, null);
        }
    }
    public record ResolutionReport(ComponentResolveResult result, List<CandidateAttempt> attempts) {
        public ResolutionReport { attempts = List.copyOf(attempts); }
    }

    public record FittedCandidate(String sourceId, ComponentDefinition definition, ComponentFitScore score,
                                  Set<String> suites) {
        public FittedCandidate { suites = Set.copyOf(suites); }
    }
    public record CandidatePool(ResolutionReport report, List<FittedCandidate> candidates) {
        public CandidatePool { candidates = List.copyOf(candidates); }
    }

    public static CandidatePool retrieveCandidatePool(ComponentQuery query, java.util.Random random) {
        return fitCandidates(query, random, query == null ? List.of() : retrieve(query, 20),
                id -> ComponentStorage.loadComponent(null, id, VIEWER_PLAYER.get()));
    }

    public static ResolutionReport retrieveResolvedReport(ComponentQuery query, java.util.Random random) {
        return resolveCandidates(query, random, query == null ? List.of() : retrieve(query, 20),
                id -> ComponentStorage.loadComponent(null, id, VIEWER_PLAYER.get()));
    }

    static ResolutionReport resolveCandidates(ComponentQuery query, java.util.Random random,
            List<ComponentScore> candidates, java.util.function.Function<String, ComponentDefinition> loader) {
        return fitCandidates(query, random, candidates, loader, false).report();
    }

    static CandidatePool fitCandidates(ComponentQuery query, java.util.Random random,
            List<ComponentScore> candidates, java.util.function.Function<String, ComponentDefinition> loader) {
        return fitCandidates(query, random, candidates, loader, true);
    }

    private static CandidatePool fitCandidates(ComponentQuery query, java.util.Random random,
            List<ComponentScore> candidates, java.util.function.Function<String, ComponentDefinition> loader,
            boolean retainFits) {
        List<CandidateAttempt> attempts = new ArrayList<>();
        List<FittedCandidate> fits = new ArrayList<>();
        if (query == null) {
            return new CandidatePool(new ResolutionReport(ComponentResolveResult.noCandidate("query_null"), attempts), fits);
        }
        boolean sawLoaded = false;
        ComponentDefinition best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        String bestId = null;
        long seed = random == null ? 0 : random.nextLong();
        String loadFailedId = null;
        boolean sawUnfittable = false;
        String lastUnfittableId = "";
        for (ComponentScore score : candidates) {
            if (score.totalScore < 0.35) continue;
            ComponentDefinition base = loader.apply(score.componentId);
            if (base == null) {
                loadFailedId = score.componentId;
                attempts.add(new CandidateAttempt(score.componentId, score.totalScore, ComponentResolveResult.Status.LOAD_FAILED));
                continue;
            }
            sawLoaded = true;
            var variant = com.formacraft.common.component.variant.VariantGenerator.generate(base, query,
                    new Random(seed ^ Objects.hashCode(score.componentId)));
            ComponentDefinition fitted =
                    com.formacraft.common.component.model.ComponentModelApi.fitToQuery(base, query, variant);
            if (fitted != null) {
                var fitScore = ComponentFitScore.evaluate(score, base, fitted, query);
                if (retainFits) fits.add(new FittedCandidate(score.componentId, fitted, fitScore, suiteIds(base.tags)));
                attempts.add(new CandidateAttempt(score.componentId, score.totalScore,
                        ComponentResolveResult.Status.SUCCESS, fitScore));
                String id = Objects.toString(score.componentId, "");
                if (fitScore.total() > bestScore || (Double.compare(fitScore.total(), bestScore) == 0
                        && (bestId == null || id.compareTo(bestId) < 0))) {
                    best = fitted;
                    bestScore = fitScore.total();
                    bestId = id;
                }
                continue;
            }
            attempts.add(new CandidateAttempt(score.componentId, score.totalScore, ComponentResolveResult.Status.ADAPT_FAILED));
            sawUnfittable = true;
            lastUnfittableId = score.componentId != null ? score.componentId : (base.id != null ? base.id : "");
        }
        if (best != null) return new CandidatePool(new ResolutionReport(ComponentResolveResult.success(best), attempts), fits);
        if (sawUnfittable) {
            return new CandidatePool(new ResolutionReport(ComponentResolveResult.adaptFailed(lastUnfittableId,
                    "candidates_exist_but_none_fit_query"), attempts), fits);
        }
        if (loadFailedId != null) return new CandidatePool(new ResolutionReport(
                ComponentResolveResult.loadFailed(loadFailedId, "ranked_candidates_could_not_load"), attempts), fits);
        if (!sawLoaded) {
            return new CandidatePool(new ResolutionReport(ComponentResolveResult.noCandidate("no_scored_loadable_candidate"), attempts), fits);
        }
        return new CandidatePool(new ResolutionReport(ComponentResolveResult.noCandidate("no_candidate_above_score_threshold"), attempts), fits);
    }

    static Set<String> suiteIds(List<String> tags) {
        Set<String> suites = new TreeSet<>();
        if (tags != null) for (String tag : tags) {
            String normalized = ComponentQueryMatchUtil.normalizeTag(tag);
            if (normalized.startsWith("suite:") && normalized.length() > 6) suites.add(normalized.substring(6));
        }
        return suites;
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
