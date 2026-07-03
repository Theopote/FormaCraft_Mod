package com.formacraft.common.compiler.voxel;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.model.PersistedComponentVariant;
import com.formacraft.common.component.variant.ComponentVariant;

/**
 * 将运行时 {@link ComponentVariant} 适配为存盘 schema {@link PersistedComponentVariant}。
 * <p>
 * 供 {@link ComponentVoxelizer} 等仍消费持久化变体文档的编译路径使用。
 */
public final class ComponentVariantAdapter {
    private ComponentVariantAdapter() {}

    /**
     * 将运行时变体转换为持久化变体文档（用于 voxel 编译管线）。
     */
    public static PersistedComponentVariant adapt(
            ComponentVariant runtimeVariant,
            ComponentDefinition base
    ) {
        if (runtimeVariant == null || base == null) {
            return null;
        }

        PersistedComponentVariant persisted = new PersistedComponentVariant();
        persisted.prototype_id = base.id;
        persisted.variant_id = generateVariantId(runtimeVariant);

        PersistedComponentVariant.Params params = new PersistedComponentVariant.Params();

        PersistedComponentVariant.Params.Scale scale = new PersistedComponentVariant.Params.Scale();
        scale.x = Math.round(runtimeVariant.scaleX);
        scale.y = Math.round(runtimeVariant.scaleY);
        scale.z = Math.round(runtimeVariant.scaleZ);
        params.scale = scale;

        if (runtimeVariant.mirrored) {
            params.mirror = runtimeVariant.mirrorAxis == com.formacraft.common.component.variant.ComponentVariantSpec.Axis.X ? "X" : "Z";
        } else {
            params.mirror = "NONE";
        }

        if (runtimeVariant.materialSemantic != null) {
            params.material_set = runtimeVariant.materialSemantic;
        }

        persisted.params = params;
        return persisted;
    }

    private static String generateVariantId(ComponentVariant variant) {
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
