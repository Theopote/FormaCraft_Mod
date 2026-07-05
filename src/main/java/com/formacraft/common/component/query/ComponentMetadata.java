package com.formacraft.common.component.query;

import com.formacraft.common.component.semantic.ComponentSemanticInference;

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
     * 是否为主要构件（用于排序）
     */
    public boolean isPrimary = false;

    /**
     * 是否视觉突出（用于排序）
     */
    public boolean isVisuallyStrong = false;

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

        /**
         * 几何原型（例如：ORNAMENT / FRAME / LINEAR）
         */
        public String geometryArchetype;
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

        /**
         * 边缘偏好（例如："flat", "corner", "convex"）
         */
        public String edgePreference;

        /**
         * 检查是否允许指定的表面侧
         */
        public boolean isSideAllowed(String side) {
            if (sidePolicy == null || side == null) {
                return true; // 默认允许
            }
            String policy = sidePolicy.toLowerCase();
            String sideLower = side.toLowerCase();
            return switch (policy) {
                case "exterior_only" -> sideLower.equals("exterior");
                case "interior_only" -> sideLower.equals("interior");
                default -> true;
            };
        }
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

        /**
         * 是否需要开口
         */
        public boolean requiresOpening = false;

        /**
         * 基础宽度（从 baseSize[0] 获取）
         */
        public int getBaseWidth() {
            return baseSize != null && baseSize.length > 0 ? baseSize[0] : 1;
        }

        /**
         * 基础高度（从 baseSize[1] 获取）
         */
        public int getBaseHeight() {
            return baseSize != null && baseSize.length > 1 ? baseSize[1] : 1;
        }
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
        metadata.semantic.role = inferRole(component);
        metadata.semantic.tags = component.tags != null ? component.tags : List.of();
        metadata.semantic.geometryArchetype = component.geometryArchetype;
        if (isBlank(metadata.semantic.geometryArchetype) && archetype != null && archetype.geometryHint != null
                && archetype.geometryHint.archetype != null) {
            metadata.semantic.geometryArchetype = archetype.geometryHint.archetype.name();
        }

        // 放置规格：优先 ComponentDefinition.placementSpec，Archetype 作补充
        metadata.placementSpec = mapPlacementSpec(component, archetype);

        // 几何规格（从 ComponentDefinition 和 Archetype 获取）
        if (component.size != null) {
            metadata.geometrySpec = new GeometrySpec();
            metadata.geometrySpec.baseSize = new int[]{component.size.w, component.size.h, component.size.d};
            if (component.placementSpec != null) {
                metadata.geometrySpec.requiresOpening = component.placementSpec.requiresOpening;
            }
            if (archetype != null && archetype.variation != null) {
                var variation = archetype.variation;
                var scalableAxes = new java.util.ArrayList<String>();
                if (!variation.scaleX.locked) scalableAxes.add("width");
                if (!variation.scaleY.locked) scalableAxes.add("height");
                if (!variation.scaleZ.locked) scalableAxes.add("depth");
                metadata.geometrySpec.scalableAxes = scalableAxes;
                metadata.geometrySpec.maxScale = (double) Math.max(
                        variation.scaleX.max,
                        Math.max(variation.scaleY.max, variation.scaleZ.max)
                );
                metadata.geometrySpec.minScale = (double) Math.min(
                        variation.scaleX.min,
                        Math.min(variation.scaleY.min, variation.scaleZ.min)
                );
            }
        }

        // 风格亲和度（优先 culturalStyle，其次从标签推断）
        metadata.styleAffinity = new java.util.HashMap<>();
        if (!isBlank(component.culturalStyle)) {
            ComponentSemanticInference.applyCulturalStyleAffinity(metadata.styleAffinity, component.culturalStyle);
        }
        if (component.tags != null) {
            for (String tag : component.tags) {
                String lower = tag.toLowerCase();
                if (lower.contains("gothic")) {
                    metadata.styleAffinity.put("Gothic", 1.0);
                    metadata.styleAffinity.put("Medieval_Castle", 0.9);
                } else if (lower.contains("chinese")) {
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

    private static String inferRole(com.formacraft.common.component.ComponentDefinition component) {
        if (component == null) {
            return "unknown";
        }
        if (component.category != null) {
            String role = switch (component.category) {
                case DOOR -> "door";
                case WINDOW -> "window";
                case BALCONY -> "balcony";
                case RAILING -> "railing";
                case PANEL -> "panel";
                case COLUMN, BRACKET -> "column";
                case STAIRS -> "stairs";
                case ARCH, ORNAMENT, ROOF_DETAIL -> "ornament";
                default -> null;
            };
            if (role != null) {
                return role;
            }
        }
        if (component.tags != null) {
            for (String tag : component.tags) {
                if (tag == null) continue;
                String lower = tag.toLowerCase();
                if (lower.contains("door") || lower.contains("门")) return "door";
                if (lower.contains("window") || lower.contains("窗")) return "window";
                if (lower.contains("balcony") || lower.contains("阳台")) return "balcony";
                if (lower.contains("railing") || lower.contains("guard") || lower.contains("栏杆")) return "railing";
                if (lower.contains("panel") || lower.contains("栏板")) return "panel";
                if (lower.contains("column") || lower.contains("pillar") || lower.contains("柱")) return "column";
            }
        }
        return "decoration";
    }

    private static PlacementSpec mapPlacementSpec(
            com.formacraft.common.component.ComponentDefinition component,
            com.formacraft.common.component.archetype.ComponentArchetype archetype
    ) {
        if (component != null && component.placementSpec != null) {
            var ps = component.placementSpec;
            PlacementSpec out = new PlacementSpec();
            out.allowedPlacements = allowedPlacementsFromAttachment(ps.attachment);
            out.sidePolicy = sidePolicyFromSpatialContext(ps.spatialContext);
            out.requiresOpening = ps.requiresOpening;
            if (ps.requireEdge) {
                out.edgePreference = "flat";
            }
            if (out.allowedPlacements != null && !out.allowedPlacements.isEmpty()) {
                return out;
            }
        }
        if (archetype != null && archetype.attachment != null) {
            PlacementSpec out = new PlacementSpec();
            var attachment = archetype.attachment;
            if (attachment.allowedContexts != null) {
                out.allowedPlacements = attachment.allowedContexts.stream()
                        .map(Enum::name)
                        .map(String::toLowerCase)
                        .toList();
            }
            if (attachment.allowedSides != null) {
                if (attachment.allowedSides.size() == 1) {
                    var side = attachment.allowedSides.iterator().next();
                    out.sidePolicy = side == com.formacraft.common.component.archetype.SurfaceSide.EXTERIOR
                            ? "exterior_only"
                            : side == com.formacraft.common.component.archetype.SurfaceSide.INTERIOR
                            ? "interior_only"
                            : "both";
                } else {
                    out.sidePolicy = "both";
                }
            }
            out.requiresOpening = attachment.type == com.formacraft.common.component.archetype.AttachmentType.SURFACE;
            return out;
        }
        return null;
    }

    private static java.util.List<String> allowedPlacementsFromAttachment(
            com.formacraft.common.component.placement.AttachmentType attachment
    ) {
        if (attachment == null || attachment == com.formacraft.common.component.placement.AttachmentType.NONE) {
            return java.util.List.of("ground", "free");
        }
        return switch (attachment) {
            case WALL_OPENING, WALL_SURFACE -> java.util.List.of("wall");
            case ROOF_SURFACE, ROOF_RIDGE -> java.util.List.of("roof");
            case ROOF_EDGE, EDGE -> java.util.List.of("edge", "wall");
            case FLOOR -> java.util.List.of("ground", "floor");
            case CORNER -> java.util.List.of("corner", "wall");
            default -> java.util.List.of("wall");
        };
    }

    private static String sidePolicyFromSpatialContext(
            com.formacraft.common.component.placement.SpatialContext spatialContext
    ) {
        if (spatialContext == null) {
            return "both";
        }
        return switch (spatialContext) {
            case EXTERIOR -> "exterior_only";
            case INTERIOR -> "interior_only";
            case ANY -> "both";
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
