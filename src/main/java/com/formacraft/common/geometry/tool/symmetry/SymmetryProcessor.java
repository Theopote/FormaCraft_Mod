package com.formacraft.common.geometry.tool.symmetry;

import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.Set;

/**
 * SymmetryProcessor（对称处理器）
 * 
 * 这是"生成型约束"，不是简单过滤
 * 它会为每个输入点生成镜像点
 * 
 * 应用场景：
 * - 宫殿
 * - 城堡
 * - 中轴对称建筑
 * - 对称屋顶 / 桥梁
 */
public class SymmetryProcessor {

    private final SymmetryPlane plane;

    public SymmetryProcessor(SymmetryPlane plane) {
        this.plane = plane;
    }

    /**
     * 应用对称处理，为每个点生成镜像点
     * 
     * @param input 输入位置集合
     * @return 包含原始点和镜像点的集合
     */
    public Set<BlockPos> apply(Set<BlockPos> input) {
        if (input == null || input.isEmpty()) {
            return Set.of();
        }

        Set<BlockPos> out = new HashSet<>(input);
        
        for (BlockPos p : input) {
            BlockPos mirrored = plane.mirror(p);
            if (mirrored != null) {
                out.add(mirrored);
            }
        }
        
        return out;
    }

    /**
     * 应用对称处理到单个位置
     */
    public BlockPos mirror(BlockPos pos) {
        return plane.mirror(pos);
    }
}

