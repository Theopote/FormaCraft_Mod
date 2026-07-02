package com.formacraft.server.skeleton.gen;

import com.formacraft.common.skeleton.ExecutableSkeletonPlan;

import com.formacraft.common.semantic.SemanticPart;
import com.formacraft.common.semantic.SemanticPlacementOp;
import com.formacraft.common.semantic.SemanticRole;
import com.formacraft.common.util.FacingUtil;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
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
        // 应用参数（从 params 读取到字段）
        plan.applyParams();
        
        List<SemanticPlacementOp> ops = new ArrayList<>();

        Direction facing = plan.facing;
        int width = Math.max(1, plan.width);
        int length = Math.max(1, plan.length);
        boolean conformTerrain = plan.conformTerrain;

        Vec3i forward = FacingUtil.forward(facing);
        Vec3i right = FacingUtil.right(facing);

        BlockPos origin = ctx.origin;
        int half = Math.max(0, width / 2);

        for (int i = 0; i < length; i++) {
            int x = origin.getX() + forward.getX() * i;
            int z = origin.getZ() + forward.getZ() * i;
            int y = conformTerrain ? ctx.getSurfaceY(x, z) : origin.getY();

            for (int w = -half; w <= half; w++) {
                int wx = x + right.getX() * w;
                int wz = z + right.getZ() * w;

                BlockPos pos = new BlockPos(wx, y, wz);

                boolean isEdge = (w == -half || w == half);
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

