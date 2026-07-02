package com.formacraft.server.skeleton.gen;

import com.formacraft.common.skeleton.ExecutableSkeletonPlan;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.util.FacingUtil;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3i;

import java.util.ArrayList;
import java.util.List;

/**
 * LINEAR_PATH 生成器（完整版）
 * 
 * 支持：
 * - 朝向（facing）
 * - 宽度（横向扩展）
 * - 顺地形/贴地生成（terrain conform）
 * - 高度策略（FLAT/FOLLOW_TERRAIN/STEP_UP/SLOPE）
 * 
 * 用途：
 * - 道路
 * - 城墙
 * - 桥
 * - 中轴建筑
 * - 建筑群主轴
 */
public class LinearPathGenerator implements ISkeletonGenerator {

    @Override
    public List<BlockPatch> generate(GenerationContext ctx, ExecutableSkeletonPlan plan) {
        // 应用参数（从 params 读取到字段）
        plan.applyParams();
        
        // 获取参数
        String block = plan.get("block", "minecraft:white_concrete");
        Direction facing = plan.facing;
        int width = Math.max(1, plan.width);
        int length = Math.max(1, plan.length);
        boolean conformTerrain = plan.conformTerrain;
        ExecutableSkeletonPlan.HeightPolicy heightPolicy = plan.heightPolicy;
        
        // 方向向量
        Vec3i forward = FacingUtil.forward(facing);
        Vec3i right = FacingUtil.right(facing);
        
        BlockPos origin = ctx.origin;
        List<BlockPatch> patches = new ArrayList<>();
        
        int halfWidth = Math.max(0, width / 2);
        int lastY = origin.getY();
        
        // 沿前进方向生成
        for (int i = 0; i < length; i++) {
            // 计算基础位置（沿前进方向）
            int baseX = origin.getX() + forward.getX() * i;
            int baseZ = origin.getZ() + forward.getZ() * i;
            
            // 计算高度
            int y;
            if (conformTerrain || heightPolicy == ExecutableSkeletonPlan.HeightPolicy.FOLLOW_TERRAIN) {
                // 顺地形：查询地表高度
                y = ctx.getSurfaceY(baseX, baseZ);
                lastY = y;
            } else if (heightPolicy == ExecutableSkeletonPlan.HeightPolicy.STEP_UP) {
                // 台阶：每 4 格上升 1 格
                y = (i == 0) ? lastY : lastY + (i % 4 == 0 ? 1 : 0);
                lastY = clampY(y, ctx);
            } else if (heightPolicy == ExecutableSkeletonPlan.HeightPolicy.SLOPE) {
                // 坡道：线性上升
                double slope = (double) plan.height / Math.max(1, length - 1);
                y = origin.getY() + (int) Math.round(slope * i);
                lastY = clampY(y, ctx);
            } else {
                // FLAT：保持原始高度
                y = origin.getY();
            }
            
            // 横向扩展（宽度）
            for (int w = -halfWidth; w <= halfWidth; w++) {
                int wx = baseX + right.getX() * w;
                int wz = baseZ + right.getZ() * w;
                
                BlockPos pos = new BlockPos(wx, y, wz);
                BlockPos relative = pos.subtract(ctx.origin);
                
                patches.add(new BlockPatch(BlockPatch.PLACE, 
                    relative.getX(), relative.getY(), relative.getZ(), block));
                
                if (patches.size() >= ctx.maxOps) return patches;
            }
        }
        
        return patches;
    }
    
    /**
     * 限制 Y 坐标在世界范围内
     */
    private static int clampY(int y, GenerationContext ctx) {
        int bottom = ctx.world.getBottomY();
        // getHeight() 返回世界高度（exclusive），所以减 1 得到最大有效 Y
        int top = ctx.world.getHeight() - 1;
        return Math.max(bottom, Math.min(top, y));
    }
}

