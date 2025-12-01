package com.formacraft.mixin;

import com.formacraft.client.ui.FormaCraftChatScreen;
import net.minecraft.client.Mouse;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.input.MouseInput;

/**
 * 鼠标输入相关的 Mixin
 * 用于处理 FormaCraft 窗口打开时的鼠标中键视角移动
 */
@Mixin(Mouse.class)
public class MouseMixin {
    @Shadow @Final private MinecraftClient client;
    @Shadow private double cursorDeltaX;
    @Shadow private double cursorDeltaY;
    @Unique
    private boolean isMiddleButtonPressed = false;
    @Unique
    private double lastMouseX = 0;
    @Unique
    private double lastMouseY = 0;

    // 视角移动灵敏度
    @Unique
    private static final double LOOK_SENSITIVITY = 2.5;

    // (JDD)V 代表参数为: long, double, double -> 返回 void
    @Inject(method = "onCursorPos(JDD)V", at = @At("HEAD"))
    private void onCursorPos(long window, double x, double y, CallbackInfo ci) {
        if (client.currentScreen instanceof FormaCraftChatScreen screen) {
            // 处理窗口拖动
            if (screen.isWindowDragging()) {
                double deltaX = x - lastMouseX;
                double deltaY = y - lastMouseY;
                screen.handleMouseDrag(x, y, 0, deltaX, deltaY);
            }

            // 如果鼠标在UI外且正在按住中键，处理视角移动
            if (!screen.isMouseOverPanel(x, y) && isMiddleButtonPressed && client.player != null) {
                // 获取玩家的鼠标灵敏度设置
                double mouseSensitivity = client.options.getMouseSensitivity().getValue();
                
                // 应用灵敏度调整并移动视角
                client.player.changeLookDirection(
                    cursorDeltaX * mouseSensitivity * LOOK_SENSITIVITY,
                    cursorDeltaY * mouseSensitivity * LOOK_SENSITIVITY
                );
            }
        }
        lastMouseX = x;
        lastMouseY = y;
    }

    // 在 Minecraft 1.21.10 中，onMouseButton 方法签名已改变：
    // 新签名：onMouseButton(long window, MouseInput mouseInput, int action, CallbackInfo ci)
    // 旧签名：onMouseButton(long window, int button, int action, int mods, CallbackInfo ci)
    // MouseInput 可能是一个记录类（record），使用 button() 方法获取按钮
    @Inject(method = "onMouseButton", at = @At(value = "HEAD"))
    private void onMouseButton(long window, MouseInput mouseInput, int action, CallbackInfo ci) {
        int button = mouseInput.button();
        
        // 处理 FormaCraft 窗口拖动
        if (client.currentScreen instanceof FormaCraftChatScreen screen) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                if (action == GLFW.GLFW_PRESS) {
                    screen.handleMouseClick(client.mouse.getX(), client.mouse.getY(), 0);
                } else if (action == GLFW.GLFW_RELEASE) {
                    screen.handleMouseRelease(client.mouse.getX(), client.mouse.getY(), 0);
                }
            }
        }
        
        // 如果是鼠标中键，记录按下状态
        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            if (action == GLFW.GLFW_PRESS) {
                isMiddleButtonPressed = true;
            } else if (action == GLFW.GLFW_RELEASE) {
                isMiddleButtonPressed = false;
            }
        }
    }
}

