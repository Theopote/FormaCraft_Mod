package com.formacraft.mixin;

import com.formacraft.client.ui.FormacraftUIState;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FormaCraft UI 打开时隐藏第一人称手臂/手持物（类似 F1），
 * 不影响世界、方块高亮与幽灵边框渲染。
 */
@Mixin(HeldItemRenderer.class)
public class HeldItemRendererMixin {

    @Inject(
            method = "renderItem(FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/network/ClientPlayerEntity;I)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void formacraft$hideHandWhenUiOpen(float tickProgress, MatrixStack matrices,
                                               OrderedRenderCommandQueue queue,
                                               ClientPlayerEntity player, int light,
                                               CallbackInfo ci) {
        if (FormacraftUIState.isOpen) {
            ci.cancel();
        }
    }
}
