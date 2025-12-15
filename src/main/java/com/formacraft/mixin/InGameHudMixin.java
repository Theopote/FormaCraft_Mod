package com.formacraft.mixin;

import com.formacraft.client.ui.FormacraftUIState;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * InGameHud Mixin
 * <p>
 * 当 FormaCraft UI 打开时，隐藏默认的十字星（crosshair），
 * 避免与自定义光标产生视觉冲突和逻辑混乱。
 */
@Mixin(InGameHud.class)
public class InGameHudMixin {

    /**
     * 拦截十字星渲染
     * 当 UI 打开时，不渲染十字星
     */
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void formacraft$hideCrosshairWhenUIOpen(net.minecraft.client.gui.DrawContext context, net.minecraft.client.render.RenderTickCounter tickCounter, CallbackInfo ci) {
        // 如果 FormaCraft UI 打开，隐藏十字星
        // 这样可以避免十字星与自定义光标产生视觉冲突和逻辑混乱
        if (FormacraftUIState.isOpen) {
            ci.cancel();
        }
    }
}

