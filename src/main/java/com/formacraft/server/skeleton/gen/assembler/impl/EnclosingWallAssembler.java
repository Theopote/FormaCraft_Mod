package com.formacraft.server.skeleton.gen.assembler.impl;

import com.formacraft.common.component.ComponentSpec;
import com.formacraft.common.component.ComponentType;
import com.formacraft.common.semantic.SemanticPart;
import com.formacraft.common.semantic.SemanticPlacementOp;
import com.formacraft.common.skeleton.SkeletonType;
import com.formacraft.common.skeleton.ExecutableSkeletonPlan;
import com.formacraft.common.skeleton.SkeletonParamParsers;
import com.formacraft.server.skeleton.gen.GenerationContext;
import com.formacraft.server.skeleton.gen.assembler.ComponentAssembler;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * ENCLOSING_WALL 装配器（围墙）
 * 
 * 适用于：
 * - 城堡城墙
 * - 土楼外墙
 * - 寺庙围合
 */
public class EnclosingWallAssembler implements ComponentAssembler {

    @Override
    public List<SemanticPlacementOp> assemble(
            GenerationContext ctx,
            ExecutableSkeletonPlan skeleton,
            ComponentSpec component
    ) {
        List<SemanticPlacementOp> ops = new ArrayList<>();

        if (component.type != ComponentType.ENCLOSING_WALL) return ops;

        // Skeleton 必须是围合类
        SkeletonType skType = skeleton.type;
        if (!isEnclosingType(skType)) return ops;

        int height = component.height > 0 ? component.height : 6;
        BlockPos origin = ctx.origin;

        // 根据不同的 Skeleton 类型生成围墙
        switch (skType) {
            case RADIAL_RING -> {
                // 环形骨架：生成圆形围墙
                int radius = skeleton.get("radius", 10);
                ops.addAll(generateRadialRingWall(ctx, origin, radius, height));
            }
            case PERIMETER_LOOP, ENCLOSURE -> {
                // 轮廓闭环：沿轮廓生成围墙
                ops.addAll(generatePerimeterWall(ctx, skeleton, origin, height));
            }
            case COURTYARD -> {
                // 中庭式：生成矩形围墙
                int width = skeleton.get("width", 16);
                int depth = skeleton.get("depth", 16);
                ops.addAll(generateRectangularWall(ctx, origin, width, depth, height));
            }
            default -> {
                // 其他类型：尝试生成基础围墙
                ops.addAll(generateBasicWall(ctx, skeleton, origin, height));
            }
        }

        return ops;
    }

    /**
     * 判断是否为围合类型
     */
    private static boolean isEnclosingType(SkeletonType type) {
        return type == SkeletonType.RADIAL_RING
                || type == SkeletonType.PERIMETER_LOOP
                || type == SkeletonType.ENCLOSURE
                || type == SkeletonType.COURTYARD;
    }

    /**
     * 生成环形围墙
     */
    private List<SemanticPlacementOp> generateRadialRingWall(
            GenerationContext ctx,
            BlockPos center,
            int radius,
            int height
    ) {
        List<SemanticPlacementOp> ops = new ArrayList<>();
        
        // 使用简化的圆形生成（可以后续优化）
        int samples = Math.max(24, radius * 4);
        for (int i = 0; i < samples; i++) {
            double angle = (Math.PI * 2.0) * (i / (double) samples);
            int x = center.getX() + (int) Math.round(Math.cos(angle) * radius);
            int z = center.getZ() + (int) Math.round(Math.sin(angle) * radius);
            int y = ctx.getSurfaceY(x, z);

            for (int dy = 0; dy < height; dy++) {
                ops.add(SemanticPlacementOp.of(
                        new BlockPos(x, y + dy, z),
                        SemanticPart.WALL
                ));
            }
        }
        
        return ops;
    }

