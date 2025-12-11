package com.formacraft.mixin;

import com.formacraft.client.ui.FormacraftUIState;
import com.formacraft.client.ui.input.InputRouter;
import net.minecraft.client.Mouse;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.MouseInput;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 鼠标输入 Mixin（HUD 模式）
 * 拦截鼠标事件，转发给 InputRouter
 * 只在 HUD 模式下（无 Screen）处理
 */
@Mixin(Mouse.class)
public class MouseMixin {
    @Shadow @Final private MinecraftClient client;

    /**
     * 拦截鼠标点击事件
     * Minecraft 1.21.10 中的新签名：
     * @param window 窗口句柄
     * @param mouseInput MouseInput 对象，包含按钮和动作信息
     * @param mods 修饰符
     */
    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void onMouseClick(long window, MouseInput mouseInput, int mods, CallbackInfo ci) {
        // 如果在游戏中（没有 Screen）
        if (client.currentScreen == null) {
            try {
                // 尝试多种方式访问 MouseInput 的字段
                int action = -1;
                int button = -1;
                
                // 方法 1: 尝试访问器方法
                try {
                    java.lang.reflect.Method actionMethod = MouseInput.class.getMethod("action");
                    action = (Integer) actionMethod.invoke(mouseInput);
                } catch (Exception e1) {
                    // 方法 2: 尝试字段访问（可能的字段名）
                    String[] possibleActionFields = {"action", "f_action", "action_", "action$"};
                    for (String fieldName : possibleActionFields) {
                        try {
                            java.lang.reflect.Field field = MouseInput.class.getDeclaredField(fieldName);
                            field.setAccessible(true);
                            action = field.getInt(mouseInput);
                            break;
                        } catch (Exception ignored) {}
                    }
                }
                
                // 如果找到了 action 且是按下事件（action == 1）
                if (action == 1) {
                    // 尝试获取 button
                    try {
                        java.lang.reflect.Method buttonMethod = MouseInput.class.getMethod("button");
                        button = (Integer) buttonMethod.invoke(mouseInput);
                    } catch (Exception e1) {
                        // 尝试字段访问
                        String[] possibleButtonFields = {"button", "f_button", "button_", "button$"};
                        for (String fieldName : possibleButtonFields) {
                            try {
                                java.lang.reflect.Field field = MouseInput.class.getDeclaredField(fieldName);
                                field.setAccessible(true);
                                button = field.getInt(mouseInput);
                                break;
                            } catch (Exception ignored) {}
                        }
                    }
                    
                    if (button >= 0) {
                        // 计算屏幕坐标
                        double mouseX = client.mouse.getX() * client.getWindow().getScaledWidth() / client.getWindow().getWidth();
                        double mouseY = client.mouse.getY() * client.getWindow().getScaledHeight() / client.getWindow().getHeight();

                        boolean cancel = InputRouter.mouseClicked(mouseX, mouseY, button);
                        if (cancel) {
                            ci.cancel(); // 如果已处理，取消原版处理
                        }
                    }
                }
            } catch (Exception e) {
                // 如果所有方法都失败，静默失败（不影响游戏）
                // System.err.println("[FormaCraft] Failed to access MouseInput: " + e.getMessage());
            }
        }
    }

    /**
     * 拦截鼠标滚轮事件
     * @param window 窗口句柄
     * @param horizontal 水平滚动量
     * @param vertical 垂直滚动量
     */
    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void onMouseScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        // 如果在游戏中（没有 Screen）
        if (client.currentScreen == null) {
            // 计算屏幕坐标
            double mouseX = client.mouse.getX() * client.getWindow().getScaledWidth() / client.getWindow().getWidth();
            double mouseY = client.mouse.getY() * client.getWindow().getScaledHeight() / client.getWindow().getHeight();

            boolean cancel = InputRouter.mouseScrolled(mouseX, mouseY, vertical);
            if (cancel) {
                ci.cancel(); // 如果已处理，取消原版处理
            }
        }
    }
    
    /**
     * 拦截鼠标移动事件（用于实现中键视角移动）
     * @param window 窗口句柄
     * @param x 鼠标 X 坐标
     * @param y 鼠标 Y 坐标
     */
    @Inject(method = "onCursorPos", at = @At("HEAD"), cancellable = true)
    private void onCursorPos(long window, double x, double y, CallbackInfo ci) {
        // 如果 UI 未打开，不处理（允许正常视角移动）
        if (!FormacraftUIState.isOpen || client.currentScreen != null) {
            return;
        }
        
        // 计算屏幕坐标
        double mouseX = x * client.getWindow().getScaledWidth() / client.getWindow().getWidth();
        double mouseY = y * client.getWindow().getScaledHeight() / client.getWindow().getHeight();
        
        // 检查鼠标是否在面板内
        boolean inside = InputRouter.isMouseInsidePanel(mouseX, mouseY);
        
        // 如果鼠标在面板内，完全阻止视角移动
        if (inside) {
            ci.cancel();
            return;
        }
        
        // 如果鼠标在面板外，检查是否按住中键
        // 只有按住中键时才允许视角移动
        boolean isMiddleButtonDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS;
        if (!isMiddleButtonDown) {
            // 未按住中键，阻止视角移动
            ci.cancel();
        }
        // 如果按住中键，不取消，允许原版处理（视角移动）
    }
}
