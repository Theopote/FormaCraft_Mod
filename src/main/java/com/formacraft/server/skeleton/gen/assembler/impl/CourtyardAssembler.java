package com.formacraft.server.skeleton.gen.assembler.impl;

import com.formacraft.common.component.ComponentSpec;
import com.formacraft.common.skeleton.SkeletonParamParsers;
import com.formacraft.common.component.ComponentType;
import com.formacraft.common.semantic.SemanticPart;
import com.formacraft.common.semantic.SemanticPlacementOp;
import com.formacraft.common.skeleton.ExecutableSkeletonPlan;
import com.formacraft.server.skeleton.gen.GenerationContext;
import com.formacraft.server.skeleton.gen.assembler.ComponentAssembler;
import com.formacraft.server.skeleton.gen.assembler.util.SkeletonHelper;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * COURTYARD 装配器（改进版）
 * 
 * 语义目标：
 * - 土楼中庭
 * - 寺庙天井
 * - 城堡内院
 * 
 * ⚠️ 注意：COURTYARD = 刻意"不建造"的空间
 * 
 * ComponentSpec 约定：
 * {
 *   "type": "COURTYARD",
 *   "params": {
 *     "shape": "circle | rectangle",
 *     "radius": 6,
 *     "floor": "stone | grass | empty"
 *   }
 * }
 */
public class CourtyardAssembler implements ComponentAssembler {

    @Override
    public List<SemanticPlacementOp> assemble(
            GenerationContext ctx,
            ExecutableSkeletonPlan skeleton,
            ComponentSpec component
    ) {
        List<SemanticPlacementOp> ops = new ArrayList<>();
        if (component.type != ComponentType.COURTYARD) return ops;

        // 从 params 获取参数
        int radius = SkeletonParamParsers.componentInt(component, "radius", 0);
        String shape = getStringParam(component, "shape", "rectangle");
        String floor = getStringParam(component, "floor", "stone");

        // 如果 radius 为 0，尝试从 width/depth 计算
        int width = component.width > 0 ? component.width : 10;
        int depth = component.depth > 0 ? component.depth : 10;
        if (radius == 0) {
            radius = Math.min(width, depth) / 2;
        }

        // 获取中心点：优先使用 component.offset，否则从 Skeleton 获取
        BlockPos center;
        if (component.offsetX != 0 || component.offsetY != 0 || component.offsetZ != 0) {
            center = ctx.origin.add(component.offsetX, component.offsetY, component.offsetZ);
        } else {
            center = SkeletonHelper.getCenter(ctx, skeleton);
            if (center == null) center = ctx.origin;
        }

        int baseY = ctx.getSurfaceY(center.getX(), center.getZ());

        // 如果 floor 是 "empty"，不生成地面
        if ("empty".equalsIgnoreCase(floor)) {
            return ops; // 返回空列表，表示"不建造"的空间
        }

        // 生成中庭地面
        if ("circle".equalsIgnoreCase(shape)) {
            // 圆形中庭
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz <= radius * radius) {
                        ops.add(SemanticPlacementOp.of(
                                new BlockPos(center.getX() + dx, baseY, center.getZ() + dz),
                                SemanticPart.COURTYARD_FLOOR
                        ));
                    }
                }
            }
        } else {
            // 矩形中庭
            int halfW = width / 2;
            int halfD = depth / 2;
            for (int dx = -halfW; dx <= halfW; dx++) {
                for (int dz = -halfD; dz <= halfD; dz++) {
                    ops.add(SemanticPlacementOp.of(
                            new BlockPos(center.getX() + dx, baseY, center.getZ() + dz),
                            SemanticPart.COURTYARD_FLOOR
                    ));
                }
            }
        }

        return ops;
    }


    private static String getStringParam(ComponentSpec component, String key, String defaultValue) {
        Object v = component.params.get(key);
        if (v instanceof String s) return s;
        if (v != null) return String.valueOf(v);
        return defaultValue;
    }
}

