package com.formacraft.common.component.variant;

import com.formacraft.common.component.model.ComponentPrototype;
import com.formacraft.common.component.model.ComponentVariant;
import com.formacraft.common.component.transform.Mirror;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.style.profile.StyleProfile;
import net.minecraft.util.math.Direction;

import java.util.List;

/**
 * ComponentVariantCompiler（变体编译器）v1：核心编译流水线。
 * <p>
 * 职责：
 * - 将 Prototype + Variant + StyleProfile 编译为 BlockPatch 列表
 * - 支持：分段重复（REPEAT）/ 裁剪（TRIM）/ 固定段（FIXED）/ 语义材质映射 / 朝向/镜像变换
 * <p>
 * 编译流水线：
 * <pre>
 * 1. 加载结构模板（StructureLoader）
 * 2. 分段缩放（SegmentScaler）
 * 3. 语义材质替换（SemanticMaterialApplier）
 * 4. 朝向/镜像变换（TransformApplier）
 * 5. 转成 BlockPatch（PatchEmitter）
 * </pre>
 */
public final class ComponentVariantCompiler {
    private ComponentVariantCompiler() {}

    private static final FcaLog LOG = FcaLog.of("ComponentVariantCompiler");

    /**
     * 编译变体（核心入口）。
     * <p>
     * 参数：
     * - proto: 原型（结构模板 + variant_rules）
     * - variant: 变体（scale / material_set / ornament_level / mirror）
     * - targetFacing: 目标朝向（世界坐标系）
     * - style: 风格配置（用于语义材质映射）
     * <p>
     * 返回：
     * - List<BlockPatch>（相对 origin 的偏移）
     */
    public static List<BlockPatch> compile(
            ComponentPrototype proto,
            ComponentVariant variant,
            Direction targetFacing,
            StyleProfile style
    ) {
        if (proto == null) return List.of();
        if (variant == null || variant.params == null) return List.of();

        // 0. 确定原始朝向（从 prototype.structure.default_facing）
        Direction originalFacing = parseOriginalFacing(proto);

        // 1. 加载结构模板
        StructureTemplate tpl = StructureLoader.load(proto);
        if (tpl == null || tpl.all().isEmpty()) return List.of();

        // 2. 分段缩放（REPEAT/TRIM/FIXED）
        VoxelGrid grid = SegmentScaler.applyScaling(tpl, getScalingRules(proto), variant.params.scale);

        // 3. 语义材质替换（semantic_map → StyleProfile.palette）
        if (style != null && proto.variant_rules != null && proto.variant_rules.material != null) {
            SemanticMaterialApplier.apply(grid, proto.variant_rules.material, variant.params.material_set, style);
        }

        // 4. 朝向/镜像变换
        Mirror mirror = parseMirror(variant.params.mirror);
        grid = TransformApplier.apply(grid, targetFacing, mirror, originalFacing);

        // 5. 转成 BlockPatch
        return PatchEmitter.emit(grid);
    }

    /**
     * 获取 scaling rules（兼容 proto.variant_rules 为 null 的情况）。
     */
    private static ComponentPrototype.VariantRules.Scaling getScalingRules(ComponentPrototype proto) {
        if (proto.variant_rules == null) return null;
        return proto.variant_rules.scaling;
    }

    /**
     * 解析原始朝向（从 proto.structure.default_facing）。
     */
    private static Direction parseOriginalFacing(ComponentPrototype proto) {
        if (proto.structure == null || proto.structure.default_facing == null) return Direction.SOUTH;
        try {
            Direction d = Direction.valueOf(proto.structure.default_facing.trim().toUpperCase());
            return d.getAxis().isHorizontal() ? d : Direction.SOUTH;
        } catch (Throwable t) {
            LOG.debug("parse default_facing failed componentId={} value={}", proto.id, proto.structure.default_facing, t);
            return Direction.SOUTH;
        }
    }

    /**
     * 解析镜像模式（从 variant.params.mirror）。
     */
    private static Mirror parseMirror(String mirror) {
        if (mirror == null || mirror.isBlank()) return Mirror.NONE;
        try {
            return Mirror.valueOf(mirror.trim().toUpperCase());
        } catch (Throwable t) {
            LOG.debug("parse mirror failed value={}", mirror, t);
            return Mirror.NONE;
        }
    }
}
