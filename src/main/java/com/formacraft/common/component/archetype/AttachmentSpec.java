package com.formacraft.common.component.archetype;

import java.util.Set;

/**
 * AttachmentSpec（附着规格）：定义构件如何附着到建筑结构上。
 * <p>
 * 这是"内外 / 边缘 / 支撑 / 附着面"思想的正式落地。
 */
public class AttachmentSpec {
    /**
     * 附着类型
     */
    public AttachmentType type = AttachmentType.SURFACE;

    /**
     * 允许的上下文类型
     */
    public Set<ContextType> allowedContexts;

    /**
     * 允许的表面侧
     */
    public Set<SurfaceSide> allowedSides;

    /**
     * 是否必须有承重面（支撑）
     */
    public boolean requireSupport = false;

    /**
     * 创建默认的 AttachmentSpec（SURFACE + WALL + BOTH）
     */
    public static AttachmentSpec createDefault() {
        AttachmentSpec spec = new AttachmentSpec();
        spec.type = AttachmentType.SURFACE;
        spec.allowedContexts = Set.of(ContextType.WALL);
        spec.allowedSides = Set.of(SurfaceSide.BOTH);
        spec.requireSupport = false;
        return spec;
    }

    /**
     * 创建门的 AttachmentSpec
     */
    public static AttachmentSpec forDoor() {
        AttachmentSpec spec = new AttachmentSpec();
        spec.type = AttachmentType.SURFACE;
        spec.allowedContexts = Set.of(ContextType.WALL);
        spec.allowedSides = Set.of(SurfaceSide.BOTH);
        spec.requireSupport = true;
        return spec;
    }

    /**
     * 创建窗的 AttachmentSpec
     */
    public static AttachmentSpec forWindow() {
        AttachmentSpec spec = new AttachmentSpec();
        spec.type = AttachmentType.SURFACE;
        spec.allowedContexts = Set.of(ContextType.WALL);
        spec.allowedSides = Set.of(SurfaceSide.BOTH);
        spec.requireSupport = true;
        return spec;
    }

    /**
     * 创建柱的 AttachmentSpec
     */
    public static AttachmentSpec forColumn() {
        AttachmentSpec spec = new AttachmentSpec();
        spec.type = AttachmentType.POINT;
        spec.allowedContexts = Set.of(ContextType.FREE);
        spec.allowedSides = Set.of(SurfaceSide.BOTH);
        spec.requireSupport = true;
        return spec;
    }

    /**
     * 创建栏杆的 AttachmentSpec
     */
    public static AttachmentSpec forRailing() {
        AttachmentSpec spec = new AttachmentSpec();
        spec.type = AttachmentType.EDGE;
        spec.allowedContexts = Set.of(ContextType.EDGE);
        spec.allowedSides = Set.of(SurfaceSide.EXTERIOR);
        spec.requireSupport = true;
        return spec;
    }

    /**
     * 创建阳台的 AttachmentSpec
     */
    public static AttachmentSpec forBalcony() {
        AttachmentSpec spec = new AttachmentSpec();
        spec.type = AttachmentType.SURFACE;
        spec.allowedContexts = Set.of(ContextType.WALL);
        spec.allowedSides = Set.of(SurfaceSide.EXTERIOR);
        spec.requireSupport = true;
        return spec;
    }
}
