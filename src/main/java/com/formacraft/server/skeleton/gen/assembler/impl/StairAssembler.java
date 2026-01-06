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
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * STAIR（楼梯/高差连接）装配器
 * 
 * 语义用途：
 * - 地形高差
 * - 建筑入口台阶
 * - 中庭上下层连接
 * - 山地建筑"依山就势"的关键
 * 
 * 这是"不破坏地形"的核心器官之一
 * 
 * ComponentSpec 约定：
 * {
 *   "type": "STAIR",
 *   "params": {
 *     "from": "ground",
 *     "to": "platform",
 *     "direction": "north | south | east | west",
 *     "steps": 5
 *   }
 * }
 */
public class StairAssembler implements ComponentAssembler {

    @Override
    public List<SemanticPlacementOp> assemble(
            GenerationContext ctx,
            ExecutableSkeletonPlan skeleton,
            ComponentSpec component
    ) {
        List<SemanticPlacementOp> ops = new ArrayList<>();
        if (component.type != ComponentType.STAIR) return ops;

        // 从 params 获取参数
        int steps = getIntParam(component, "steps", 5);
        String dirStr = getStringParam(component, "direction", "north");

        Direction dir = parseDirection(dirStr);
        if (dir == null) dir = Direction.NORTH;

        // 获取楼梯起点
        BlockPos start;
        if (component.offsetX != 0 || component.offsetY != 0 || component.offsetZ != 0) {
            start = ctx.origin.add(component.offsetX, component.offsetY, component.offsetZ);
        } else {
            start = SkeletonHelper.getStairStart(ctx, skeleton);
            if (start == null) start = ctx.origin;
        }

        int baseY = ctx.getSurfaceY(start.getX(), start.getZ());

        // 生成台阶
        for (int i = 0; i < steps; i++) {
            BlockPos stepPos = start
                    .offset(dir, i)
                    .up(i);

            // 确保台阶在地表之上
            int stepY = Math.max(baseY + i, ctx.getSurfaceY(stepPos.getX(), stepPos.getZ()) + i);
            stepPos = new BlockPos(stepPos.getX(), stepY, stepPos.getZ());

            ops.add(SemanticPlacementOp.of(stepPos, SemanticPart.STAIR_STEP));
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

    private static String getStringParam(ComponentSpec component, String key, String defaultValue) {
        Object v = component.params.get(key);
        if (v instanceof String s) return s;
        if (v != null) return String.valueOf(v);
        return defaultValue;
    }

    private static Direction parseDirection(String dirStr) {
        if (dirStr == null || dirStr.isBlank()) return Direction.NORTH;
        try {
            return Direction.valueOf(dirStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Direction.NORTH;
        }
    }
}

