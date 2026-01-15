package com.formacraft.common.component.query;

import com.formacraft.common.component.ComponentDefinition;

/**
 * ComponentRanker（构件排序器）：多维评分系统。
 * <p>
 * 这是整个系统的"智慧核心"。
 * <p>
 * 评分模型：
 * finalScore =
 *     semanticScore * 0.30 +
 *     placementScore * 0.25 +
 *     geometryScore * 0.20 +
 *     styleScore * 0.15 +
 *     usageScore * 0.10;
 */
public final class ComponentRanker {
    private ComponentRanker() {}

    // 评分权重
    private static final double WEIGHT_SEMANTIC = 0.30;
    private static final double WEIGHT_PLACEMENT = 0.25;
    private static final double WEIGHT_GEOMETRY = 0.20;
    private static final double WEIGHT_STYLE = 0.15;
    private static final double WEIGHT_USAGE = 0.10;

    /**
     * 对构件进行多维评分
     * 
     * @param metadata 构件元数据
     * @param component 构件定义
     * @param query 查询条件
     * @return 评分结果
     */
    public static ComponentScore rank(ComponentMetadata metadata, ComponentDefinition component, ComponentQuery query) {
        ComponentScore score = new ComponentScore(metadata.componentId);

        // 1. 语义评分（我是不是你要的那个）
        score.semanticScore = scoreSemantic(metadata, query);

        // 2. 放置评分（放得合不合理）
        score.contextScore = scorePlacement(metadata, query);

        // 3. 几何评分（能不能塞进去）
        score.flexibilityScore = scoreGeometry(metadata, query);

        // 4. 风格评分（看起来像不像一套）
        score.styleScore = scoreStyle(metadata, query);

        // 5. 使用评分（主角还是配角）
        double usageScore = scoreUsage(metadata, query);

        // 6. 计算总分（使用新的权重模型）
        score.totalScore = score.semanticScore * WEIGHT_SEMANTIC +
                          score.contextScore * WEIGHT_PLACEMENT +
                          score.flexibilityScore * WEIGHT_GEOMETRY +
                          score.styleScore * WEIGHT_STYLE +
                          usageScore * WEIGHT_USAGE;

        return score;
    }

    /**
     * 语义评分（我是不是你要的那个）
     * <p>
     * - role 完全匹配：1.0
     * - tags 命中率：n / total
     * - importance 中的字段加权
     */
    private static double scoreSemantic(ComponentMetadata metadata, ComponentQuery query) {
        if (query.semantic == null) {
            return 0.5; // 如果没有语义要求，给中等分数
        }

        double score = 0.0;

        // 1. role 完全匹配：1.0
        if (query.semantic.role != null && metadata.semantic != null && metadata.semantic.role != null) {
            if (query.semantic.role.equalsIgnoreCase(metadata.semantic.role)) {
                score += 0.6; // role 匹配占 60%
            }
        }

        // 2. tags 命中率
        if (query.semantic.tags != null && !query.semantic.tags.isEmpty()) {
            if (metadata.semantic != null && metadata.semantic.tags != null && !metadata.semantic.tags.isEmpty()) {
                int matches = 0;
                for (String queryTag : query.semantic.tags) {
                    if (queryTag == null || queryTag.isBlank()) continue;
                    String lowerQuery = queryTag.toLowerCase();
                    for (String componentTag : metadata.semantic.tags) {
                        if (componentTag != null && componentTag.toLowerCase().contains(lowerQuery)) {
                            matches++;
                            break;
                        }
                    }
                }
                // tags 匹配占 40%
                score += 0.4 * (double) matches / query.semantic.tags.size();
            }
        }

        return Math.min(1.0, score);
    }

    /**
     * 放置评分（放得合不合理）
     * <p>
     * - placement 完全匹配：1.0
     * - side_policy 完全匹配：1.0
     * - edge_condition 符合：+0.2
     */
    private static double scorePlacement(ComponentMetadata metadata, ComponentQuery query) {
        if (query.context == null) {
            return 0.5; // 如果没有上下文要求，给中等分数
        }

        double score = 0.0;

        // 1. placement 完全匹配
        if (query.context.placement != null) {
            if (metadata.placementSpec != null && metadata.placementSpec.allowedPlacements != null) {
                boolean exactMatch = metadata.placementSpec.allowedPlacements.stream()
                        .anyMatch(p -> p.equalsIgnoreCase(query.context.placement));
                if (exactMatch) {
                    score += 0.5; // placement 匹配占 50%
                }
            }
        }

        // 2. side_policy 完全匹配
        if (query.context.side != null) {
            if (metadata.placementSpec != null && metadata.placementSpec.sidePolicy != null) {
                String sidePolicy = metadata.placementSpec.sidePolicy.toLowerCase();
                String querySide = query.context.side.toLowerCase();
                if (sidePolicy.equals("both")) {
                    score += 0.3; // 支持 both，给部分分
                } else if (sidePolicy.equals(querySide + "_only")) {
                    score += 0.3; // 完全匹配
                }
            }
        }

        // 3. edge_condition 符合（简化处理）
        if (query.context.edgeCondition != null) {
            // 如果构件支持边缘放置，给加分
            if (metadata.placementSpec != null && metadata.placementSpec.allowedPlacements != null) {
                if (metadata.placementSpec.allowedPlacements.contains("edge")) {
                    score += 0.2;
                }
            }
        }

        return Math.min(1.0, score);
    }

