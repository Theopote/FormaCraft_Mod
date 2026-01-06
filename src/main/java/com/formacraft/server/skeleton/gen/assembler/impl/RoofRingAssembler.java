package com.formacraft.server.skeleton.gen.assembler.impl;

import com.formacraft.common.component.ComponentSpec;
import com.formacraft.common.component.ComponentType;
import com.formacraft.common.semantic.SemanticPart;
import com.formacraft.common.semantic.SemanticPlacementOp;
import com.formacraft.server.skeleton.gen.ExecutableSkeletonPlan;
import com.formacraft.server.skeleton.gen.GenerationContext;
import com.formacraft.server.skeleton.gen.assembler.ComponentAssembler;
import com.formacraft.server.skeleton.gen.assembler.util.SkeletonHelper;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * ROOF_RING 装配器
 * 
 * 语义目标：
 * - 福建土楼屋顶
 * - 天坛圆殿顶
 * - 环形檐口（极其重要）
 * 
 * ComponentSpec 约定：
 * {
 *   "type": "ROOF_RING",
 *   "params": {
 *     "inner_radius": 8,
 *     "outer_radius": 10,
 *     "height": 1,
 *     "eaves": true
 *   }
 * }
 */
public class RoofRingAssembler implements ComponentAssembler {

    @Override
    public List<SemanticPlacementOp> assemble(
            GenerationContext ctx,
            ExecutableSkeletonPlan skeleton,
            ComponentSpec component
    ) {
        List<SemanticPlacementOp> ops = new ArrayList<>();
        if (component.type != ComponentType.ROOF_RING) return ops;

        // 从 params 获取参数
        int innerRadius = getIntParam(component, "inner_radius", 6);
        int outerRadius = getIntParam(component, "outer_radius", 8);
        int height = getIntParam(component, "height", 1);
        boolean eaves = getBoolParam(component, "eaves", true);

        // 如果 outerRadius 为 0，尝试从 skeleton 的 radius 计算
        if (outerRadius == 0) {
            int skeletonRadius = skeleton.get("radius", 0);
            if (skeletonRadius > 0) {
                outerRadius = skeletonRadius;
                innerRadius = Math.max(1, skeletonRadius - 2);
            } else {
                outerRadius = 8;
                innerRadius = 6;
            }
        }

        // 获取中心点
        BlockPos center = SkeletonHelper.getCenter(ctx, skeleton);
        if (center == null) center = ctx.origin;

        // 获取屋顶高度
        int roofY = SkeletonHelper.getMaxHeight(ctx, skeleton);

        // 生成环形屋顶
        for (int dx = -outerRadius; dx <= outerRadius; dx++) {
            for (int dz = -outerRadius; dz <= outerRadius; dz++) {
                int distSq = dx * dx + dz * dz;
                // 在 innerRadius 和 outerRadius 之间的环形区域
                if (distSq >= innerRadius * innerRadius && distSq <= outerRadius * outerRadius) {
                    // 屋顶表面
                    for (int dy = 0; dy < height; dy++) {
                        ops.add(SemanticPlacementOp.of(
                                new BlockPos(center.getX() + dx, roofY + dy, center.getZ() + dz),
                                SemanticPart.ROOF_SURFACE
                        ));
                    }

                    // 檐口（如果启用）
                    if (eaves && distSq >= (outerRadius - 1) * (outerRadius - 1) && distSq <= outerRadius * outerRadius) {
                        ops.add(SemanticPlacementOp.of(
                                new BlockPos(center.getX() + dx, roofY + height, center.getZ() + dz),
                                SemanticPart.ROOF,  // 使用 ROOF 作为檐口
                                com.formacraft.common.semantic.SemanticRole.TRIM
                        ));
                    }
                }
            }
        }

        return ops;
    }

    private static int getIntParam(ComponentSpec component, String key, int defaultValue) {
        Object v = component.params.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (Exception e) { return defaultValue; }
        }
        return defaultValue;
    }

    private static boolean getBoolParam(ComponentSpec component, String key, boolean defaultValue) {
        Object v = component.params.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        if (v instanceof Number n) return n.intValue() != 0;
        return defaultValue;
    }
}

