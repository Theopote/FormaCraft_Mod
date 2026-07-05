package com.formacraft.mixin;

import com.formacraft.client.interaction.CursorRaycastHelper;
import com.formacraft.client.tool.ToolManager;
import com.formacraft.client.tool.ToolWorldRenderContext;
import com.formacraft.client.tool.PathTool;
import com.formacraft.client.tool.BrushTool;
import com.formacraft.client.ui.FormacraftUIState;
import com.formacraft.client.ui.input.InputRouter;
import com.formacraft.client.preview.BuildingOutlineRenderer;
import com.formacraft.client.preview.ComponentPreviewRenderer;
import com.formacraft.client.preview.ComponentSocketPreviewRenderer;
import com.formacraft.client.preview.PatchPreviewRenderer;
import com.formacraft.client.preview.SkeletonPreviewRenderer;
import com.formacraft.client.interaction.AnchorRenderer;
import com.formacraft.config.SettingsConfig;
import com.formacraft.common.logging.FcaLog;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 关键 Mixin：把“屏幕中心十字准星 RayCast”替换成“系统光标 RayCast”。
 * <p>
 * 原版会在 GameRenderer#updateCrosshairTarget 中更新 MinecraftClient.crosshairTarget。
 * 我们在 UI 打开时拦截它，改为用 CursorRaycastHelper 的结果写回。
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Unique
    private static final FcaLog LOG = FcaLog.of("GameRendererMixin");

    @Inject(method = "updateCrosshairTarget(F)V", at = @At("HEAD"), cancellable = true)
    private void formacraft$overrideCrosshairTarget(float tickDelta, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!FormacraftUIState.isOpen || client == null || client.currentScreen != null) {
            return;
        }

        // 更新 InputRouter 的鼠标位置（scaled）
        double scale = client.getWindow().getScaleFactor();
        InputRouter.updateMouse(client.mouse.getX() / scale, client.mouse.getY() / scale);

        // 鼠标在面板内：不更新世界目标（避免隔着 UI 选中方块）
        if (InputRouter.isMouseInsideUI()) {
            CursorRaycastHelper.clear();
            // 不要把 crosshairTarget 置空：否则原版的“目标方块轮廓渲染阶段”可能不会执行，
            // 而我们的选区框/预览正挂在同一阶段（SelectionBoxRenderMixin），会导致鼠标在面板上时预览消失。
            // 这里选择“冻结”上一帧的 crosshairTarget（不更新即可），达到：
            // - 面板上不再穿透选中方块（目标不更新）
            // - 选区框/预览仍能持续渲染
            ci.cancel();
            return;
        }

        // 鼠标在面板外：用光标 RayCast 更新目标
        double fov = 70.0;
        try {
            fov = ((GameRendererAccessor) this).formacraft$invokeGetFov(client.gameRenderer.getCamera(), tickDelta, true);
        } catch (Throwable ex) {
            LOG.debug("read camera fov failed, using default", ex);
        }
        client.crosshairTarget = CursorRaycastHelper.raycastFromCursor(tickDelta, getReachDistance(), fov);
        ci.cancel();
    }

    @Unique
    private double getReachDistance() {
        return SettingsConfig.effectiveInteractionReach();
    }

    /**
     * 每帧渲染注入点：确保选区框始终显示，即使鼠标不在方块上
     * 这解决了当 crosshairTarget 为空时 renderTargetBlockOutline 不执行的问题
     */
    @Inject(
            method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V",
            at = @At(value = "TAIL"),
            require = 0
    )
    private void formacraft$renderSelectionBoxes(net.minecraft.client.render.RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        if (!FormacraftUIState.isOpen) return;
        
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.gameRenderer == null || client.world == null) return;
        
        // 检查是否有选区需要渲染
        boolean hasSelection = com.formacraft.client.tool.SelectionTool.INSTANCE.hasSelection() || 
                              com.formacraft.client.tool.SelectionTool.INSTANCE.isSelecting() ||
                              com.formacraft.client.ui.FormaCraftHudOverlay.activePanel == com.formacraft.client.ui.panel.PanelType.COMPONENT_CAPTURE;
        
        if (!hasSelection) return;
        
        // 获取 Immediate 并创建 lines layer
        VertexConsumerProvider.Immediate immediate = client.getBufferBuilders().getEntityVertexConsumers();
        if (immediate == null) return;
        
        // 获取相机位置和矩阵
        Vec3d cam = client.gameRenderer.getCamera().getPos();
        MatrixStack matrices = new MatrixStack();
        matrices.push();
        
        try {
            VertexConsumer lines = immediate.getBuffer(RenderLayer.getLines());
            ToolWorldRenderContext ctx = new ToolWorldRenderContext(matrices, lines, null, cam.x, cam.y, cam.z);
            
            // 渲染选区框和其他预览
            ToolManager.renderWorld(ctx);
            PathTool.renderGlobal(ctx);
            BrushTool.renderGlobal(ctx);
            
            if (com.formacraft.client.ui.FormaCraftHudOverlay.activePanel == com.formacraft.client.ui.panel.PanelType.COMPONENT_CAPTURE) {
                if (com.formacraft.client.ui.FormaCraftHudOverlay.COMPONENT_CAPTURE_PANEL != null) {
                    com.formacraft.client.ui.FormaCraftHudOverlay.COMPONENT_CAPTURE_PANEL.renderWorldSelection(ctx);
                }
            }
            
            AnchorRenderer.render(ctx);
            BuildingOutlineRenderer.render(ctx);
            SkeletonPreviewRenderer.render(ctx);
            PatchPreviewRenderer.render(ctx);
            ComponentPreviewRenderer.render(ctx);
            ComponentSocketPreviewRenderer.render(ctx);
        } finally {
            matrices.pop();
        }
    }
}

