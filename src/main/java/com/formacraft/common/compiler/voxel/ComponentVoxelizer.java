package com.formacraft.common.compiler.voxel;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.model.ComponentVariant;
import com.formacraft.common.placement.PlacementContext;
import net.minecraft.util.math.Direction;


/**
 * ComponentVoxelizer：将 ComponentDefinition 转换为 VoxelPlan。
 * <p>
 * 核心算法：
 * - 遍历 ComponentDefinition.blocks
 * - 应用 ComponentVariant 的缩放和镜像
 * - 应用 PlacementContext 的变换（方向、镜像、对称）
 * - 生成语义块（SemanticBlock）
 * <p>
 * 关键思想：
 * - 组件本身永远是"正向 + 原点"
 * - 方向、镜像、对称全部由 PlacementContext 决定
 */
public final class ComponentVoxelizer {
    private ComponentVoxelizer() {}

    /**
     * 将 ComponentDefinition 体素化为 VoxelPlan
     * 
     * @param component 构件定义（已验证）
     * @param variant 构件变体（已解析/确定）
     * @param ctx 放置上下文（anchor / facing / surface / tool 约束）
     * @return VoxelPlan（相对 anchor 的体素计划）
     */
    public static VoxelPlan voxelize(
            ComponentDefinition component,
            ComponentVariant variant,
            PlacementContext ctx
    ) {
        VoxelPlan plan = new VoxelPlan();

        if (component == null || component.blocks == null || component.blocks.isEmpty()) {
            return plan;
        }

        // 获取变体参数（缩放、镜像）
        int scaleX = 1, scaleY = 1, scaleZ = 1;
        boolean mirrorX = false, mirrorZ = false;
        
        if (variant != null && variant.params != null) {
            if (variant.params.scale != null) {
                scaleX = Math.max(1, variant.params.scale.x);
                scaleY = Math.max(1, variant.params.scale.y);
                scaleZ = Math.max(1, variant.params.scale.z);
            }
            if (variant.params.mirror != null) {
                String mirror = variant.params.mirror.toUpperCase();
                mirrorX = mirror.contains("X");
                mirrorZ = mirror.contains("Z");
            }
        }

        // 获取放置方向（从 PlacementContext 或 ComponentDefinition.anchor.facing）
        Direction facing = resolveFacing(ctx, component);
        
        // 获取 anchor 偏移（ComponentDefinition 中的 anchor 是相对于组件内部的）
        int anchorDx = 0, anchorDy = 0, anchorDz = 0;
        if (component.anchor != null) {
            anchorDx = component.anchor.dx;
            anchorDy = component.anchor.dy;
            anchorDz = component.anchor.dz;
        }
        
        // 遍历所有方块条目
        for (ComponentDefinition.BlockEntry entry : component.blocks) {
            if (entry == null) continue;

            // 应用变体缩放（相对于 anchor）
            int dx = (entry.dx - anchorDx) * scaleX;
            int dy = (entry.dy - anchorDy) * scaleY;
            int dz = (entry.dz - anchorDz) * scaleZ;

            // 应用镜像（在缩放之后）
            if (mirrorX) {
                // 镜像 X 轴（需要知道组件宽度）
                int componentWidth = component.size != null ? component.size.w : 1;
                dx = (componentWidth * scaleX - 1) - dx;
            }
            if (mirrorZ) {
                // 镜像 Z 轴（需要知道组件深度）
                int componentDepth = component.size != null ? component.size.d : 1;
                dz = (componentDepth * scaleZ - 1) - dz;
            }

            // 应用方向变换（通过 PlacementContext.transformLocal）
            VoxelPlan.Vec3i local = transformLocal(dx, dy, dz, facing, ctx);

            // 创建语义块
            // 注意：如果 BlockEntry 有 block 字符串，我们需要保存它以便回退
            SemanticBlock semanticBlock = SemanticBlock.fromBlockEntry(entry);

            // 添加到计划
            plan.put(local.x(), local.y(), local.z(), semanticBlock);
        }

        return plan;
    }

    /**
     * 解析放置方向
     */
    private static Direction resolveFacing(PlacementContext ctx, ComponentDefinition component) {
        // 优先使用 PlacementContext 的方向（如果有）
        if (ctx != null && ctx.hitFace != null) {
            return ctx.hitFace;
        }

        // 其次使用 ComponentDefinition.anchor.facing
        if (component != null && component.anchor != null && component.anchor.facing != null) {
            String facingStr = component.anchor.facing.toUpperCase();
            return switch (facingStr) {
                case "NORTH" -> Direction.NORTH;
                case "EAST" -> Direction.EAST;
                case "WEST" -> Direction.WEST;
                default -> Direction.SOUTH;
            };
        }

        // 默认方向
        return Direction.SOUTH;
    }

    /**
     * 变换局部坐标（应用方向）
     * <p>
     * 注意：这里只处理方向旋转，不处理镜像（镜像已在变体阶段处理）
     */
    private static VoxelPlan.Vec3i transformLocal(int dx, int dy, int dz, Direction facing, PlacementContext ctx) {
        // 如果 PlacementContext 有 transformLocal 方法，使用它
        // 否则，根据 facing 进行简单的旋转
        return switch (facing) {
            case NORTH -> new VoxelPlan.Vec3i(-dx, dy, -dz);  // 180度旋转
            case EAST -> new VoxelPlan.Vec3i(-dz, dy, dx);     // 90度顺时针
            case WEST -> new VoxelPlan.Vec3i(dz, dy, -dx);    // 90度逆时针
            case SOUTH -> new VoxelPlan.Vec3i(dx, dy, dz);     // 默认（无旋转）
            default -> new VoxelPlan.Vec3i(dx, dy, dz);
        };
    }
}
