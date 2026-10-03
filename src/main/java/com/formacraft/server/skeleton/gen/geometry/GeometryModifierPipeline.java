package com.formacraft.server.skeleton.gen.geometry;

import com.formacraft.common.geometry.GeometryModifier;
import com.formacraft.common.geometry.tool.GeometryConstraintPipeline;
import com.formacraft.common.geometry.tool.symmetry.SymmetryProcessor;
import com.formacraft.common.semantic.SemanticPlacementOp;
import com.formacraft.common.style.SemanticStyleProfile;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;

/**
 * GeometryModifierPipeline（几何修饰管道）
 * 
 * 在生成阶段应用几何修饰器和工具约束
 * 
 * 完整流程：
 * 1. 基础 SemanticPlacementOp
 * 2. 应用 GeometryModifier（扩展为多个点）
 * 3. 应用 GeometryConstraint（裁剪不允许的点）
 * 4. 应用 SymmetryProcessor（生成镜像点，如果有）
 * 5. 输出扩展后的 SemanticPlacementOp 列表
 */
public final class GeometryModifierPipeline {

    private GeometryModifierPipeline() {}

    /**
     * 应用几何修饰器到语义放置操作列表（不应用约束）
     * 
     * @param baseOps 基础语义放置操作列表
     * @param style 风格配置（包含几何修饰器映射）
     * @return 扩展后的语义放置操作列表
     */
    public static List<SemanticPlacementOp> applyModifiers(
            List<SemanticPlacementOp> baseOps,
            SemanticStyleProfile style
    ) {
        return applyModifiers(baseOps, style, Integer.MAX_VALUE);
    }

    public static List<SemanticPlacementOp> applyModifiers(List<SemanticPlacementOp> baseOps,
            SemanticStyleProfile style, int maxOps) {
        return applyModifiersAndConstraints(baseOps, style, null, null, maxOps);
    }

    /**
     * 应用几何修饰器和工具约束到语义放置操作列表
     * 
     * @param baseOps 基础语义放置操作列表
     * @param style 风格配置（包含几何修饰器映射）
     * @param constraintPipeline 约束管道（可选）
     * @param symmetryProcessor 对称处理器（可选）
     * @return 扩展后的语义放置操作列表
     */
    public static List<SemanticPlacementOp> applyModifiersAndConstraints(
            List<SemanticPlacementOp> baseOps,
            SemanticStyleProfile style,
            GeometryConstraintPipeline constraintPipeline,
            SymmetryProcessor symmetryProcessor
    ) {
        return applyModifiersAndConstraints(baseOps, style, constraintPipeline, symmetryProcessor, Integer.MAX_VALUE);
    }

    public static List<SemanticPlacementOp> applyModifiersAndConstraints(
            List<SemanticPlacementOp> baseOps, SemanticStyleProfile style,
            GeometryConstraintPipeline constraintPipeline, SymmetryProcessor symmetryProcessor, int maxOps) {
        if (maxOps < 0) throw new IllegalArgumentException("Negative geometry operation budget");
        if (baseOps == null || baseOps.isEmpty()) {
            return List.of();
        }

        // 1. 应用几何修饰器
        List<SemanticPlacementOp> expanded = new ArrayList<>();
        
        if (style != null) {
            for (SemanticPlacementOp base : baseOps) {
                if (base == null) continue;

                // 获取该部位的几何修饰器
                GeometryModifier modifier = style.getGeometry(base.part());
                
                if (modifier != null) {
                    // 应用修饰器，扩展为多个点
                    List<SemanticPlacementOp> additions = modifier.apply(base);
                    if (additions == null) throw new IllegalArgumentException("Geometry modifier returned null");
                    checkBudget((long) expanded.size() + additions.size(), maxOps);
                    expanded.addAll(additions);
                } else {
                    // 没有修饰器，直接添加原始操作
                    checkBudget((long) expanded.size() + 1, maxOps);
                    expanded.add(base);
                }
            }
        } else {
            checkBudget(baseOps.size(), maxOps);
            expanded.addAll(baseOps);
        }

        // 2. 应用约束（裁剪不允许的点）
        Set<BlockPos> finalPositions = new LinkedHashSet<>();
        Map<BlockPos, SemanticPlacementOp> originals = new LinkedHashMap<>();
        
        for (SemanticPlacementOp op : expanded) {
            if (op == null || op.pos() == null) continue;
            
            BlockPos p = op.pos();
            
            // 如果有约束管道，检查是否允许
            if (constraintPipeline != null && !constraintPipeline.isEmpty()) {
                if (!constraintPipeline.allow(p)) {
                    continue; // 不允许，跳过
                }
            }
            
            finalPositions.add(p);
            originals.put(p, op);
        }

        // 3. 应用对称处理（如果有）
        if (symmetryProcessor != null) {
            var mirrored = symmetryProcessor.apply(finalPositions);
            checkBudget(mirrored.size(), maxOps);
            var ordered = new LinkedHashSet<>(finalPositions);
            mirrored.stream().filter(p -> !ordered.contains(p)).sorted(java.util.Comparator
                    .comparingInt(BlockPos::getX).thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ))
                    .forEach(ordered::add);
            finalPositions = ordered;
        }

        // 4. 转换回 SemanticPlacementOp 列表
        // 重叠位置使用最后一次操作的完整语义；新镜像点仍沿用旧默认语义。
        List<SemanticPlacementOp> result = new ArrayList<>();
        for (BlockPos pos : finalPositions) {
            // 尝试从原始操作中找到对应的语义信息
            SemanticPlacementOp original = originals.get(pos);
            if (original != null) {
                result.add(new SemanticPlacementOp(
                        pos,
                        original.facing(),
                        original.part(),
                        original.role(),
                        original.geometry(),
                        original.tags()
                ));
            } else {
                // 如果没有找到原始操作，创建一个默认的
                result.add(SemanticPlacementOp.of(pos, com.formacraft.common.semantic.SemanticPart.WALL));
            }
        }

        return result;
    }

    private static void checkBudget(long count, int maxOps) {
        if (count > maxOps) throw new IllegalArgumentException("Geometry expansion exceeds operation budget");
    }
}
