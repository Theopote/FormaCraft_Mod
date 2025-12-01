package com.formacraft.mixin;

import com.formacraft.client.ui.FormaCraftChatScreen;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Screen 相关的 Mixin
 * 用于处理 FormaCraft 窗口的鼠标检测
 */
@Mixin(Screen.class)
public class ScreenMixin {
    @Inject(method = "isMouseOver(DD)Z", at = @At("HEAD"), cancellable = true)
    private void onIsMouseOver(double mouseX, double mouseY, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof FormaCraftChatScreen screen) {
            // 只有当鼠标在 FormaCraft UI 区域外时才覆盖 isMouseOver
            if (!screen.isMouseOverPanel(mouseX, mouseY)) {
                cir.setReturnValue(false); // 鼠标在UI外时返回false
                cir.cancel(); // 取消原始isMouseOver逻辑
            }
        }
    }
}

