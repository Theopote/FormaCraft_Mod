package com.formacraft.server.skeleton.gen;

import com.formacraft.common.skeleton.ExecutableSkeletonPlan;

import com.formacraft.common.semantic.SemanticPart;
import com.formacraft.common.semantic.SemanticPlacementOp;
import com.formacraft.common.semantic.SemanticRole;
import com.formacraft.common.util.FacingUtil;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * LINEAR_PATH 语义生成器
 * 
 * 输出 SemanticPlacementOp 而不是直接的 BlockPatch
 */
public class LinearPathSemanticGenerator implements ISkeletonSemanticGenerator {

    @Override
    public List<SemanticPlacementOp> generateSemantic(GenerationContext ctx, ExecutableSkeletonPlan plan) {
        var layout = LinearPathLayout.create(ctx, plan, true);
        List<SemanticPlacementOp> ops = new ArrayList<>();
        int width = layout.width();
        Vec3i right = FacingUtil.right(plan.facing);
        for (var row : layout.rows()) {
            for (int column = 0; column < width; column++) {
                int offset = layout.start() + column;
                BlockPos pos = row.center().add(right.getX() * offset, 0, right.getZ() * offset);
                boolean isEdge = column == 0 || column == width - 1;
                if (isEdge && width >= 3) {
                    // 边缘：使用 PATH_BASE + EDGE role，并在上方放置 PATH_EDGE
                    ops.add(SemanticPlacementOp.of(pos, SemanticPart.PATH_BASE, SemanticRole.EDGE, Set.of("edge")));
                    ops.add(SemanticPlacementOp.of(pos.up(), SemanticPart.PATH_EDGE, SemanticRole.TRIM, Set.of("edge_trim")));
                } else {
                    // 中间：使用 PATH_BASE + FILL role
                    ops.add(SemanticPlacementOp.of(pos, SemanticPart.PATH_BASE));
                }
            }
        }
        return ops;
    }
}

