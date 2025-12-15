package com.formacraft.mixin;

import com.formacraft.client.ui.FormacraftUIState;
import com.formacraft.client.ui.input.InputRouter;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * KeyBinding Mixin
 * <p>
 * 拦截 KeyBinding 的更新，当 UI 打开且鼠标在 UI 内时，阻止 KeyBinding 状态更新，
 * 防止残留的按键状态导致玩家继续移动（惯性问题）。
 */
@Mixin(KeyBinding.class)
public class KeyBindingMixin {

    /**
     * 拦截 updatePressedStates 方法
     * <p>
     * 当 UI 打开且鼠标在 UI 内时，阻止 KeyBinding 状态更新，
     * 防止 WASD 等按键的残留状态导致玩家继续移动。
     */
    @Inject(method = "updatePressedStates", at = @At("HEAD"), cancellable = true)
    private static void onUpdatePressedStates(CallbackInfo ci) {
        // 如果 UI 未打开，不处理，让游戏正常更新 KeyBinding
        if (!FormacraftUIState.isOpen) {
            return;
        }

        // 检查鼠标是否在 UI 内
        boolean inside = InputRouter.isMouseInsideUI();

        if (inside) {
            // 鼠标在 UI 内：阻止 KeyBinding 状态更新，防止残留按键导致玩家移动
            ci.cancel();
        }
        // 如果鼠标在 UI 外，不拦截，允许正常更新 KeyBinding
    }
}
