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

        // 获取 action（按下=1, 释放=0, 重复=2）
        int action = -1;
        try {
            // 尝试使用方法获取
            try {
                java.lang.reflect.Method actionMethod = KeyInput.class.getMethod("action");
                action = (Integer) actionMethod.invoke(keyInput);
            } catch (Exception e) {
                // 尝试用字段获取
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
        } catch (Exception e) {
            // 如果无法获取action，默认拦截（安全起见）
        }

        // 检查鼠标是否在 UI 内
        boolean inside = InputRouter.isMouseInsideUI();
        
        if (inside) {
            // 鼠标在 UI 内
            if (action == 1) {
                // 按下事件：拦截，防止控制玩家移动
                InputRouter.onKeyPressed(key, keyInput.scancode(), keyInput.modifiers());
                ci.cancel();
            } else if (action == 0) {
                // 释放事件：关键！即使鼠标在UI内，也要让游戏处理释放事件
                // 否则KeyBinding会认为按键还在按下状态，导致玩家一直移动
                // 不cancel，让游戏正常处理释放事件，确保KeyBinding状态被正确更新
            } else if (action == 2) {
                // 重复事件：拦截
                ci.cancel();
            } else if (action == -1) {
                // 无法获取action：为了安全，拦截
                ci.cancel();
            }
        } else {
            // 鼠标在 UI 外：允许游戏处理，不拦截
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
