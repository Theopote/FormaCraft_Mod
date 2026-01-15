package com.formacraft.common.component.query;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.archetype.ComponentArchetype;
import com.formacraft.common.component.archetype.AttachmentSpec;
import com.formacraft.common.component.archetype.VariationSpec;
import com.formacraft.common.component.archetype.ComponentArchetypeStorage;

import java.util.List;

/**
 * ComponentScorer（构件评分器）：计算构件与查询的匹配评分。
 * <p>
 * 注意：这个类保留用于向后兼容，新的代码应该使用 ComponentRanker。
 * <p>
 * ComponentRanker 提供了更详细的多维评分系统。
 */
public final class ComponentScorer {
    private ComponentScorer() {}

    /**
     * 对构件进行评分（向后兼容方法）
     * 
     * @param component 构件定义
     * @param query 查询条件
     * @return 评分结果
     * @deprecated 使用 ComponentRanker.rank() 代替
     */
    @Deprecated
    public static ComponentScore score(ComponentDefinition component, ComponentQuery query) {
        if (component == null || query == null) {
            return new ComponentScore(component != null ? component.id : "unknown");
        }

        // 获取 Archetype（如果有）
        ComponentArchetype archetype = null;
        if (component.id != null) {
            archetype = ComponentArchetypeStorage.get(component.id);
        }

        // 创建元数据
        ComponentMetadata metadata = ComponentMetadata.fromComponent(component, archetype);

        // 使用 ComponentRanker 进行评分
        return ComponentRanker.rank(metadata, component, query);
    }

    /**
     * 语义匹配评分（标签匹配）
     */
    private static double scoreSemantic(ComponentDefinition component, ComponentQuery query) {
        if (query.semantic == null || query.semantic.isEmpty()) {
            return 0.5; // 如果没有语义要求，给中等分数
        }

        if (component.tags == null || component.tags.isEmpty()) {
            return 0.0; // 如果构件没有标签，给 0 分
        }

        // 计算匹配的标签数量
        int matches = 0;
        for (String queryTag : query.semantic) {
            if (queryTag == null || queryTag.isBlank()) continue;
            String lowerQuery = queryTag.toLowerCase();
            for (String componentTag : component.tags) {
                if (componentTag != null && componentTag.toLowerCase().contains(lowerQuery)) {
                    matches++;
                    break;
                }
            }
        }

        // 归一化到 0.0 - 1.0
        return Math.min(1.0, (double) matches / query.semantic.size());
    }

    /**
     * 上下文匹配评分（放置上下文、表面侧）
     */
    private static double scoreContext(ComponentDefinition component, ComponentQuery query) {
        if (query.context == null) {
            return 0.5; // 如果没有上下文要求，给中等分数
        }

        // 尝试获取 Archetype（如果有）
        ComponentArchetype archetype = null;
        if (component.id != null) {
            // 尝试从 Archetype ID 推断（例如：component.id = "door.basic" -> archetype.id = "door.basic"）
            archetype = ComponentArchetypeStorage.get(component.id);
        }

        double score = 0.0;

        // 检查放置上下文
        if (query.context.placement != null) {
            if (archetype != null && archetype.attachment != null) {
                AttachmentSpec attachment = archetype.attachment;
                if (attachment.allowedContexts != null && attachment.allowedContexts.contains(query.context.placement)) {
                    score += 0.5; // 上下文匹配
                }
            } else if (component.placementSpec != null) {
                // 回退到 ComponentDefinition.placementSpec
                // 这里可以添加更复杂的匹配逻辑
                score += 0.3; // 部分匹配
            }
        }

        // 检查表面侧
        if (query.context.side != null) {
            if (archetype != null && archetype.attachment != null) {
                AttachmentSpec attachment = archetype.attachment;
                if (attachment.allowedSides != null) {
                    if (attachment.allowedSides.contains(query.context.side) ||
                        attachment.allowedSides.contains(com.formacraft.common.component.archetype.SurfaceSide.BOTH)) {
                        score += 0.3; // 表面侧匹配
                    }
                }
            }
        }

        // 检查支撑要求
        if (query.context.requireSupport != null) {
            if (archetype != null && archetype.attachment != null) {
                if (archetype.attachment.requireSupport == query.context.requireSupport) {
                    score += 0.2; // 支撑要求匹配
                }
            }
        }

        return Math.min(1.0, score);
    }

    /**
     * 风格匹配评分（风格兼容性）
     */
    private static double scoreStyle(ComponentDefinition component, ComponentQuery query) {
        if (query.style == null || query.style.isBlank()) {
            return 0.5; // 如果没有风格要求，给中等分数
        }

        // 检查构件的标签和分类是否与风格匹配
        String styleLower = query.style.toLowerCase();
        double score = 0.0;

        // 检查标签
        if (component.tags != null) {
            for (String tag : component.tags) {
                if (tag != null && tag.toLowerCase().contains(styleLower)) {
                    score += 0.5;
                    break;
                }
            }
        }

        // 检查分类（某些分类暗示风格）
        if (component.category != null) {
            String categoryLower = component.category.name().toLowerCase();
            if (styleLower.contains("gothic") && categoryLower.contains("opening")) {
                score += 0.3;
            } else if (styleLower.contains("chinese") && categoryLower.contains("structural")) {
                score += 0.3;
            }
        }

        return Math.min(1.0, score);
    }

    /**
     * 可变形程度评分（是否满足约束条件）
     */
    private static double scoreFlexibility(ComponentDefinition component, ComponentQuery query) {
        if (query.constraints == null) {
            return 0.5; // 如果没有约束，给中等分数
        }

        // 尝试获取 Archetype（如果有）
        ComponentArchetype archetype = null;
        if (component.id != null) {
            archetype = ComponentArchetypeStorage.get(component.id);
        }

        double score = 0.0;

        if (archetype != null && archetype.variation != null) {
            VariationSpec variation = archetype.variation;

            // 检查镜像约束
            if (query.constraints.allowMirror != null) {
                if (variation.allowMirror == query.constraints.allowMirror) {
                    score += 0.3;
                }
            }

            // 检查旋转约束
            if (query.constraints.allowRotation != null) {
                if (variation.allowRotation == query.constraints.allowRotation) {
                    score += 0.3;
                }
            }

            // 检查材质替换约束
            if (query.constraints.allowMaterialSwap != null) {
                if (variation.allowMaterialSwap == query.constraints.allowMaterialSwap) {
                    score += 0.2;
                }
            }

            // 检查尺寸约束（简化处理）
            if (query.constraints.minSize != null || query.constraints.maxSize != null) {
                // 如果构件的 VariationSpec 允许缩放，给高分
                if (!variation.scaleX.locked || !variation.scaleY.locked || !variation.scaleZ.locked) {
                    score += 0.2;
                }
            }
        }

        return Math.min(1.0, score);
    }
}
