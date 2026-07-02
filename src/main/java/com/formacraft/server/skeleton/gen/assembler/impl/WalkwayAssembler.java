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
 * WALKWAY（道路/连廊/城墙步道）装配器
 * 
 * 语义用途：
 * - 城墙巡逻道
 * - 建筑之间连接
 * - 桥面
 * - 内院回廊
 * 
 * WALKWAY = 可走的"语义线"
 * 
 * ComponentSpec 约定：
 * {
 *   "type": "WALKWAY",
 *   "params": {
 *     "width": 2,
 *     "connect": "tower_to_tower | gate_to_keep | custom"
 *   }
 * }
 */
public class WalkwayAssembler implements ComponentAssembler {

    @Override
    public List<SemanticPlacementOp> assemble(
            GenerationContext ctx,
            ExecutableSkeletonPlan skeleton,
            ComponentSpec component
    ) {
        List<SemanticPlacementOp> ops = new ArrayList<>();
        if (component.type != ComponentType.WALKWAY) return ops;

        // 从 params 获取参数
        int width = SkeletonParamParsers.componentInt(component, "width", 2);
        String connect = getStringParam(component, "connect", "custom");

        // 获取步道路径
        List<BlockPos> path;
        if (component.offsetX != 0 || component.offsetZ != 0) {
            // 如果有自定义偏移，生成简单路径
            path = generateCustomPath(ctx, component);
        } else {
            // 从 Skeleton 获取路径
            path = SkeletonHelper.getWalkwayPath(ctx, skeleton);
        }

        if (path == null || path.isEmpty()) return ops;

        // 根据连接类型调整路径
        if ("tower_to_tower".equalsIgnoreCase(connect)) {
            path = adjustPathForTowers(ctx, skeleton, path);
        } else if ("gate_to_keep".equalsIgnoreCase(connect)) {
            path = adjustPathForGateToKeep(ctx, skeleton, path);
        }

        // 生成步道
        for (BlockPos p : path) {
            int y = ctx.getSurfaceY(p.getX(), p.getZ());

            // 横向扩展（宽度）
            int halfWidth = width / 2;
            for (int dx = -halfWidth; dx <= halfWidth; dx++) {
                for (int dz = -halfWidth; dz <= halfWidth; dz++) {
                    if (dx * dx + dz * dz <= halfWidth * halfWidth) {
                        ops.add(SemanticPlacementOp.of(
                                new BlockPos(p.getX() + dx, y, p.getZ() + dz),
                                SemanticPart.WALKWAY_FLOOR
                        ));
                    }
                }
            }
        }

        return ops;
    }

    /**
     * 生成自定义路径
     */
    private List<BlockPos> generateCustomPath(GenerationContext ctx, ComponentSpec component) {
        List<BlockPos> path = new ArrayList<>();
        BlockPos start = ctx.origin.add(component.offsetX, 0, component.offsetZ);
        
        // 简单实现：从 start 到 origin（可以后续扩展）
        path.add(start);
        path.add(ctx.origin);
        
        return path;
    }

    /**
     * 调整路径以连接塔楼
     */
    private List<BlockPos> adjustPathForTowers(GenerationContext ctx, ExecutableSkeletonPlan skeleton, List<BlockPos> basePath) {
        List<BlockPos> towers = SkeletonHelper.getTowerBases(ctx, skeleton);
        if (towers.size() < 2) return basePath;

        // 简单实现：连接前两个塔
        List<BlockPos> path = new ArrayList<>();
        path.add(towers.get(0));
        path.add(towers.get(1));
        return path;
    }

    /**
     * 调整路径以连接门到核心
     */
    private List<BlockPos> adjustPathForGateToKeep(GenerationContext ctx, ExecutableSkeletonPlan skeleton, List<BlockPos> basePath) {
        BlockPos gate = SkeletonHelper.getGatePosition(ctx, skeleton, "auto");
        BlockPos center = SkeletonHelper.getCenter(ctx, skeleton);
        
        if (gate == null || center == null) return basePath;

        List<BlockPos> path = new ArrayList<>();
        path.add(gate);
        path.add(center);
        return path;
    }


    private static String getStringParam(ComponentSpec component, String key, String defaultValue) {
        Object v = component.params.get(key);
        if (v instanceof String s) return s;
        if (v != null) return String.valueOf(v);
        return defaultValue;
    }
}

