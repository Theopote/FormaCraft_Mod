package com.formacraft.mixin;

import com.formacraft.client.ui.FormacraftUIState;
import com.formacraft.client.ui.input.InputRouter;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FormaCraft UI 打开时限制 KeyBinding：
 * <ul>
 *   <li>鼠标在面板内：全部按键视为未按下（避免 WASD 惯性、方便打字）</li>
 *   <li>鼠标在世界侧：仅保留移动（WASD/Shift/空格）与鼠标世界交互键；E 等失效</li>
 * </ul>
 */
@Mixin(KeyBinding.class)
public class KeyBindingMixin {

    @Shadow
    private boolean pressed;

    @Shadow
    private int timesPressed;

    /**
     * 面板内时仍阻止批量轮询更新，避免残留状态。
     */
    @Inject(method = "updatePressedStates", at = @At("HEAD"), cancellable = true)
    private static void formacraft$blockUpdateWhenPointerOnUi(CallbackInfo ci) {
        if (!FormacraftUIState.isOpen) return;
        if (InputRouter.isMouseInsideUI()) {
            ci.cancel();
        }
    }

    @Inject(method = "setPressed", at = @At("HEAD"), cancellable = true)
    private void formacraft$restrictSetPressed(boolean pressed, CallbackInfo ci) {
        if (!FormacraftUIState.isOpen) return;
        if (!isCurrentlyAllowed((KeyBinding) (Object) this)) {
            this.pressed = false;
            ci.cancel();
        }
    }

    @Inject(method = "isPressed", at = @At("HEAD"), cancellable = true)
    private void formacraft$restrictIsPressed(CallbackInfoReturnable<Boolean> cir) {
        if (!FormacraftUIState.isOpen) return;
        if (!isCurrentlyAllowed((KeyBinding) (Object) this)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "wasPressed", at = @At("HEAD"), cancellable = true)
    private void formacraft$restrictWasPressed(CallbackInfoReturnable<Boolean> cir) {
        if (!FormacraftUIState.isOpen) return;
        if (!isCurrentlyAllowed((KeyBinding) (Object) this)) {
            this.timesPressed = 0;
            cir.setReturnValue(false);
        }
    }

    private static boolean isCurrentlyAllowed(KeyBinding binding) {
        return FormacraftUIState.isAllowedKeyBindingWhileUiOpen(binding)
                && !InputRouter.isMouseInsideUI();
    }
}
