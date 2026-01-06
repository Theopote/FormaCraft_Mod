package com.formacraft.server.skeleton.gen;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.skeleton.gen.util.GenMath;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CONTOUR_FOLLOW 生成器
 * 生成等高线跟随路径（简化版：使用折线路径，后续可升级为地形采样）
 */
public class ContourFollowGenerator implements ISkeletonGenerator {
    @Override
    public List<BlockPatch> generate(GenerationContext ctx, ExecutableSkeletonPlan plan) {
        // v1: 复用 PATH_POLYLINE 逻辑，后续可升级为地形采样
        PathPolylineGenerator delegate = new PathPolylineGenerator();
        return delegate.generate(ctx, plan);
    }
}

