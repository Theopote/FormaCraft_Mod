package com.formacraft.server.skeleton.gen.assembler.util;

import com.formacraft.common.skeleton.SkeletonType;
import com.formacraft.common.skeleton.ExecutableSkeletonPlan;
import com.formacraft.server.skeleton.gen.GenerationContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Skeleton 辅助工具类
 * 
 * 提供从 Skeleton 获取关键位置的方法
 */
public final class SkeletonHelper {
    private SkeletonHelper() {}

    /**
     * 获取 Skeleton 的中心点
     */
    public static BlockPos getCenter(GenerationContext ctx, ExecutableSkeletonPlan skeleton) {
        if (skeleton == null || ctx == null) return null;

        SkeletonType type = skeleton.type;
        BlockPos origin = ctx.origin;

        switch (type) {
            case RADIAL_RING:
            case RADIAL_SPOKE:
                // 径向类型：中心就是 origin
                return origin;
            case COURTYARD:
            case GRID:
                // 矩形类型：计算中心
                int width = skeleton.get("width", 16);
                int depth = skeleton.get("depth", 16);
                return origin.add(width / 2, 0, depth / 2);
            default:
                // 默认返回 origin
                return origin;
        }
    }

    /**
     * 获取塔位列表（用于 TOWER 组件）
     */
    public static List<BlockPos> getTowerBases(GenerationContext ctx, ExecutableSkeletonPlan skeleton) {
        List<BlockPos> bases = new ArrayList<>();
        if (skeleton == null || ctx == null) return bases;

        SkeletonType type = skeleton.type;
        BlockPos origin = ctx.origin;

        switch (type) {
            case RADIAL_RING: {
                // 环形：四个角
                int radius = skeleton.get("radius", 10);
                bases.add(origin.add(radius, 0, 0));
                bases.add(origin.add(-radius, 0, 0));
                bases.add(origin.add(0, 0, radius));
                bases.add(origin.add(0, 0, -radius));
                break;
            }
            case GRID: {
                // 网格：四个角
                int width = skeleton.get("width", 24);
                int depth = skeleton.get("depth", 24);
                bases.add(origin.add(0, 0, 0));
                bases.add(origin.add(width, 0, 0));
                bases.add(origin.add(0, 0, depth));
                bases.add(origin.add(width, 0, depth));
                break;
            }
            case COURTYARD: {
                // 中庭：四个角
                int width = skeleton.get("width", 16);
                int depth = skeleton.get("depth", 16);
                bases.add(origin.add(0, 0, 0));
                bases.add(origin.add(width, 0, 0));
                bases.add(origin.add(0, 0, depth));
                bases.add(origin.add(width, 0, depth));
                break;
            }
            default:
                // 默认：使用 component 的 offset
                // 如果没有 offset，使用 origin
                bases.add(origin);
                break;
        }

        return bases;
    }

    /**
     * 获取 Skeleton 的最大高度
     */
    public static int getMaxHeight(GenerationContext ctx, ExecutableSkeletonPlan skeleton) {
        if (skeleton == null || ctx == null) return ctx.origin.getY();

        int height = skeleton.height;
        if (height > 0) {
            return ctx.origin.getY() + height;
        }

        // 默认高度
        return ctx.origin.getY() + 10;
    }

    /**
     * 获取门的位置
     * 
     * @param ctx 生成上下文
     * @param skeleton 骨架计划
     * @param position 位置（south/north/east/west/auto）
     * @return 门的基础位置
     */
    public static BlockPos getGatePosition(GenerationContext ctx, ExecutableSkeletonPlan skeleton, String position) {
        if (skeleton == null || ctx == null) return null;

        BlockPos center = getCenter(ctx, skeleton);
        if (center == null) center = ctx.origin;

        SkeletonType type = skeleton.type;
        Direction facing = parseDirection(position);

        // 根据 Skeleton 类型计算门位置
        switch (type) {
            case RADIAL_RING: {
                // 环形：在指定方向的外围
                int radius = skeleton.get("radius", 10);
                return center.add(
                    facing.getOffsetX() * radius,
                    0,
                    facing.getOffsetZ() * radius
                );
            }
            case COURTYARD:
            case GRID: {
                // 矩形：在指定方向的边缘中点
                int width = skeleton.get("width", 16);
                int depth = skeleton.get("depth", 16);
                if (facing == Direction.SOUTH || facing == Direction.NORTH) {
                    return center.add(width / 2, 0, facing == Direction.SOUTH ? depth : -depth);
                } else {
                    return center.add(facing == Direction.EAST ? width : -width, 0, depth / 2);
                }
            }
            default:
                // 默认：在指定方向偏移
                return center.offset(facing, 5);
        }
    }

    /**
     * 获取门的朝向
     */
    public static Direction getGateFacing(ExecutableSkeletonPlan skeleton, String position) {
        Direction facing = parseDirection(position);
        if (facing != null) {
            return facing.getOpposite(); // 门朝向内部
        }
        return Direction.NORTH;
    }

    /**
     * 获取楼梯起点
     */
    public static BlockPos getStairStart(GenerationContext ctx, ExecutableSkeletonPlan skeleton) {
        if (skeleton == null || ctx == null) return ctx.origin;

        // 默认从 Skeleton 的入口位置开始
        BlockPos gatePos = getGatePosition(ctx, skeleton, "auto");
        if (gatePos != null) {
            return gatePos;
        }

        return ctx.origin;
    }

    /**
     * 获取步道路径
     */
    public static List<BlockPos> getWalkwayPath(GenerationContext ctx, ExecutableSkeletonPlan skeleton) {
        List<BlockPos> path = new ArrayList<>();
        if (skeleton == null || ctx == null) return path;

        SkeletonType type = skeleton.type;
        BlockPos origin = ctx.origin;

        switch (type) {
            case RADIAL_RING: {
                // 环形：沿外围生成路径
                int radius = skeleton.get("radius", 10);
                int samples = Math.max(24, radius * 4);
                for (int i = 0; i < samples; i++) {
                    double angle = (Math.PI * 2.0) * (i / (double) samples);
                    int x = origin.getX() + (int) Math.round(Math.cos(angle) * radius);
                    int z = origin.getZ() + (int) Math.round(Math.sin(angle) * radius);
                    path.add(new BlockPos(x, 0, z));
                }
                break;
            }
            case LINEAR_PATH: {
                // 直线路径：沿路径生成
                int length = skeleton.get("length", 10);
                Direction facing = skeleton.facing;
                for (int i = 0; i <= length; i++) {
                    path.add(origin.add(
                        facing.getOffsetX() * i,
                        0,
                        facing.getOffsetZ() * i
                    ));
                }
                break;
            }
            default:
                // 默认：从 origin 到中心
                BlockPos center = getCenter(ctx, skeleton);
                if (center != null) {
                    path.add(origin);
                    path.add(center);
                }
                break;
        }

        return path;
    }

    /**
     * 解析方向字符串
     */
    private static Direction parseDirection(String dirStr) {
        if (dirStr == null || dirStr.isBlank() || "auto".equalsIgnoreCase(dirStr)) {
            return Direction.SOUTH; // 默认南向
        }
        try {
            return Direction.valueOf(dirStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Direction.SOUTH;
        }
    }
}

