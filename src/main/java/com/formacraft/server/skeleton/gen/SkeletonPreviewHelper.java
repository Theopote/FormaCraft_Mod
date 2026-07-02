package com.formacraft.server.skeleton.gen;

import com.formacraft.common.skeleton.ExecutableSkeletonPlan;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.model.constraint.ProtectedZone;
import com.formacraft.server.state.PlayerOutlineStorage;
import com.formacraft.server.state.PlayerProtectedZoneStorage;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * Skeleton 预览辅助类
 * <p>
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

        // 签发 PreviewTicket 并下发客户端预览
        List<ProtectedZone> zones = PlayerProtectedZoneStorage.get(player);
        com.formacraft.server.patch.PatchPreviewService.issuePreview(
                player, origin, patches, zones, PlayerOutlineStorage.get(player), false, null, null);
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

