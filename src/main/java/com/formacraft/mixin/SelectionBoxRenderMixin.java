package com.formacraft.mixin;

import com.formacraft.client.tool.ToolManager;
import com.formacraft.client.tool.ToolWorldRenderContext;
import com.formacraft.client.ui.FormacraftUIState;
import com.formacraft.client.preview.BuildingOutlineRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在原版绘制“目标方块轮廓”的同一渲染阶段，额外绘制 SelectionTool 的区域框。
 * <p>
 * 说明：我们不依赖 WorldRenderEvents（当前环境不可用），而复用现有管线里
 * 已经准备好的 MatrixStack / VertexConsumer。
 */
@Mixin(WorldRenderer.class)
public class SelectionBoxRenderMixin {

    @Inject(
            method = "renderTargetBlockOutline(Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;Lnet/minecraft/client/util/math/MatrixStack;ZLnet/minecraft/client/render/state/WorldRenderState;)V",
            at = @At("TAIL"),
            require = 0
    )
    private static void formacraft$renderOverlays(VertexConsumerProvider.Immediate immediate,
                                                  MatrixStack matrices,
                                                  boolean renderBlockOutline,
                                                  net.minecraft.client.render.state.WorldRenderState renderStates,
                                                  CallbackInfo ci) {
        if (!FormacraftUIState.isOpen) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.gameRenderer == null) return;
        Vec3d cam = client.gameRenderer.getCamera().getPos();

        VertexConsumer lines = immediate.getBuffer(RenderLayer.getLines());
        ToolWorldRenderContext ctx = new ToolWorldRenderContext(matrices, lines, cam.x, cam.y, cam.z);

        // Tools：选区框/刷子预览等
        ToolManager.renderWorld(ctx);

        // BuildConfirm：真实占用方块预览（来自 OutlinePreviewState）
        BuildingOutlineRenderer.render(ctx);
    }
}

