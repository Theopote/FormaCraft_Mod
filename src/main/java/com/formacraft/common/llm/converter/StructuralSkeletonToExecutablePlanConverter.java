package com.formacraft.common.llm.converter;

import com.formacraft.common.llm.dto.StructuralSkeleton;
import com.formacraft.common.skeleton.SkeletonType;
import com.formacraft.server.skeleton.gen.ExecutableSkeletonPlan;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * StructuralSkeleton → ExecutableSkeletonPlan 转换器
 * <p>
 * 核心职责：将 StructuralSkeleton 的"3D结构骨架"转换为 ExecutableSkeletonPlan（Formacraft Skeleton）
 * <p>
 * 映射规则：
 * 1. WallSegment → WALL Skeleton（EXTERNAL/INTERNAL/COURTYARD）
 * 2. FloorPlate → FLOOR Skeleton
 * 3. RoofPlate → ROOF Skeleton
 * 4. CourtyardVoid → 不直接生成 Skeleton（但影响周围 wall 的生成）
 * <p>
 * 设计原则：
 * - 完全兼容现有的 ExecutableSkeletonPlan + Generator 系统
 * - 自动触发 SocketProvider 的能力（WALL_SURFACE、EDGE_OUTER、WALL_OPENING 等）
 */
public final class StructuralSkeletonToExecutablePlanConverter {

    private StructuralSkeletonToExecutablePlanConverter() {}

    /**
     * 转换 StructuralSkeleton 为 ExecutableSkeletonPlan 列表
     * <p>
     * 返回多个 ExecutableSkeletonPlan，因为一个 StructuralSkeleton 可能包含多个结构元素
     * 
     * @param structural StructuralSkeleton（3D 结构骨架）
     * @return ExecutableSkeletonPlan 列表（可执行的骨架计划）
     */
    public static List<ExecutableSkeletonPlan> convert(StructuralSkeleton structural) {
        if (structural == null) {
            throw new IllegalArgumentException("StructuralSkeleton cannot be null");
        }

        List<ExecutableSkeletonPlan> plans = new ArrayList<>();

        // 1. WallSegments → WALL Skeletons
        if (structural.wallSegments() != null) {
            for (StructuralSkeleton.WallSegment wall : structural.wallSegments()) {
                if (wall == null) continue;
                ExecutableSkeletonPlan wallPlan = convertWallSegment(wall);
                if (wallPlan != null) {
                    plans.add(wallPlan);
                }
            }
        }

        // 2. FloorPlate → FLOOR Skeleton（可选，v1 可以先跳过）
        // 未来：如果系统需要独立的 FLOOR Skeleton，可以在这里生成

        // 3. RoofPlate → ROOF Skeleton（可选，v1 可以先跳过）
        // 未来：如果系统需要独立的 ROOF Skeleton，可以在这里生成

        return plans;
    }

    /**
     * 转换 WallSegment 为 ExecutableSkeletonPlan
     * <p>
     * 映射逻辑：
     * - EXTERNAL → 生成 WALL Skeleton，context = EXTERIOR
     * - INTERNAL → 生成 WALL Skeleton，context = INTERIOR
     * - COURTYARD → 生成 WALL Skeleton，context = COURTYARD
     * <p>
     * 自动触发的能力：
     * - SkeletonSocketProfile.forWall() 会生成：
     *   - WALL_SURFACE
     *   - WALL_OPENING
     *   - EDGE_OUTER（仅 EXTERNAL）
     */
    private static ExecutableSkeletonPlan convertWallSegment(StructuralSkeleton.WallSegment wall) {
        // v1 简化：使用 PERIMETER_LOOP 作为基础类型（因为现有系统有 WallGenerator）
        // 未来：可以引入新的 SkeletonType.WALL（如果系统扩展）
        ExecutableSkeletonPlan plan = new ExecutableSkeletonPlan(SkeletonType.PERIMETER_LOOP);

        // 设置基础参数
        if (wall.baseLine() != null && !wall.baseLine().isEmpty()) {
            // 计算长度（从 baseLine 的两个端点）
            if (wall.baseLine().size() >= 2) {
                BlockPos start = wall.baseLine().get(0);
                BlockPos end = wall.baseLine().get(wall.baseLine().size() - 1);
                int length = (int) Math.sqrt(
                        Math.pow(end.getX() - start.getX(), 2) +
                        Math.pow(end.getZ() - start.getZ(), 2)
                );
                plan.length = Math.max(1, length);
            }

            // 设置 points（用于 PERIMETER_LOOP）
            plan.put("points", wall.baseLine());
        }

        // 设置高度
        if (wall.height() != null) {
            plan.height = Math.max(1, wall.height());
            plan.put("height", wall.height());
        }

        // 设置宽度（墙厚度，默认 1）
        plan.width = 1;
        plan.put("width", 1);

        // 设置上下文信息（用于后续 Socket 生成）
        plan.put("wall_kind", wall.kind());  // EXTERNAL / INTERNAL / COURTYARD
        plan.put("wall_zones", wall.zoneIds());

        // 设置 normal（法线方向）
        if (wall.normal() != null) {
            plan.put("wall_normal", wall.normal());
        }

        return plan;
    }
}
