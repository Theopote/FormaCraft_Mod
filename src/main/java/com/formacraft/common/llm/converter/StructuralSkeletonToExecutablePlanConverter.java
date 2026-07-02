package com.formacraft.common.llm.converter;

import com.formacraft.common.geometry.Vec2;
import com.formacraft.common.geometry.extrusion.ExtrudedSolid;
import com.formacraft.common.geometry.extrusion.WallExtrusion;
import com.formacraft.common.llm.dto.StructuralSkeleton;
import com.formacraft.common.skeleton.SkeletonType;
import com.formacraft.common.skeleton.ExecutableSkeletonPlan;
import com.formacraft.FormacraftMod;
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
 * 2. FloorPlate → FLOOR Skeleton（v1 暂不生成）
 * 3. RoofPlate → ROOF Skeleton（v1 暂不生成）
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

        // 1. Walls → WALL Skeletons
        if (structural.walls != null) {
            for (StructuralSkeleton.WallSegment wall : structural.walls) {
                if (wall == null) continue;
                ExecutableSkeletonPlan wallPlan = convertWallSegment(wall);
                plans.add(wallPlan);
            }
        }

        // 2. FloorPlate → FLOOR Skeleton（可选，v1 可以先跳过）
        // 未来：如果系统需要独立的 FLOOR Skeleton，可以在这里生成

        // 3. RoofPlate → ROOF Skeleton（使用 RoofSocketGenerator 生成 Socket）
        if (structural.roofPlate != null) {
            // 生成屋顶 Skeleton（已包含在 WallSegment 处理中）
            // 如果需要额外的屋顶 Socket，可以使用 RoofSocketGenerator
            try {
                @SuppressWarnings("unused")
                var roofSockets = com.formacraft.common.component.roof.RoofSocketGenerator.generateRoofSockets(
                        structural.roofPlate,
                        structural // 传递 structural 用于生成 EAVE_LINE Socket
                );
                // v1 简化：屋顶 Socket 暂时不转换为 ExecutableSkeletonPlan
                // 未来：可以将 RoofSocket 转换为 ROOF_SLOPE / ROOF_RIDGE 类型的 Skeleton
                // 这些 Socket 可以用于屋顶构件装配（屋瓦、脊兽、檐饰等）
            } catch (Exception e) {
                FormacraftMod.LOGGER.warn("Failed to generate roof sockets", e);
            }
        }

        // 4. CourtyardVoid → 不直接生成 Skeleton（但影响周围 wall 的生成）

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
     * <p>
     * 几何信息：
     * - 执行 extrusion 生成 ExtrudedSolid（存储在 params 中，供未来使用）
     * - 提取关键参数用于 ExecutableSkeletonPlan（与现有 Generator 兼容）
     */
    private static ExecutableSkeletonPlan convertWallSegment(StructuralSkeleton.WallSegment wall) {
        // v1 简化：使用 PERIMETER_LOOP 作为基础类型（因为现有系统有 WallGenerator）
        // 未来：可以引入新的 SkeletonType.WALL（如果系统扩展）
        ExecutableSkeletonPlan plan = new ExecutableSkeletonPlan(SkeletonType.PERIMETER_LOOP);

        // 设置基础参数
        if (wall.baseline != null) {
            List<Vec2> points = wall.baseline.getPoints();
            if (!points.isEmpty()) {
                // 转换为 BlockPos 列表（用于 PERIMETER_LOOP）
                List<BlockPos> blockPosPoints = new ArrayList<>();
                for (Vec2 point : points) {
                    blockPosPoints.add(new BlockPos((int) point.x(), (int) wall.heightProfile.baseY, (int) point.z()));
                }
                
                // 计算长度
                if (points.size() >= 2) {
                    Vec2 start = points.getFirst();
                    Vec2 end = points.getLast();
                    double length = start.distanceTo(end);
                    plan.length = Math.max(1, (int) length);
                }

                // 设置 points（用于 PERIMETER_LOOP）
                plan.put("points", blockPosPoints);
            }
        }

        // 设置高度（从 HeightProfile）
        if (wall.heightProfile != null) {
            int height = (int) wall.heightProfile.height();
            plan.height = Math.max(1, height);
            plan.put("height", height);
        }

        // 设置宽度（墙厚度）
        plan.width = (int) wall.thickness;
        plan.put("width", (int) wall.thickness);

        // 设置上下文信息（用于后续 Socket 生成）
        plan.put("wall_kind", wall.type.name());  // EXTERNAL / INTERNAL / COURTYARD
        plan.put("wall_zones", new ArrayList<>(wall.zones));

        // 设置 normal（法线方向）
        if (wall.normal != null) {
            plan.put("wall_normal", wall.normal.toString());
        }

        // 执行 extrusion 生成 3D 几何（存储在 params 中，供未来使用）
        // 注意：当前 Generator 可能不使用 ExtrudedSolid，但为未来的几何查询/调试/可视化保留
        List<ExtrudedSolid> extrudedSolids = WallExtrusion.extrude(wall);
        if (!extrudedSolids.isEmpty()) {
            // v1：存储第一个 solid（折线墙可能产生多个，后续可以扩展）
            plan.put("extruded_solid", extrudedSolids.getFirst());
            // 存储所有 solids（如果是折线墙）
            if (extrudedSolids.size() > 1) {
                plan.put("extruded_solids", extrudedSolids);
            }
        }

        return plan;
    }
}
