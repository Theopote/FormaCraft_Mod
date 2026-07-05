package com.formacraft.common.component.variant;

import com.formacraft.common.component.ComponentDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * ComponentVariantApplier（Phase 8）：把 {@link ComponentVariant} 的<b>几何</b>变形
 * （整数放大 + 裁剪）落到一个新的 {@link ComponentDefinition} 上，供
 * {@code PlayerComponentExpander} 展开时直接使用。
 * <p>
 * 设计取舍（保守、可回退）：
 * <ul>
 *   <li>只处理"整数放大"（scale ≥ ~1.5 才生效）与"裁剪"；分数缩放/降采样/镜像不在此处理，
 *       其中<b>镜像</b>由展开器按 reqMap 统一处理，避免双重镜像；</li>
 *   <li>恒等变体（无放大、无裁剪）直接返回原 {@code base}，行为零变化；</li>
 *   <li>材质语义替换仍走展开器既有的 {@code semantic_skin} 路径，这里不改材质。</li>
 * </ul>
 */
public final class ComponentVariantApplier {
    private ComponentVariantApplier() {}

    /** 上限，避免 AI 给出离谱缩放导致构件爆炸式放大。 */
    private static final int MAX_SCALE = 4;

    public static ComponentDefinition apply(ComponentDefinition base, ComponentVariant variant) {
        if (base == null || variant == null || base.blocks == null || base.blocks.isEmpty()) {
            return base;
        }

        int sx = intScale(variant.scaleX);
        int sy = intScale(variant.scaleY);
        int sz = intScale(variant.scaleZ);
        boolean scaling = sx > 1 || sy > 1 || sz > 1;
        boolean repeating = variant.repeatCount > 1;
        boolean trimming = variant.trimmedWidth != null || variant.trimmedHeight != null || variant.trimmedDepth != null;

        if (!scaling && !trimming && !repeating) {
            return base;
        }

        return applyInternal(base, variant, scaling, repeating, trimming, sx, sy, sz);
    }

    /**
     * 应用变体但跳过分段 repeat（SegmentScaler 已处理 repeat 时使用）。
     */
    public static ComponentDefinition applyWithoutRepeat(ComponentDefinition base, ComponentVariant variant) {
        if (base == null || variant == null || base.blocks == null || base.blocks.isEmpty()) {
            return base;
        }

        int sx = intScale(variant.scaleX);
        int sy = intScale(variant.scaleY);
        int sz = intScale(variant.scaleZ);
        boolean scaling = sx > 1 || sy > 1 || sz > 1;
        boolean trimming = variant.trimmedWidth != null || variant.trimmedHeight != null || variant.trimmedDepth != null;

        if (!scaling && !trimming) {
            return base;
        }

        return applyInternal(base, variant, scaling, false, trimming, sx, sy, sz);
    }

    public static ComponentDefinition cloneWithBlocksPublic(
            ComponentDefinition base,
            List<ComponentDefinition.BlockEntry> blocks
    ) {
        return cloneWithBlocks(base, blocks);
    }

    private static ComponentDefinition applyInternal(
            ComponentDefinition base,
            ComponentVariant variant,
            boolean scaling,
            boolean repeating,
            boolean trimming,
            int sx,
            int sy,
            int sz
    ) {
        List<ComponentDefinition.BlockEntry> src = base.blocks;

        // 1) 整数放大（最近邻）
        if (scaling) {
            List<ComponentDefinition.BlockEntry> scaled = new ArrayList<>(src.size() * sx * sy * sz);
            for (ComponentDefinition.BlockEntry be : src) {
                if (be == null) continue;
                for (int i = 0; i < sx; i++) {
                    for (int j = 0; j < sy; j++) {
                        for (int k = 0; k < sz; k++) {
                            ComponentDefinition.BlockEntry e = new ComponentDefinition.BlockEntry();
                            e.dx = be.dx * sx + i;
                            e.dy = be.dy * sy + j;
                            e.dz = be.dz * sz + k;
                            e.block = be.block;
                            e.semantic = be.semantic;
                            scaled.add(e);
                        }
                    }
                }
            }
            src = scaled;
        }

        // 2) 分段重复（沿指定轴平铺整段几何，用于栏杆/窗带等）
        if (repeating) {
            src = applyAxisRepeat(src, variant.repeatAxis, variant.repeatCount);
        }

        // 3) 裁剪（相对 min 角，超出目标尺寸的体素丢弃）
        if (trimming) {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            for (ComponentDefinition.BlockEntry be : src) {
                if (be == null) continue;
                minX = Math.min(minX, be.dx);
                minY = Math.min(minY, be.dy);
                minZ = Math.min(minZ, be.dz);
            }
            if (minX == Integer.MAX_VALUE) {
                minX = minY = minZ = 0;
            }
            int limX = variant.trimmedWidth != null && variant.trimmedWidth > 0 ? variant.trimmedWidth : Integer.MAX_VALUE;
            int limY = variant.trimmedHeight != null && variant.trimmedHeight > 0 ? variant.trimmedHeight : Integer.MAX_VALUE;
            int limZ = variant.trimmedDepth != null && variant.trimmedDepth > 0 ? variant.trimmedDepth : Integer.MAX_VALUE;

            List<ComponentDefinition.BlockEntry> trimmed = new ArrayList<>(src.size());
            for (ComponentDefinition.BlockEntry be : src) {
                if (be == null) continue;
                if ((be.dx - minX) < limX && (be.dy - minY) < limY && (be.dz - minZ) < limZ) {
                    trimmed.add(be);
                }
            }
            if (!trimmed.isEmpty()) {
                src = trimmed;
            }
        }

        return cloneWithBlocks(base, src);
    }

