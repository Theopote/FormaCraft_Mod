package com.formacraft.mixin;

import com.formacraft.client.ui.input.InputRouter;
import com.formacraft.client.ui.FormacraftUIState;
import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Keyboard.class)
public class KeyboardMixin {

    @Shadow @Final private MinecraftClient client;

    @Inject(method = "onKey", at = @At("HEAD"), cancellable = true)
    private void onKey(long window, int key, KeyInput keyInput, CallbackInfo ci) {
        // 重要：UI 关闭时，完全不处理，让游戏使用默认行为
        if (!FormacraftUIState.isOpen) {
            return;
        }

        // 检查鼠标是否在 UI 内
        boolean inside = InputRouter.isMouseInsideUI();
        
        if (inside) {
            // 鼠标在 UI 内：拦截所有键盘输入，防止控制玩家移动
            // 先尝试让 UI 处理，但不管是否被消费，都要拦截游戏处理
            InputRouter.onKeyPressed(key, keyInput.scancode(), keyInput.modifiers());
            // 完全拦截，防止 KeyBinding 更新和玩家移动
            ci.cancel();
        } else {
            // 鼠标在 UI 外：允许游戏处理，不拦截
            // 不调用 InputRouter，让游戏正常处理
        }
    }

    @Inject(method = "onChar", at = @At("HEAD"), cancellable = true)
    private void onChar(long window, CharInput charInput, CallbackInfo ci) {
        // 重要：UI 关闭时，完全不处理，让游戏使用默认行为
        if (!FormacraftUIState.isOpen) {
            return;
        }

        // 检查鼠标是否在 UI 内
        boolean inside = InputRouter.isMouseInsideUI();
        
        if (inside) {
            // 鼠标在 UI 内：拦截所有字符输入
            InputRouter.onCharTyped((char) charInput.codepoint(), charInput.modifiers());
            // 完全拦截，防止游戏处理
            ci.cancel();
        } else {
            // 鼠标在 UI 外：允许游戏处理，不拦截
        }
    }
}
