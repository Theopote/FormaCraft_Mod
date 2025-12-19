package com.formacraft.mixin;

import com.formacraft.client.tool.ToolManager;
import com.formacraft.client.tool.ToolWorldRenderContext;
import com.formacraft.client.ui.FormacraftUIState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.state.OutlineRenderState;
import net.minecraft.client.util.math.MatrixStack;
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
            method = "drawBlockOutline(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumer;DDDLnet/minecraft/client/render/state/OutlineRenderState;I)V",
            at = @At("TAIL"),
            require = 0
    )
    private static void formacraft$drawSelectionBox(MatrixStack matrices, VertexConsumer vertexConsumer,
                                                    double cameraX, double cameraY, double cameraZ,
                                                    OutlineRenderState state, int color, CallbackInfo ci) {
        if (!FormacraftUIState.isOpen) return;
        ToolManager.renderWorld(new ToolWorldRenderContext(matrices, vertexConsumer, cameraX, cameraY, cameraZ));
    }
}

