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

/** World overlays render once after the opaque outline pass, even when the cursor hits air. */
@Mixin(WorldRenderer.class)
public class SelectionBoxRenderMixin {

    /**
     * 在世界矩阵仍有效的阶段绘制，并独立提交线框缓冲
     */
    @Inject(
            method = "renderTargetBlockOutline(Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;Lnet/minecraft/client/util/math/MatrixStack;ZLnet/minecraft/client/render/state/WorldRenderState;)V",
            // Run after vanilla has flushed its outline buffer, including when no target exists.
            at = @At("TAIL"),
            require = 0
    )
    private void formacraft$renderOverlays(VertexConsumerProvider.Immediate immediate,
                                           MatrixStack matrices,
                                           boolean renderBlockOutline,
                                           net.minecraft.client.render.state.WorldRenderState renderStates,
                                           CallbackInfo ci) {
        // Vanilla invokes this method for opaque and translucent passes; render once.
        if (!renderBlockOutline && FormacraftUIState.isOpen) {
            renderFormacraftOverlays(immediate, matrices);
            immediate.draw(RenderLayer.getLines());
        }
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
