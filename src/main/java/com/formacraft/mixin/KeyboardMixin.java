package com.formacraft.mixin;

import com.formacraft.client.ui.FormacraftUIState;
import com.formacraft.client.ui.input.InputRouter;
import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.input.CharInput;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 键盘输入 Mixin（HUD 模式）
 * 拦截键盘事件，转发给 InputRouter
 * 只在 HUD 模式下（无 Screen）处理
 */
@Mixin(Keyboard.class)
public class KeyboardMixin {
    @Shadow @Final private MinecraftClient client;

    /**
     * 拦截键盘按键事件
     * Minecraft 1.21.10 中的新签名：
     * @param window 窗口句柄
     * @param key 按键代码
     * @param keyInput KeyInput 对象，包含按键信息
     */
    @Inject(method = "onKey", at = @At("HEAD"), cancellable = true)
    private void onKey(long window, int key, KeyInput keyInput, CallbackInfo ci) {
        // 如果 UI 未打开，不处理
        if (!FormacraftUIState.isOpen || client.currentScreen != null) {
            return;
        }
        
        try {
            // 尝试多种方式访问 KeyInput 的字段
            int action = -1;
            int scanCode = -1;
            int modifiers = 0;
            
            // 尝试获取 action
            try {
                java.lang.reflect.Method actionMethod = KeyInput.class.getMethod("action");
                action = (Integer) actionMethod.invoke(keyInput);
            } catch (Exception e1) {
                String[] possibleActionFields = {"action", "f_action", "action_", "action$"};
                for (String fieldName : possibleActionFields) {
                    try {
                        java.lang.reflect.Field field = KeyInput.class.getDeclaredField(fieldName);
                        field.setAccessible(true);
                        action = field.getInt(keyInput);
                        break;
                    } catch (Exception ignored) {}
                }
            }
            
            // 获取鼠标位置，判断是否在面板内（无论 action 是什么，都要检查）
            double mouseX = client.mouse.getX() * client.getWindow().getScaledWidth() / client.getWindow().getWidth();
            double mouseY = client.mouse.getY() * client.getWindow().getScaledHeight() / client.getWindow().getHeight();
            
            boolean inside = InputRouter.isMouseInsidePanel(mouseX, mouseY);
            
            if (inside) {
                // 鼠标在面板内，完全拦截所有键盘输入（包括按下、释放、重复）
                // 只处理按下事件（action == 1）用于面板输入
                if (action == 1 || action < 0) {
                    // 尝试获取 scanCode
                    try {
                        java.lang.reflect.Method scanCodeMethod = KeyInput.class.getMethod("scanCode");
                        scanCode = (Integer) scanCodeMethod.invoke(keyInput);
                    } catch (Exception e1) {
                        String[] possibleScanCodeFields = {"scanCode", "f_scanCode", "scanCode_", "scanCode$", "scancode", "scancode_"};
                        for (String fieldName : possibleScanCodeFields) {
                            try {
                                java.lang.reflect.Field field = KeyInput.class.getDeclaredField(fieldName);
                                field.setAccessible(true);
                                scanCode = field.getInt(keyInput);
                                break;
                            } catch (Exception ignored) {}
                        }
                    }
                    
                    // 尝试获取 modifiers
                    try {
                        java.lang.reflect.Method modifiersMethod = KeyInput.class.getMethod("modifiers");
                        modifiers = (Integer) modifiersMethod.invoke(keyInput);
                    } catch (Exception e1) {
                        String[] possibleModifiersFields = {"modifiers", "f_modifiers", "modifiers_", "modifiers$"};
                        for (String fieldName : possibleModifiersFields) {
                            try {
                                java.lang.reflect.Field field = KeyInput.class.getDeclaredField(fieldName);
                                field.setAccessible(true);
                                modifiers = field.getInt(keyInput);
                                break;
                            } catch (Exception ignored) {}
                        }
                    }
                    
                    // 如果是按下事件，交给面板处理
                    if (action == 1 || action < 0) {
                        if (scanCode >= 0) {
                            InputRouter.keyPressed(key, scanCode, modifiers);
                        } else {
                            InputRouter.keyPressed(key, 0, modifiers);
                        }
                    }
                }
                // 无论什么 action，都阻止原版处理
                ci.cancel();
            }
            // 如果鼠标在面板外，不拦截，允许正常游戏操作
        } catch (Exception e) {
            // 如果所有方法都失败，静默失败（不影响游戏）
        }
    }

    /**
     * 拦截字符输入事件
     * Minecraft 1.21.10 中的新签名：
     * @param window 窗口句柄
     * @param charInput CharInput 对象，包含字符信息
     */
    @Inject(method = "onChar", at = @At("HEAD"), cancellable = true)
    private void onChar(long window, CharInput charInput, CallbackInfo ci) {
        // 如果 UI 未打开，不处理
        if (!FormacraftUIState.isOpen || client.currentScreen != null) {
            return;
        }
        
        try {
            // 尝试多种方式访问 CharInput 的字段
            int codePoint = -1;
            int modifiers = 0;
            
            // 尝试获取 codePoint
            try {
                java.lang.reflect.Method codePointMethod = CharInput.class.getMethod("codePoint");
                codePoint = (Integer) codePointMethod.invoke(charInput);
            } catch (Exception e1) {
                String[] possibleCodePointFields = {"codePoint", "f_codePoint", "codePoint_", "codePoint$", "codepoint", "codepoint_"};
                for (String fieldName : possibleCodePointFields) {
                    try {
                        java.lang.reflect.Field field = CharInput.class.getDeclaredField(fieldName);
                        field.setAccessible(true);
                        codePoint = field.getInt(charInput);
                        break;
                    } catch (Exception ignored) {}
                }
            }
            
            // 尝试获取 modifiers
            try {
                java.lang.reflect.Method modifiersMethod = CharInput.class.getMethod("modifiers");
                modifiers = (Integer) modifiersMethod.invoke(charInput);
            } catch (Exception e1) {
                String[] possibleModifiersFields = {"modifiers", "f_modifiers", "modifiers_", "modifiers$"};
                for (String fieldName : possibleModifiersFields) {
                    try {
                        java.lang.reflect.Field field = CharInput.class.getDeclaredField(fieldName);
                        field.setAccessible(true);
                        modifiers = field.getInt(charInput);
                        break;
                    } catch (Exception ignored) {}
                }
            }
            
            if (codePoint >= 0) {
                // 将 codePoint 转换为字符
                char chr = (char) codePoint;
                
                // 获取鼠标位置，判断是否在面板内
                double mouseX = client.mouse.getX() * client.getWindow().getScaledWidth() / client.getWindow().getWidth();
                double mouseY = client.mouse.getY() * client.getWindow().getScaledHeight() / client.getWindow().getHeight();
                
                boolean inside = InputRouter.isMouseInsidePanel(mouseX, mouseY);
                
                if (inside) {
                    // 鼠标在面板内，完全拦截
                    InputRouter.charTyped(chr, modifiers);
                    ci.cancel(); // 阻止原版处理
                }
                // 如果鼠标在面板外，不拦截，允许正常游戏操作
            }
        } catch (Exception e) {
            // 如果所有方法都失败，静默失败（不影响游戏）
        }
    }
}
