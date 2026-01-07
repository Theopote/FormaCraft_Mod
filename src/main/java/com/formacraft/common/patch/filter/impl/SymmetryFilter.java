package com.formacraft.common.patch.filter.impl;

import com.formacraft.client.tool.SelectionTool;
import com.formacraft.client.tool.SymmetryMode;
import com.formacraft.client.tool.SymmetryTool;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.filter.PatchFilter;
import com.formacraft.common.patch.filter.PatchFilterContext;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SymmetryFilter（对称 / 镜像）
 * 
 * 核心功能：为每个 BlockPatch 生成镜像版本
 * 
 * 效果：
 * 🪞 AI 只画一半
 * 系统自动补另一半
 */
public class SymmetryFilter implements PatchFilter {

    @Override
    public List<BlockPatch> filter(
            List<BlockPatch> input,
            BlockPos origin,
            PatchFilterContext context
    ) {
        if (!context.hasSymmetry()) {
            return input;
        }

        SymmetryTool sym = context.symmetry;
        SymmetryMode mode = sym.getMode();

        if (mode == null || mode == SymmetryMode.NONE) {
            return input;
        }

        List<BlockPatch> out = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>(); // 避免重复

        for (BlockPatch p : input) {
            BlockPos world = origin.add(p.dx(), p.dy(), p.dz());
            
            // 添加原始 patch
            if (seen.add(world)) {
                out.add(p);
            }

            // 生成镜像 patch
            BlockPos mirrored = mirror(world, mode, sym, origin);
            if (mirrored != null && !mirrored.equals(world) && seen.add(mirrored)) {
                out.add(new BlockPatch(
                        p.action(),
                        mirrored.getX() - origin.getX(),
                        mirrored.getY() - origin.getY(),
                        mirrored.getZ() - origin.getZ(),
                        p.targetBlock()
                ));
            }
        }

        return out;
    }

    /**
     * 计算镜像位置
     */
    private BlockPos mirror(BlockPos pos, SymmetryMode mode, SymmetryTool tool, BlockPos origin) {
        if (pos == null) {
            return null;
        }

        // CUSTOM_AXIS：使用工具定义的轴线
        if (mode == SymmetryMode.CUSTOM_AXIS && tool.hasAxis()) {
            BlockPos axisA = tool.getAxisA();
            BlockPos axisB = tool.getAxisB();
            if (axisA != null && axisB != null) {
                // 简化处理：使用两点定义的轴线（XZ 平面）
                // 这里使用简化的垂直平分线镜像
                // 实际实现可能需要更复杂的几何计算
                return mirrorCustomAxis(pos, axisA, axisB);
            }
        }

        // 预设模式：使用选区中心作为对称轴
        int centerX = 0;
        int centerZ = 0;
        
        if (SelectionTool.INSTANCE.hasSelection()) {
            BlockPos min = SelectionTool.INSTANCE.getMin();
            BlockPos max = SelectionTool.INSTANCE.getMax();
            if (min != null && max != null) {
                centerX = (min.getX() + max.getX() + 1) / 2;
                centerZ = (min.getZ() + max.getZ() + 1) / 2;
            }
        } else {
            // 使用 origin 作为对称中心
            centerX = origin.getX();
            centerZ = origin.getZ();
        }

        BlockPos result = pos;

        // MIRROR_X：关于 x = centerX 镜像
        if (mode == SymmetryMode.MIRROR_X || mode == SymmetryMode.BOTH) {
            result = new BlockPos(
                    centerX * 2 - result.getX(),
                    result.getY(),
                    result.getZ()
            );
        }

        // MIRROR_Z：关于 z = centerZ 镜像
        if (mode == SymmetryMode.MIRROR_Z || mode == SymmetryMode.BOTH) {
            result = new BlockPos(
                    result.getX(),
                    result.getY(),
                    centerZ * 2 - result.getZ()
            );
        }

        return result;
    }

    /**
     * 自定义轴线镜像（简化实现）
     */
    private BlockPos mirrorCustomAxis(BlockPos pos, BlockPos axisA, BlockPos axisB) {
        // 简化实现：使用两点定义的轴线进行镜像
        // 这里使用垂直平分线的概念
        // 实际实现可能需要更复杂的几何计算
        
        // 计算轴线的方向向量（XZ 平面）
        int dx = axisB.getX() - axisA.getX();
        int dz = axisB.getZ() - axisA.getZ();
        
        // 如果轴线是水平的（Z 方向），则关于 X 镜像
        if (Math.abs(dx) < Math.abs(dz)) {
            int centerX = (axisA.getX() + axisB.getX()) / 2;
            return new BlockPos(
                    centerX * 2 - pos.getX(),
                    pos.getY(),
                    pos.getZ()
            );
        } else {
            // 否则关于 Z 镜像
            int centerZ = (axisA.getZ() + axisB.getZ()) / 2;
            return new BlockPos(
                    pos.getX(),
                    pos.getY(),
                    centerZ * 2 - pos.getZ()
            );
        }
    }
}