    /**
     * 生成轮廓围墙
     */
    private List<SemanticPlacementOp> generatePerimeterWall(
            GenerationContext ctx,
            ExecutableSkeletonPlan skeleton,
            BlockPos origin,
            int height
    ) {
        List<SemanticPlacementOp> ops = new ArrayList<>();
        
        // 从 skeleton 的 points 参数获取轮廓点
        Object ptsObj = skeleton.params.get("points");
        if (!(ptsObj instanceof List<?> pts) || pts.size() < 3) {
            return ops;
        }

        List<BlockPos> perimeter = new ArrayList<>();
        for (Object o : pts) {
            if (!(o instanceof java.util.Map<?, ?> m)) continue;
            int dx = SkeletonParamParsers.intValue(m.get("dx"), 0);
            int dy = SkeletonParamParsers.intValue(m.get("dy"), 0);
            int dz = SkeletonParamParsers.intValue(m.get("dz"), 0);
            BlockPos pos = origin.add(dx, dy, dz);
            perimeter.add(pos);
        }

        // 沿轮廓生成围墙
        for (BlockPos edge : perimeter) {
            int y = ctx.getSurfaceY(edge.getX(), edge.getZ());
            for (int dy = 0; dy < height; dy++) {
                ops.add(SemanticPlacementOp.of(
                        new BlockPos(edge.getX(), y + dy, edge.getZ()),
                        SemanticPart.WALL
                ));
            }
        }
        
        return ops;
    }

    /**
     * 生成矩形围墙
     */
    private List<SemanticPlacementOp> generateRectangularWall(
            GenerationContext ctx,
            BlockPos origin,
            int width,
            int depth,
            int height
    ) {
        List<SemanticPlacementOp> ops = new ArrayList<>();
        
        // 生成矩形边界
        for (int x = 0; x <= width; x++) {
            int y1 = ctx.getSurfaceY(origin.getX() + x, origin.getZ());
            int y2 = ctx.getSurfaceY(origin.getX() + x, origin.getZ() + depth);
            for (int dy = 0; dy < height; dy++) {
                ops.add(SemanticPlacementOp.of(
                        new BlockPos(origin.getX() + x, y1 + dy, origin.getZ()),
                        SemanticPart.WALL
                ));
                ops.add(SemanticPlacementOp.of(
                        new BlockPos(origin.getX() + x, y2 + dy, origin.getZ() + depth),
                        SemanticPart.WALL
                ));
            }
        }
        
        for (int z = 0; z <= depth; z++) {
            int y1 = ctx.getSurfaceY(origin.getX(), origin.getZ() + z);
            int y2 = ctx.getSurfaceY(origin.getX() + width, origin.getZ() + z);
            for (int dy = 0; dy < height; dy++) {
                ops.add(SemanticPlacementOp.of(
                        new BlockPos(origin.getX(), y1 + dy, origin.getZ() + z),
                        SemanticPart.WALL
                ));
                ops.add(SemanticPlacementOp.of(
                        new BlockPos(origin.getX() + width, y2 + dy, origin.getZ() + z),
                        SemanticPart.WALL
                ));
            }
        }
        
        return ops;
    }

    /**
     * 生成基础围墙（兜底方案）
     */
    private List<SemanticPlacementOp> generateBasicWall(
            GenerationContext ctx,
            ExecutableSkeletonPlan skeleton,
            BlockPos origin,
            int height
    ) {
        // 简单实现：在 origin 周围生成一个小围墙
        List<SemanticPlacementOp> ops = new ArrayList<>();
        int size = 10; // 默认大小
        
        for (int x = -size; x <= size; x++) {
            for (int z = -size; z <= size; z++) {
                if (Math.abs(x) == size || Math.abs(z) == size) {
                    int y = ctx.getSurfaceY(origin.getX() + x, origin.getZ() + z);
                    for (int dy = 0; dy < height; dy++) {
                        ops.add(SemanticPlacementOp.of(
                                new BlockPos(origin.getX() + x, y + dy, origin.getZ() + z),
                                SemanticPart.WALL
                        ));
                    }
                }
            }
        }
        
        return ops;
    }

}

