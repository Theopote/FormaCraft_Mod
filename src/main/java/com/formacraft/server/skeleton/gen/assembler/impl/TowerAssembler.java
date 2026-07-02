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
 * TOWER 装配器（改进版）
 * 
 * 语义目标：
 * - 角楼 / 中心塔 / 观景塔
 * - 适配 RADIAL_RING / GRID / COMPOUND
 * - 支持圆塔 / 方塔
 * 
 * ComponentSpec 约定：
 * {
 *   "type": "TOWER",
 *   "params": {
 *     "shape": "cylinder | cuboid",
 *     "radius": 3,
 *     "height": 14,
 *     "position": "corner | center | custom"
 *   }
 * }
 */
public class TowerAssembler implements ComponentAssembler {

    @Override
    public List<SemanticPlacementOp> assemble(
            GenerationContext ctx,
            ExecutableSkeletonPlan skeleton,
            ComponentSpec component
    ) {
        List<SemanticPlacementOp> ops = new ArrayList<>();
        if (component.type != ComponentType.TOWER) return ops;

        // 从 params 获取参数，如果没有则使用默认值或 component 字段
        int height = SkeletonParamParsers.componentInt(component, "height", component.height > 0 ? component.height : 12);
        int radius = SkeletonParamParsers.componentInt(component, "radius", 0);
        String shape = getStringParam(component, "shape", "cuboid");

        // 如果 radius 为 0，尝试从 width/depth 计算
        if (radius == 0) {
            int width = component.width > 0 ? component.width : 5;
            int depth = component.depth > 0 ? component.depth : 5;
            radius = Math.max(width, depth) / 2;
        }

        // 获取塔位：优先使用 component.offset，否则从 Skeleton 获取
        List<BlockPos> bases = new ArrayList<>();
        if (component.offsetX != 0 || component.offsetY != 0 || component.offsetZ != 0) {
            // 使用自定义偏移
            bases.add(ctx.origin.add(component.offsetX, component.offsetY, component.offsetZ));
        } else {
            // 从 Skeleton 获取塔位（如四角）
            bases = SkeletonHelper.getTowerBases(ctx, skeleton);
            if (bases.isEmpty()) {
                // 如果没有，使用 origin
                bases.add(ctx.origin);
            }
        }

        // 为每个塔位生成塔楼
        for (BlockPos base : bases) {
            int baseY = ctx.getSurfaceY(base.getX(), base.getZ());

            for (int dy = 0; dy < height; dy++) {
                if ("cylinder".equalsIgnoreCase(shape)) {
                    emitCylinderLayer(ops, base, baseY + dy, radius);
                } else {
                    // cuboid 或默认
                    emitCuboidLayer(ops, base, baseY + dy, radius);
                }
            }

            // 塔顶装饰
            if (height > 5) {
                emitTowerTop(ops, base, baseY + height, radius, shape);
            }
        }

        return ops;
    }

    /**
     * 生成圆柱形塔层
     */
    private void emitCylinderLayer(List<SemanticPlacementOp> ops, BlockPos center, int y, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int distSq = dx * dx + dz * dz;
                // 只生成外圈（空心）
                if (distSq <= r * r && distSq > (r - 1) * (r - 1)) {
                    ops.add(SemanticPlacementOp.of(
                            new BlockPos(center.getX() + dx, y, center.getZ() + dz),
                            SemanticPart.TOWER_WALL
                    ));
                }
            }
        }
    }

    /**
     * 生成立方体塔层
     */
    private void emitCuboidLayer(List<SemanticPlacementOp> ops, BlockPos center, int y, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                // 只生成外圈（空心）
                boolean isWall = (dx == -r || dx == r || dz == -r || dz == r);
                if (isWall) {
                    ops.add(SemanticPlacementOp.of(
                            new BlockPos(center.getX() + dx, y, center.getZ() + dz),
                            SemanticPart.TOWER_WALL
                    ));
                }
            }
        }
    }

    /**
     * 生成塔顶
     */
    private void emitTowerTop(List<SemanticPlacementOp> ops, BlockPos center, int y, int r, String shape) {
        if ("cylinder".equalsIgnoreCase(shape)) {
            // 圆形塔顶
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz <= r * r) {
                        ops.add(SemanticPlacementOp.of(
                                new BlockPos(center.getX() + dx, y, center.getZ() + dz),
                                SemanticPart.ROOF
                        ));
                    }
                }
            }
        } else {
            // 方形塔顶
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    ops.add(SemanticPlacementOp.of(
                            new BlockPos(center.getX() + dx, y, center.getZ() + dz),
                            SemanticPart.ROOF
                    ));
                }
            }
        }
    }


    private static String getStringParam(ComponentSpec component, String key, String defaultValue) {
        Object v = component.params.get(key);
        if (v instanceof String s) return s;
        if (v != null) return String.valueOf(v);
        return defaultValue;
    }
}

