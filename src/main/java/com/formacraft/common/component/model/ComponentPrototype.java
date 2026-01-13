package com.formacraft.common.component.model;

import com.formacraft.common.component.ComponentCategory;
import com.formacraft.common.component.placement.AttachmentType;
import com.formacraft.common.component.placement.ComponentPlacementSpec;
import com.formacraft.common.component.placement.FacingPolicy;
import com.formacraft.common.component.placement.PlacementConstraints;
import com.formacraft.common.component.placement.SpatialContext;

import java.util.List;
import java.util.Map;

/**
 * Prototype（原型）v1：跨存档共享的“母体构件”定义。
 * <p>
 * 设计目标：
 * - JSON 结构稳定（以 schema 字段版本化）
 * - 面向后续 Variant/Instance 扩展（分段缩放、材质语义映射等）
 */
public class ComponentPrototype {
    public String schema = "formacraft.component.prototype.v1";

    public String id;
    public String name;
    public ComponentCategory category = ComponentCategory.GENERIC;
    public List<String> tags;

    public StructureRef structure;
    /** 与旧系统的 ComponentPlacementSpec 同语义，但字段名保持 JSON v1 规范（snake_case）。 */
    public Placement placement;
    public VariantRules variant_rules;

    public static class StructureRef {
        /** "nbt"（预期），也允许 "json"/"patch" 等后续格式 */
        public String format;
        /** 例如 "structure.nbt" */
        public String file;
        public Bounds bounds;
        public Anchor anchor;
        /** "NORTH"/"SOUTH"/"EAST"/"WEST" */
        public String default_facing;

        public static class Bounds { public int w, h, d; }
        public static class Anchor { public int x, y, z; }
    }

    public static class Placement {
        /** AttachmentType name（例如 "WALL_OPENING"） */
        public String attachment;
        /** SpatialContext name（"INTERIOR"/"EXTERIOR"/"ANY"） */
        public String spatial_context;
        /** FacingPolicy name（例如 "DERIVED_FROM_HOST"） */
        public String facing_policy;
        public boolean has_interior_exterior = false;

        public Constraints constraints;

        public static class Constraints {
            public boolean requires_attachment = false;
            public int min_attachments = 0;
            public int max_attachments = 1;
            public boolean requires_edge = false;
            public boolean requires_support_below = false;
            public boolean forbid_interior = false;
            public boolean respect_protected_zones = true;
            public boolean prefers_continuity = false;
            public Integer min_height = null;
            public Integer max_height = null;
        }
    }

    public static class VariantRules {
        public Scaling scaling;
        public Material material;
        public Details details;

        public static class Scaling {
            /** 例如 "SEGMENTED" */
            public String mode;
            /** key: "X"/"Y"/"Z" */
            public Map<String, AxisRule> axes;

            public static class AxisRule {
                /** 
                 * "FIXED" / "REPEAT" / "TRIM"
                 * - FIXED: 该轴固定不变（例如门的宽度/深度）
                 * - REPEAT: 重复中段（例如门拉高 = START + MID(repeat N) + END）
                 * - TRIM: 裁剪中段（例如栏杆缩短 = START + MID(trim N) + END）
                 */
                public String type;
                /** 最小尺寸约束（单位：方块数） */
                public Integer min;
                /** 最大尺寸约束（单位：方块数） */
                public Integer max;
                /** 
                 * 重复/裁剪的分段标签（例如 "MID_X" / "MID_Y" / "MID_Z"）
                 * 与结构模板中的 segment tag 对应
                 */
                public String segment;
            }
        }

        public static class Material {
            /** group -> SemanticBlock / SemanticPart id（由后续 StyleProfile 解释） */
            public Map<String, String> semantic_map;
        }

        public static class Details {
            public List<String> ornament_levels;
        }
    }

    /**
     * 将 prototype.json 的 placement 映射为运行时使用的 ComponentPlacementSpec（供 Tool/Validator/Prompt 复用）。
     * <p>
     * 这是“最小桥接”：不引入新依赖，不改变现有放置系统。
     */
    public ComponentPlacementSpec toPlacementSpecOrNull() {
        if (placement == null) return null;
        ComponentPlacementSpec out = new ComponentPlacementSpec();
        try {
            if (placement.attachment != null) out.attachment = AttachmentType.valueOf(placement.attachment.trim());
        } catch (Throwable ignored) {}
        try {
            if (placement.spatial_context != null) out.spatialContext = SpatialContext.valueOf(placement.spatial_context.trim());
        } catch (Throwable ignored) {}
        try {
            if (placement.facing_policy != null) out.facingPolicy = FacingPolicy.valueOf(placement.facing_policy.trim());
        } catch (Throwable ignored) {}
        out.hasInteriorExterior = placement.has_interior_exterior;

        // constraints（字段命名不同，做一次拷贝）
        out.constraints = new PlacementConstraints();
        Placement.Constraints c = placement.constraints;
        if (c != null) {
            out.constraints.requiresAttachment = c.requires_attachment;
            out.constraints.minAttachments = c.min_attachments;
            out.constraints.maxAttachments = c.max_attachments;
            out.constraints.requiresEdge = c.requires_edge;
            out.constraints.requiresSupportBelow = c.requires_support_below;
            out.constraints.forbidInterior = c.forbid_interior;
            out.constraints.respectProtectedZones = c.respect_protected_zones;
            out.constraints.prefersContinuity = c.prefers_continuity;
            out.constraints.minHeight = c.min_height;
            out.constraints.maxHeight = c.max_height;
        }
        return out;
    }
}

