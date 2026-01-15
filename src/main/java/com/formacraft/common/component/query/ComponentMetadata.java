package com.formacraft.common.component.query;

import java.util.List;
import java.util.Map;

/**
 * ComponentMetadata（构件元数据）：构件库侧必须具备的信息。
 * <p>
 * 核心思想：
 * - 这不是 AI 写的，而是系统定义的"事实"
 * - 用于 ComponentRetriever 和 ComponentRanker 的筛选和评分
 * <p>
 * 每个构件在库中必须至少有这些信息。
 */
public class ComponentMetadata {
    /**
     * 构件 ID
     */
    public String componentId;

    /**
     * 语义信息
     */
    public Semantic semantic;

    /**
     * 放置规格
     */
    public PlacementSpec placementSpec;

    /**
     * 几何规格
     */
    public GeometrySpec geometrySpec;

    /**
     * 风格亲和度（与不同风格的匹配度，0.0 - 1.0）
     */
    public Map<String, Double> styleAffinity;

    /**
     * 变体能力
     */
    public VariantCapability variantCapability;

    /**
     * 语义信息
     */
    public static class Semantic {
        /**
         * 角色（例如："door", "window", "column"）
         */
        public String role;

        /**
         * 标签（例如：["gothic", "arched", "stone", "heavy"]）
         */
        public List<String> tags;
    }

    /**
     * 放置规格
     */
    public static class PlacementSpec {
        /**
         * 允许的放置上下文（例如：["wall"], ["wall", "roof"]）
         */
        public List<String> allowedPlacements;

        /**
         * 表面侧策略（例如："exterior_only", "interior_only", "both"）
         */
        public String sidePolicy;

        /**
         * 是否需要开口
         */
        public Boolean requiresOpening;
    }

    /**
     * 几何规格
     */
    public static class GeometrySpec {
        /**
         * 基础尺寸 {w, h, d}
         */
        public int[] baseSize;

        /**
         * 可缩放的轴（例如：["height"], ["width", "height"]）
         */
        public List<String> scalableAxes;

        /**
         * 最大缩放比例
         */
        public Double maxScale;

        /**
         * 最小缩放比例
         */
        public Double minScale;
    }

    /**
     * 变体能力
     */
    public static class VariantCapability {
        /**
         * 是否允许材质替换
         */
        public Boolean materialSwap;

        /**
         * 是否允许分段重复
         */
        public Boolean segmentRepeat;
    }

    /**
     * 从 ComponentDefinition 和 ComponentArchetype 创建元数据
     */
    public static ComponentMetadata fromComponent(
            com.formacraft.common.component.ComponentDefinition component,
            com.formacraft.common.component.archetype.ComponentArchetype archetype
    ) {
        ComponentMetadata metadata = new ComponentMetadata();
        metadata.componentId = component.id;

        // 语义信息
        metadata.semantic = new Semantic();
        if (component.category != null) {
            // 从分类推断角色（简化处理）
            String categoryName = component.category.name().toLowerCase();
            if (categoryName.contains("door") || categoryName.contains("window")) {
                metadata.semantic.role = categoryName.contains("door") ? "door" : "window";
            } else if (categoryName.contains("column") || categoryName.contains("support")) {
                metadata.semantic.role = "column";
            } else if (categoryName.contains("railing") || categoryName.contains("guard")) {
                metadata.semantic.role = "railing";
            } else {
                metadata.semantic.role = "decoration";
            }
        } else {
            metadata.semantic.role = "unknown";
        }
        metadata.semantic.tags = component.tags != null ? component.tags : List.of();

        // 放置规格（从 Archetype 获取）
        if (archetype != null && archetype.attachment != null) {
            metadata.placementSpec = new PlacementSpec();
            var attachment = archetype.attachment;
            if (attachment.allowedContexts != null) {
                metadata.placementSpec.allowedPlacements = attachment.allowedContexts.stream()
                        .map(Enum::name)
                        .map(String::toLowerCase)
                        .toList();
            }
            if (attachment.allowedSides != null) {
                if (attachment.allowedSides.size() == 1) {
                    var side = attachment.allowedSides.iterator().next();
                    metadata.placementSpec.sidePolicy = side == com.formacraft.common.component.archetype.SurfaceSide.EXTERIOR
                            ? "exterior_only"
                            : side == com.formacraft.common.component.archetype.SurfaceSide.INTERIOR
                            ? "interior_only"
                            : "both";
                } else {
                    metadata.placementSpec.sidePolicy = "both";
                }
            }
            metadata.placementSpec.requiresOpening = attachment.type == com.formacraft.common.component.archetype.AttachmentType.SURFACE;
        }

        // 几何规格（从 ComponentDefinition 和 Archetype 获取）
        if (component.size != null) {
            metadata.geometrySpec = new GeometrySpec();
            metadata.geometrySpec.baseSize = new int[]{component.size.w, component.size.h, component.size.d};
            
            if (archetype != null && archetype.variation != null) {
                var variation = archetype.variation;
                var scalableAxes = new java.util.ArrayList<String>();
                if (!variation.scaleX.locked) scalableAxes.add("width");
                if (!variation.scaleY.locked) scalableAxes.add("height");
                if (!variation.scaleZ.locked) scalableAxes.add("depth");
                metadata.geometrySpec.scalableAxes = scalableAxes;
                metadata.geometrySpec.maxScale = Math.max(
                        variation.scaleX.max,
                        Math.max(variation.scaleY.max, variation.scaleZ.max)
                );
                metadata.geometrySpec.minScale = Math.min(
                        variation.scaleX.min,
                        Math.min(variation.scaleY.min, variation.scaleZ.min)
                );
            }
        }

        // 风格亲和度（简化处理，从标签推断）
        metadata.styleAffinity = new java.util.HashMap<>();
        if (component.tags != null) {
            for (String tag : component.tags) {
                String lower = tag.toLowerCase();
                if (lower.contains("gothic")) {
                    metadata.styleAffinity.put("Gothic", 1.0);
                    metadata.styleAffinity.put("Medieval_Castle", 0.9);
                } else if (lower.contains("chinese") || lower.contains("chinese")) {
                    metadata.styleAffinity.put("Chinese_Traditional", 1.0);
                    metadata.styleAffinity.put("Medieval_Castle", 0.3);
                } else if (lower.contains("medieval")) {
                    metadata.styleAffinity.put("Medieval_Castle", 0.9);
                }
            }
        }

        // 变体能力（从 Archetype 获取）
        if (archetype != null && archetype.variation != null) {
            metadata.variantCapability = new VariantCapability();
            metadata.variantCapability.materialSwap = archetype.variation.allowMaterialSwap;
            metadata.variantCapability.segmentRepeat = archetype.variation.repeatRule != null && archetype.variation.repeatRule.enabled;
        }

        return metadata;
    }
}
