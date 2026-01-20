package com.formacraft.mixin;

import com.formacraft.client.tool.ToolManager;
import com.formacraft.client.tool.ToolWorldRenderContext;
import com.formacraft.client.tool.PathTool;
import com.formacraft.client.tool.BrushTool;
import com.formacraft.client.ui.FormacraftUIState;
import com.formacraft.client.preview.BuildingOutlineRenderer;
import com.formacraft.client.preview.ComponentPreviewRenderer;
import com.formacraft.client.preview.ComponentSocketPreviewRenderer;
import com.formacraft.client.preview.PatchPreviewRenderer;
import com.formacraft.client.preview.SkeletonPreviewRenderer;
import com.formacraft.client.interaction.AnchorRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在原版绘制"目标方块轮廓"的同一渲染阶段，额外绘制 SelectionTool 的区域框。
 * <p>
 * 说明：我们不依赖 WorldRenderEvents（当前环境不可用），而复用现有管线里
 * 已经准备好的 MatrixStack / VertexConsumer。
 * <p>
 * 注意：renderTargetBlockOutline 只在有目标方块时调用，仰视天空时会消失。
 * 因此添加了额外的注入点确保选区框始终可见。
 */
@Mixin(WorldRenderer.class)
public class SelectionBoxRenderMixin {

    /**
     * 主要的渲染注入点：在 renderTargetBlockOutline 中渲染（有目标方块时）
     */
    @Inject(
            method = "renderTargetBlockOutline(Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;Lnet/minecraft/client/util/math/MatrixStack;ZLnet/minecraft/client/render/state/WorldRenderState;)V",
            // IMPORTANT:
            // 不能用 HEAD：太早时原版还没 begin 线框的 BufferBuilder，首次写顶点会 "Not building!" 崩溃。
            // 也不能用 TAIL：太晚时原版可能已经 flush 了 immediate。
            // 因此我们选择在原版首次 getBuffer(...) 之后立刻绘制，保证 BufferBuilder 处于 building 状态。
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;getBuffer(Lnet/minecraft/client/render/RenderLayer;)Lnet/minecraft/client/render/VertexConsumer;",
                    shift = At.Shift.AFTER
            ),
            require = 0
    )
    private void formacraft$renderOverlays(VertexConsumerProvider.Immediate immediate,
                                           MatrixStack matrices,
                                           boolean renderBlockOutline,
                                           net.minecraft.client.render.state.WorldRenderState renderStates,
                                           CallbackInfo ci) {
        renderFormacraftOverlays(immediate, matrices);
    }


    /**
     * 统一的渲染逻辑
     */
    @Unique
    private void renderFormacraftOverlays(VertexConsumerProvider.Immediate immediate, MatrixStack matrices) {
        if (!FormacraftUIState.isOpen) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.gameRenderer == null) return;
        Vec3d cam = client.gameRenderer.getCamera().getPos();

        // 关键：在原版绘制目标方块轮廓的同一 Immediate 上，不要在此阶段渲染任何"文字/其他 RenderLayer"，
        // 否则会触发 Immediate 切换 layer 并可能提前结束 lines 的 BufferBuilder，导致原版后续 "Not building!" 崩溃。
        // 因此这里把 ctx.immediate 置空，仅允许线框（lines layer）渲染。
        VertexConsumer lines = immediate.getBuffer(RenderLayer.getLines());
        ToolWorldRenderContext ctx = new ToolWorldRenderContext(matrices, lines, null, cam.x, cam.y, cam.z);

        // Tools：选区框/刷子预览等
        ToolManager.renderWorld(ctx);

        // Path：路径参考线（即便切换到其他工具也保持可见）
        PathTool.renderGlobal(ctx);

        // Brush：笔刷选中范围（即便切换到其他工具也保持可见）
        BrushTool.renderGlobal(ctx);
        
        // ComponentCapturePanel：构件拾取面板的选区预览
        if (com.formacraft.client.ui.FormaCraftHudOverlay.activePanel == com.formacraft.client.ui.panel.PanelType.COMPONENT_CAPTURE) {
            if (com.formacraft.client.ui.FormaCraftHudOverlay.COMPONENT_CAPTURE_PANEL != null) {
                com.formacraft.client.ui.FormaCraftHudOverlay.COMPONENT_CAPTURE_PANEL.renderWorldSelection(ctx);
            }
        }

        // Anchor：锚点可视化（不依赖 activeTool）
        AnchorRenderer.render(ctx);

        // BuildConfirm：真实占用方块预览（来自 OutlinePreviewState）
        BuildingOutlineRenderer.render(ctx);

        // J-layer：骨架预览（更轻量、更像规划图）
        SkeletonPreviewRenderer.render(ctx);

        // Patch：增量修改预览（place/remove/replace 不同颜色）
        PatchPreviewRenderer.render(ctx);

        // Component：构件预览（纯客户端，不放置方块）
        ComponentPreviewRenderer.render(ctx);

        // Component Socket：socket 开洞预览（纯客户端）
        ComponentSocketPreviewRenderer.render(ctx);
    }
}
