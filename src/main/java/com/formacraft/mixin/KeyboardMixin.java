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
    private void onKey(long window, int key, KeyInput keyInput, CallbackInfo ci) {
        // 重要：UI 关闭时，完全不处理，让游戏使用默认行为
        if (!FormacraftUIState.isOpen) {
            return;
        }

        // action：按下=1, 释放=0, 重复=2
        // 注意：不同 Yarn/MC 版本 KeyInput 的 API 可能不同，这里用反射兜底。
        int action = -1;
        try {
            try {
                java.lang.reflect.Method actionMethod = KeyInput.class.getMethod("action");
                action = (Integer) actionMethod.invoke(keyInput);
            } catch (Exception e) {
                String[] possibleFields = {"action", "f_action", "action_", "action$"};
                for (String fieldName : possibleFields) {
                    try {
                        java.lang.reflect.Field field = KeyInput.class.getDeclaredField(fieldName);
                        field.setAccessible(true);
                        action = field.getInt(keyInput);
                        break;
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}

        // 关键修复：
        // - 不要只在“鼠标在 UI 内”时才路由键盘事件；输入框获得焦点后，鼠标移出也应能 Backspace/粘贴等。
        // - 无论 action 是否正确识别，都应尝试转发；否则会出现“能输入字符(onChar)但 Backspace 无效(onKey)”。
        // - 仅当 UI 消费了该按键且不是释放事件(action!=0)时才 cancel，避免 KeyBinding 残留按下状态。
        boolean handled = InputRouter.onKeyPressed(key, keyInput.scancode(), keyInput.modifiers());
        if (handled && action != 0) {
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
