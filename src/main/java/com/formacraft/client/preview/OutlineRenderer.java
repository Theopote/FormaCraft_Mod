package com.formacraft.client.preview;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * 预览线框渲染器
 * 在客户端渲染建筑轮廓
 * <p>
 * 注意：此功能需要 Fabric API 0.136.0+ 支持 WorldRenderEvents
 * 当前版本的 Fabric API 可能不包含 WorldRenderContext 和 WorldRenderEvents
 * 请更新 Fabric API 到最新版本，或等待这些 API 可用
 */
@Environment(EnvType.CLIENT)
public class OutlineRenderer {
    /**
     * 注册渲染器
     * <p>
     * 暂时禁用，因为 WorldRenderContext 和 WorldRenderEvents 在当前 Fabric API 版本中不可用
     * 需要更新到支持这些 API 的 Fabric API 版本
     */
    public static void register() {
        // TODO: 当 Fabric API 支持 WorldRenderEvents 时，取消注释以下代码
        /*
        try {
            WorldRenderEvents.AFTER_ENTITIES.register((context) -> {
                if (!OutlinePreviewState.active || OutlinePreviewState.blocks.isEmpty()) {
                    return;
                }
                renderOutlines(context);
            });
        } catch (NoClassDefFoundError | Exception e) {
            System.err.println("[FormaCraft] WorldRenderEvents not available, outline preview disabled.");
        }
        */
        System.out.println("[FormaCraft] Outline preview is temporarily disabled. Please update Fabric API to a version that supports WorldRenderEvents.");
    }
}
