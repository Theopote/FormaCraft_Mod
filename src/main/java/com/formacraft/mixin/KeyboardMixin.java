package com.formacraft.mixin;

import com.formacraft.client.ui.input.InputRouter;
import com.formacraft.client.ui.FormacraftUIState;
import net.minecraft.client.Keyboard;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Keyboard.class)
public class KeyboardMixin {

    @Inject(method = "onKey", at = @At("HEAD"), cancellable = true)
    private void onKey(long window, int action, KeyInput keyInput, CallbackInfo ci) {
        // 重要：UI 关闭时，完全不处理，让游戏使用默认行为
        if (!FormacraftUIState.isOpen) {
            return;
        }

        // action：按下=1, 释放=0, 重复=2
        // 重要：这个方法的第二个参数在 1.21.10 的 Yarn 映射中就是 action，
        // KeyInput 里包含 key/scancode/modifiers。
        //
        // 关键修复：
        // - 输入框获得焦点后，鼠标移出也应能 Backspace/粘贴等（由 InputRouter 的 wantsKeyboardInput 决定）。
        // - 只转发“按下/重复”，不要把“释放”当作按下，否则 Backspace 会按一次删两次。
        // - 仅当 UI 消费了该按键时才 cancel（释放事件永远不 cancel，避免 KeyBinding 残留按下状态）。
        if (action == 0) return; // 释放：不转发、不 cancel

        boolean handled = InputRouter.onKeyPressed(keyInput.key(), keyInput.scancode(), keyInput.modifiers());
        if (handled) {
                ci.cancel();
        }
    }

    @Inject(method = "onChar", at = @At("HEAD"), cancellable = true)
    private void onChar(long window, CharInput charInput, CallbackInfo ci) {
        // 重要：UI 关闭时，完全不处理，让游戏使用默认行为
        if (!FormacraftUIState.isOpen) {
            return;
        }

        // 同 onKey：让 InputRouter 决定是否消费（inside UI 或输入框已获得焦点）。
        boolean handled = InputRouter.onCharTyped((char) charInput.codepoint(), charInput.modifiers());
        if (handled) {
            ci.cancel();
        }
    }
}