    /**
     * 几何评分（能不能塞进去）
     * <p>
     * - opening 尺寸差距（越小越好）
     * - 是否需要极端缩放（惩罚）
     * - scalable_axes 是否支持
     */
    private static double scoreGeometry(ComponentMetadata metadata, ComponentQuery query) {
        if (query.geometry == null) {
            return 0.5; // 如果没有几何要求，给中等分数
        }

        double score = 0.0;

        // 1. opening 尺寸差距
        if (query.geometry.opening != null && metadata.geometrySpec != null) {
            if (query.geometry.opening.width != null && query.geometry.opening.height != null) {
                int[] baseSize = metadata.geometrySpec.baseSize;
                if (baseSize != null && baseSize.length >= 2) {
                    int widthDiff = Math.abs(query.geometry.opening.width - baseSize[0]);
                    int heightDiff = Math.abs(query.geometry.opening.height - baseSize[1]);
                    
                    // 计算差距比例（越小越好）
                    double widthRatio = (double) widthDiff / Math.max(1, query.geometry.opening.width);
                    double heightRatio = (double) heightDiff / Math.max(1, query.geometry.opening.height);
                    double avgRatio = (widthRatio + heightRatio) / 2.0;
                    
                    // 差距越小，分数越高
                    score += 0.6 * Math.max(0.0, 1.0 - avgRatio);
                }
            }
        }

        // 2. 是否需要极端缩放（惩罚）
        if (query.geometry != null && Boolean.TRUE.equals(query.geometry.scalable)) {
            if (metadata.geometrySpec != null) {
                // 如果构件的可缩放轴支持查询的需求，给高分
                if (metadata.geometrySpec.scalableAxes != null && !metadata.geometrySpec.scalableAxes.isEmpty()) {
                    score += 0.3;
                } else {
                    // 如果不可缩放，但查询需要缩放，给低分
                    score += 0.1;
                }
            }
        }

        // 3. scalable_axes 是否支持（简化处理）
        if (metadata.geometrySpec != null && metadata.geometrySpec.scalableAxes != null) {
            // 如果支持多个轴的缩放，给加分
            if (metadata.geometrySpec.scalableAxes.size() >= 2) {
                score += 0.1;
            }
        }

        return Math.min(1.0, score);
    }

    /**
     * 风格评分（看起来像不像一套）
     * <p>
     * - style_affinity[style_profile]
     * - material_tone 相似度
     */
    private static double scoreStyle(ComponentMetadata metadata, ComponentQuery query) {
        if (query.style == null) {
            return 0.5; // 如果没有风格要求，给中等分数
        }

        double score = 0.0;

        // 1. style_affinity[style_profile]
        if (query.style.styleProfile != null && metadata.styleAffinity != null) {
            Double affinity = metadata.styleAffinity.get(query.style.styleProfile);
            if (affinity != null) {
                score += 0.7 * affinity; // 风格亲和度占 70%
            } else {
                // 如果没有直接匹配，尝试模糊匹配
                String queryStyle = query.style.styleProfile.toLowerCase();
                for (var entry : metadata.styleAffinity.entrySet()) {
                    if (entry.getKey().toLowerCase().contains(queryStyle) || 
                        queryStyle.contains(entry.getKey().toLowerCase())) {
                        score += 0.5 * entry.getValue(); // 模糊匹配，给部分分
                        break;
                    }
                }
            }
        }

        // 2. material_tone 相似度（简化处理）
        if (query.style.materialTone != null) {
            if (metadata.semantic != null && metadata.semantic.tags != null) {
                String queryTone = query.style.materialTone.toLowerCase();
                for (String tag : metadata.semantic.tags) {
                    if (tag != null && tag.toLowerCase().contains(queryTone)) {
                        score += 0.3; // 材质色调匹配
                        break;
                    }
                }
            }
        }

        return Math.min(1.0, score);
    }

    /**
     * 使用评分（主角还是配角）
     * <p>
     * - primary + visibility=high → 加权
     * - decorative → 降权
     */
    private static double scoreUsage(ComponentMetadata metadata, ComponentQuery query) {
        if (query.usageHint == null) {
            return 0.5; // 如果没有使用提示，给中等分数
        }

        double score = 0.5; // 基础分

        // 1. frequency
        if (query.usageHint.frequency != null) {
            String frequency = query.usageHint.frequency.toLowerCase();
            if (frequency.equals("primary")) {
                score += 0.3; // 主要构件，加分
            } else if (frequency.equals("decorative")) {
                score -= 0.2; // 装饰性构件，降分
            }
        }

        // 2. visibility
        if (query.usageHint.visibility != null) {
            String visibility = query.usageHint.visibility.toLowerCase();
            if (visibility.equals("high")) {
                score += 0.2; // 高可见性，加分
            } else if (visibility.equals("low")) {
                score -= 0.1; // 低可见性，降分
            }
        }

        return Math.max(0.0, Math.min(1.0, score));
    }
}