    /**
     * 沿单轴平铺 repeatCount 份（整段复制，stride = 该轴跨度）。
     */
    private static List<ComponentDefinition.BlockEntry> applyAxisRepeat(
            List<ComponentDefinition.BlockEntry> src,
            ComponentVariantSpec.Axis axis,
            int repeatCount
    ) {
        if (src == null || src.isEmpty() || repeatCount <= 1 || axis == null) {
            return src;
        }

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (ComponentDefinition.BlockEntry be : src) {
            if (be == null) continue;
            minX = Math.min(minX, be.dx); maxX = Math.max(maxX, be.dx);
            minY = Math.min(minY, be.dy); maxY = Math.max(maxY, be.dy);
            minZ = Math.min(minZ, be.dz); maxZ = Math.max(maxZ, be.dz);
        }
        if (minX == Integer.MAX_VALUE) {
            return src;
        }

        int stride = switch (axis) {
            case Y -> maxY - minY + 1;
            case Z -> maxZ - minZ + 1;
            default -> maxX - minX + 1;
        };
        if (stride <= 0) {
            return src;
        }

        List<ComponentDefinition.BlockEntry> out = new ArrayList<>(src.size() * repeatCount);
        for (int i = 0; i < repeatCount; i++) {
            int shift = i * stride;
            for (ComponentDefinition.BlockEntry be : src) {
                if (be == null) continue;
                ComponentDefinition.BlockEntry e = new ComponentDefinition.BlockEntry();
                e.block = be.block;
                e.semantic = be.semantic;
                e.dx = be.dx + (axis == ComponentVariantSpec.Axis.X ? shift : 0);
                e.dy = be.dy + (axis == ComponentVariantSpec.Axis.Y ? shift : 0);
                e.dz = be.dz + (axis == ComponentVariantSpec.Axis.Z ? shift : 0);
                out.add(e);
            }
        }
        return out;
    }

    /** 把 float 缩放折算成整数放大因子（<1.5 视为不放大，返回 1）。 */
    private static int intScale(float s) {
        if (Float.isNaN(s) || s < 1.5f) {
            return 1;
        }
        int v = Math.round(s);
        return Math.max(1, Math.min(MAX_SCALE, v));
    }

    /**
     * 浅拷贝 base、替换 blocks 与 size，其余字段（sockets/placementSpec/anchor…）沿用引用。
     * 变体是运行时产物，不存盘，因此共享只读字段是安全的。
     */
    private static ComponentDefinition cloneWithBlocks(ComponentDefinition base, List<ComponentDefinition.BlockEntry> blocks) {
        ComponentDefinition out = new ComponentDefinition();
        out.schema = base.schema;
        out.version = base.version;
        out.id = base.id;
        out.name = base.name;
        out.category = base.category;
        out.tags = base.tags;
        out.culturalStyle = base.culturalStyle;
        out.archetypeRef = base.archetypeRef;
        out.geometryArchetype = base.geometryArchetype;
        out.anchor = base.anchor;
        out.allowed_facing = base.allowed_facing;
        out.placement_rules = base.placement_rules;
        out.placementSpec = base.placementSpec;
        out.directionHints = base.directionHints;
        out.anchorHint = base.anchorHint;
        out.placementHints = base.placementHints;
        out.sockets = base.sockets;
        out.socketPlacements = base.socketPlacements;
        out.blocks = blocks;

        // 重新计算 bounding size
        ComponentDefinition.Size size = new ComponentDefinition.Size();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (ComponentDefinition.BlockEntry be : blocks) {
            if (be == null) continue;
            minX = Math.min(minX, be.dx); maxX = Math.max(maxX, be.dx);
            minY = Math.min(minY, be.dy); maxY = Math.max(maxY, be.dy);
            minZ = Math.min(minZ, be.dz); maxZ = Math.max(maxZ, be.dz);
        }
        if (minX == Integer.MAX_VALUE) {
            size.w = base.size != null ? base.size.w : 0;
            size.h = base.size != null ? base.size.h : 0;
            size.d = base.size != null ? base.size.d : 0;
        } else {
            size.w = maxX - minX + 1;
            size.h = maxY - minY + 1;
            size.d = maxZ - minZ + 1;
        }
        out.size = size;
        return out;
    }
}
