package com.formacraft.client.preview;

import com.formacraft.common.model.build.BuildingSpec;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import com.formacraft.client.preview.PreviewModalState;

/**
 * 建筑预览状态中心：
 * - BuildConfirmPanel.show(spec) 时激活
 * - cancel/confirm 时清理
 * <p>
 * 说明：
 * - “真实占用方块”优先来自服务端下发的 OutlinePreviewState.blocks
 * - 这里额外保存 origin，用于确认建造时与预览一致
 */
public final class BuildingPreviewState {
    private BuildingPreviewState() {}

    private static BuildingSpec previewSpec;
    private static boolean active = false;

    private static BlockPos origin;
    private static BlockPos pendingOrigin; // 客户端发起请求时先暂存

    public static void setPendingOrigin(BlockPos pos) {
        pendingOrigin = pos;
    }

    public static void show(BuildingSpec spec) {
        previewSpec = spec;
        active = (spec != null);

        if (pendingOrigin != null) {
            origin = pendingOrigin;
        } else {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.player != null) {
                origin = mc.player.getBlockPos();
            }
        }
        pendingOrigin = null;
    }

    public static void clear() {
        previewSpec = null;
        active = false;
        origin = null;
        pendingOrigin = null;
    }

    public static boolean isActive() {
        return active && previewSpec != null;
    }

    /** 预览模态锁：只要在预览中，就锁定所有非确认/取消输入。 */
    public static boolean isInputLocked() {
        // 兼容旧调用：现在以 PreviewModalState 为准（BuildConfirmPanel 会在 show/hide 时切换）
        return PreviewModalState.isLocked();
    }

    public static BuildingSpec getSpec() {
        return previewSpec;
    }

    public static BlockPos getOrigin() {
        return origin;
    }
}

