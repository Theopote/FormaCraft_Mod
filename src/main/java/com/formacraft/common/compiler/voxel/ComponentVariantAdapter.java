package com.formacraft.common.compiler.voxel;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.model.ComponentVariant;

/**
 * ComponentVariantAdapter（构件变体适配器）：将新的 ComponentVariant 适配为旧的 ComponentVariant。
 * <p>
 * 这是临时适配层，用于兼容现有的 ComponentVoxelizer 和 ComponentPlanCompiler。
 * <p>
 * 未来可以逐步迁移到新的 ComponentVariant 系统。
 */
public final class ComponentVariantAdapter {
    private ComponentVariantAdapter() {}

    /**
     * 将新的 ComponentVariant 转换为旧的 ComponentVariant
     */
    public static ComponentVariant adapt(
            com.formacraft.common.component.variant.ComponentVariant newVariant,
            ComponentDefinition base
    ) {
        if (newVariant == null || base == null) {
            return null;
        }

        ComponentVariant oldVariant = new ComponentVariant();
        oldVariant.prototype_id = base.id;
        oldVariant.variant_id = generateVariantId(newVariant);

        // 创建参数
        ComponentVariant.Params params = new ComponentVariant.Params();
        
        // 缩放
        ComponentVariant.Params.Scale scale = new ComponentVariant.Params.Scale();
        scale.x = Math.round(newVariant.scaleX);
        scale.y = Math.round(newVariant.scaleY);
        scale.z = Math.round(newVariant.scaleZ);
        params.scale = scale;

        // 镜像
        if (newVariant.mirrored) {
            params.mirror = newVariant.mirrorAxis == com.formacraft.common.component.variant.ComponentVariantSpec.Axis.X ? "X" : "Z";
        } else {
            params.mirror = "NONE";
        }

        // 材质语义
        if (newVariant.materialSemantic != null) {
            params.material_set = newVariant.materialSemantic;
        }

        oldVariant.params = params;
        return oldVariant;
    }

    /**
     * 生成变体 ID（基于变体参数）
     */
    private static String generateVariantId(com.formacraft.common.component.variant.ComponentVariant variant) {
        StringBuilder sb = new StringBuilder();
        sb.append("v_");
        sb.append(Math.round(variant.scaleX * 100)).append("_");
        sb.append(Math.round(variant.scaleY * 100)).append("_");
        sb.append(Math.round(variant.scaleZ * 100));
        if (variant.mirrored) {
            sb.append("_m").append(variant.mirrorAxis.name());
        }
        if (variant.repeatCount > 0) {
            sb.append("_r").append(variant.repeatCount).append(variant.repeatAxis.name());
        }
        return sb.toString();
    }
}
