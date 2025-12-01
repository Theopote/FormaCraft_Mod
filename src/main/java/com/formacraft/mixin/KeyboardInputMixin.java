package com.formacraft.mixin;

import com.formacraft.client.ui.FormaCraftChatScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.client.input.Input;
import net.minecraft.util.PlayerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.Window;

/**
 * 键盘输入相关的 Mixin
 * 用于处理 FormaCraft 窗口打开时的键盘移动输入
 */
@Mixin(KeyboardInput.class)
public class KeyboardInputMixin extends Input {
    // 这些字段在 Input 父类中，通过继承访问
    // 在 Minecraft 1.21.10 中，可能需要通过反射或 Accessor 访问

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void onMovementTick(CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.currentScreen instanceof FormaCraftChatScreen screen) {
            // 检查鼠标是否在UI内
            boolean mouseOverPanel = screen.isMouseOverPanel(client.mouse.getX(), client.mouse.getY());
            
            if (mouseOverPanel) {
                // 鼠标在UI内时，取消原版处理，阻止移动输入（让UI处理键盘输入）
                ci.cancel();
            } else {
                // 鼠标在UI外时，手动处理键盘输入，允许玩家移动
                Window window = client.getWindow();

                // 直接设置移动状态
                this.movementForward = 0.0F;
                this.movementSideways = 0.0F;

                // 检查按键并更新移动
                if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_W)) this.movementForward += 1.0F;
                if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_S)) this.movementForward -= 1.0F;
                if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_D)) this.movementSideways -= 1.0F;
                if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_A)) this.movementSideways += 1.0F;

                // 更新玩家输入状态
                boolean forward = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_W);
                boolean backward = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_S);
                boolean left = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_A);
                boolean right = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_D);
                boolean jump = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_SPACE);
                boolean sneak = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT_SHIFT);
                boolean sprint = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT_CONTROL);

                // 更新 PlayerInput
                this.playerInput = new PlayerInput(forward, backward, left, right, jump, sneak, sprint);

                // 处理慢速移动
                if (sneak) {
                    this.movementSideways *= 0.3F;
                    this.movementForward *= 0.3F;
                }

                ci.cancel(); // 阻止原版处理，使用我们手动处理的输入
            }
        }
    }
}

