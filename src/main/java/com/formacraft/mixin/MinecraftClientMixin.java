package com.formacraft.mixin;

import com.formacraft.client.ui.FormacraftUIState;
import com.formacraft.client.ui.input.InputRouter;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MinecraftClient Mixin
 * <p>
 * 拦截 handleInputEvents 方法，当鼠标在面板内时阻止所有输入处理。
 * 这是必要的，因为即使我们在 MouseMixin 中 cancel 了鼠标事件，
 * Minecraft 的 handleInputEvents 仍然会处理鼠标输入，导致玩家动作（如攻击、使用物品）。
 */
@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {

    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger("FormaCraft-MinecraftClientMixin");

    /**
     * 拦截 handleInputEvents 方法
     * <p>
     * 这是最后一道防线：当鼠标在面板内时，阻止所有游戏输入处理。
     * 即使 MouseMixin 和 KeyboardMixin 已经拦截了事件，Minecraft 的 handleInputEvents
     * 仍然可能处理一些残留的输入状态（如鼠标按钮状态），导致玩家动作。
     * <p>
     * 注意：我们只在面板内时拦截，面板外完全允许正常游戏操作。
     */
    @Inject(method = "handleInputEvents", at = @At("HEAD"), cancellable = true)
    private void onHandleInputEvents(CallbackInfo ci) {
        MinecraftClient client = (MinecraftClient) (Object) this;
        
        // 重要：UI 关闭时，完全不处理，让游戏使用默认行为
        // 如果 UI 未打开或有 Screen，不处理
        if (!FormacraftUIState.isOpen || client.currentScreen != null) {
            return;
        }
        
        // 更新鼠标位置（确保是最新的）
        // Mouse.getX() 返回窗口坐标，需要转换为 scaled 坐标
        double rawMouseX = client.mouse.getX();
        double rawMouseY = client.mouse.getY();
        double mouseX = rawMouseX / client.getWindow().getScaleFactor();
        double mouseY = rawMouseY / client.getWindow().getScaleFactor();
        InputRouter.updateMouse(mouseX, mouseY);
        
        // 检查鼠标是否在面板内
        boolean insidePanel = InputRouter.isMouseInsideUI();
        
        // 只在调试时输出详细日志（减少日志量，提高性能）
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[MinecraftClientMixin.handleInputEvents] 原始鼠标=({}, {}), scaled鼠标=({}, {}), 面板内={}, 将{}拦截", 
                    String.format("%.2f", rawMouseX), String.format("%.2f", rawMouseY),
                    String.format("%.2f", mouseX), String.format("%.2f", mouseY),
                    insidePanel, insidePanel ? "完全" : "不");
        }
        
        if (insidePanel) {
            // 鼠标在面板内，完全阻止所有输入处理（包括移动、攻击、使用物品等）
            // 这是必要的，因为即使我们 cancel 了鼠标事件，Minecraft 仍可能处理残留的输入状态
            // 完全 cancel，阻止 KeyBinding 更新、鼠标处理、键盘处理等所有输入逻辑
            // 注意：通过 cancel handleInputEvents，Minecraft 不会更新 KeyBinding 状态，
            // 这应该能防止残留的 WASD 键导致玩家继续移动
            
            // 只在调试时输出警告（减少日志量）
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("[MinecraftClientMixin] 面板内，完全拦截 handleInputEvents");
            }
            ci.cancel();
        }
        // 如果鼠标在面板外，不拦截，允许正常游戏操作
    }
}

