package com.formacraft.client.ui;

import com.formacraft.client.ui.input.InputRouter;
import com.formacraft.client.tool.ToolManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * 输入事件处理器
 * 处理鼠标和键盘输入，传递给 HUD Overlay
 */
@Environment(EnvType.CLIENT)
public class InputEventHandler {
    
    public static void register() {
        // 注册客户端 Tick 事件来处理输入
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            // 每帧开始时清除UI处理标记（防止一直拦截）
            InputRouter.clearLastClickHandledFlag();
        });
        
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // 确保光标状态正确
            FormacraftUIState.ensureCursorState();
            
            if (client.currentScreen != null) {
                // 如果有全屏 Screen，不处理 HUD 输入
                return;
            }
            
            // 处理鼠标点击
            if (client.mouse.wasLeftButtonClicked()) {
                double mouseX = client.mouse.getX() / client.getWindow().getScaleFactor();
                double mouseY = client.mouse.getY() / client.getWindow().getScaleFactor();
                FormaCraftHudOverlay.handleMouseClick(mouseX, mouseY, 0);
            }
            
            // 处理键盘输入（通过 Mixin 或事件系统）
            // 注意：Minecraft 的键盘输入通常通过 Screen 处理
            // 对于 HUD Overlay，我们需要特殊处理

            // Tools tick：实时预览/状态更新
            ToolManager.tick();
            
            // ComponentTool tick：构件库模式下的悬停预览（双击构件后）
            var toolState = com.formacraft.client.tool.ComponentTool.INSTANCE.getState();
            if (toolState.useLibrary) {
                com.formacraft.client.tool.ComponentTool.INSTANCE.tick();
            }
            
            // ComponentCapturePanel tick：框选工具实时更新
            if (FormaCraftHudOverlay.activePanel == com.formacraft.client.ui.panel.PanelType.COMPONENT_CAPTURE) {
                if (FormaCraftHudOverlay.COMPONENT_CAPTURE_PANEL != null) {
                    FormaCraftHudOverlay.COMPONENT_CAPTURE_PANEL.tick();
                }
            }
        });
    }
    
    /**
     * 处理键盘按键（从 Mixin 或 KeyBinding 调用）
     */
    public static boolean handleKeyPress(int keyCode, int scanCode, int modifiers) {
        return FormaCraftHudOverlay.handleKeyPress(keyCode, scanCode, modifiers);
    }
    
    /**
     * 处理字符输入（从 Mixin 或 KeyBinding 调用）
     */
    public static boolean handleCharTyped(char chr, int modifiers) {
        return FormaCraftHudOverlay.handleCharTyped(chr, modifiers);
    }
}

