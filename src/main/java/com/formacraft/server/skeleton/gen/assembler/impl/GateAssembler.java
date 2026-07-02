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
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * GATE（门/入口）装配器
 * 
 * 语义用途：
 * - 城门
 * - 建筑主入口
 * - 中庭入口
 * - 桥头入口
 * 
 * 本质：在"围护结构"上打一个洞 + 标注入口语义
 * 
 * ComponentSpec 约定：
 * {
 *   "type": "GATE",
 *   "params": {
 *     "width": 3,
 *     "height": 4,
 *     "position": "south | north | east | west | auto"
 *   }
 * }
 */
public class GateAssembler implements ComponentAssembler {

    @Override
    public List<SemanticPlacementOp> assemble(
            GenerationContext ctx,
            ExecutableSkeletonPlan skeleton,
            ComponentSpec component
    ) {
        List<SemanticPlacementOp> ops = new ArrayList<>();
        if (component.type != ComponentType.GATE) return ops;

        // 从 params 获取参数
        int width = SkeletonParamParsers.componentInt(component, "width", 3);
        int height = SkeletonParamParsers.componentInt(component, "height", 4);
        String position = getStringParam(component, "position", "auto");

        // Skeleton 决定门的位置（例如城墙中点）
        BlockPos gateBase = SkeletonHelper.getGatePosition(ctx, skeleton, position);
        if (gateBase == null) {
            // 如果没有，使用 component offset
            gateBase = ctx.origin.add(component.offsetX, component.offsetY, component.offsetZ);
        }

        Direction facing = SkeletonHelper.getGateFacing(skeleton, position);

        // 门洞（OPENING 语义）
        for (int dx = -width / 2; dx <= width / 2; dx++) {
            for (int dy = 0; dy < height; dy++) {
                BlockPos p = gateBase
                        .up(dy)
                        .offset(facing.rotateYClockwise(), dx);
                ops.add(SemanticPlacementOp.of(p, SemanticPart.GATE_OPENING));
            }
        }

        // 门框/门楣
        ops.add(SemanticPlacementOp.of(
                gateBase.up(height),
                SemanticPart.GATE_LINTEL
        ));

        return ops;
    }


    private static String getStringParam(ComponentSpec component, String key, String defaultValue) {
        Object v = component.params.get(key);
        if (v instanceof String s) return s;
        if (v != null) return String.valueOf(v);
        return defaultValue;
    }
}

