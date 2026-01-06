package com.formacraft.server.skeleton.gen;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.FormacraftMod;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * Skeleton 预览辅助类
 * 
 * 提供便捷方法，将 Skeleton 生成并发送到客户端预览
 */
public final class SkeletonPreviewHelper {
    private SkeletonPreviewHelper() {}

    /**
     * 生成 Skeleton 并发送到客户端预览
     * 
     * @param player 玩家
     * @param origin 原点位置
     * @param plan 可执行的骨架计划
     */
    public static void previewSkeleton(ServerPlayerEntity player, BlockPos origin, ExecutableSkeletonPlan plan) {
        if (player == null || origin == null || plan == null) {
            return;
        }

        if (!(player.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }

        // 生成 BlockPatch
        SkeletonBuildService service = new SkeletonBuildService();
        List<BlockPatch> patches = service.build(world, origin, plan);

        if (patches.isEmpty()) {
            return;
        }

        // 发送到客户端预览
        // 注意：这里需要检查是否有 PatchPreviewPayload，如果没有，可以使用现有的方式
        // 或者直接调用 BuildConfirmPanel.showPatchPreview（但这需要在客户端）
        // 暂时先记录日志，实际集成时需要根据网络协议调整
        FormacraftMod.LOGGER.info("Generated {} patches for skeleton preview", patches.size());
        
        // TODO: 发送 PatchPreviewPayload 到客户端
        // FormaCraftNetworking.sendPatchPreview(player, origin, patches);
    }

    /**
     * 生成 Skeleton 并返回 BlockPatch 列表（用于其他用途）
     * 
     * @param world 服务器世界
     * @param origin 原点位置
     * @param plan 可执行的骨架计划
     * @return BlockPatch 列表
     */
    public static List<BlockPatch> generatePatches(ServerWorld world, BlockPos origin, ExecutableSkeletonPlan plan) {
        if (world == null || origin == null || plan == null) {
            return List.of();
        }

        SkeletonBuildService service = new SkeletonBuildService();
        return service.build(world, origin, plan);
    }
}

