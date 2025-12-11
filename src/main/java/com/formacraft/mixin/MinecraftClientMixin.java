package com.formacraft.mixin;

import com.formacraft.client.ui.FormacraftUIState;
import com.formacraft.client.ui.input.InputRouter;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MinecraftClient Mixin
 * 拦截客户端 tick，在面板内时阻止玩家输入处理
 */
@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
    
    /**
     * 拦截 handleInput 方法，在面板内时阻止所有输入处理
     * 这个方法处理玩家输入（移动、攻击、使用物品等）
     */
    @Inject(method = "handleInputEvents", at = @At("HEAD"), cancellable = true)
    private void onHandleInputEvents(CallbackInfo ci) {
        MinecraftClient client = (MinecraftClient) (Object) this;
        
        // 如果 UI 未打开或有 Screen，不处理
        if (!FormacraftUIState.isOpen || client.currentScreen != null) {
            return;
        }
        
        // 获取鼠标位置
        double mouseX = client.mouse.getX() * client.getWindow().getScaledWidth() / client.getWindow().getWidth();
        double mouseY = client.mouse.getY() * client.getWindow().getScaledHeight() / client.getWindow().getHeight();
        
        // 检查鼠标是否在面板内
        boolean inside = InputRouter.isMouseInsidePanel(mouseX, mouseY);
        
        if (inside) {
            // 鼠标在面板内，完全阻止所有输入处理（包括移动、攻击、使用物品等）
            ci.cancel();
        }
    }
}

