package com.formacraft.client.ui.input;

import com.formacraft.client.ui.FormacraftUIState;
import com.formacraft.client.ui.FormaCraftHudOverlay;
import com.formacraft.client.ui.panel.BasePanel;
import com.formacraft.client.ui.panel.BuildConfirmPanel;
import org.lwjgl.glfw.GLFW;

/**
 * 输入转发中心（HUD 模式）
 * 统一处理面板区域的输入事件，转发给当前激活的 Panel
 * 根据鼠标位置判断是否在面板内，面板内完全拦截，面板外允许正常游戏操作
 */
public class InputRouter {

    /**
     * 获取当前激活的面板
     */
    private static BasePanel getActivePanel() {
        if (!FormacraftUIState.isOpen) {
            return null;
        }
        
        return switch (FormaCraftHudOverlay.activePanel) {
            case CHAT -> FormaCraftHudOverlay.CHAT_PANEL;
            case BLUEPRINT -> FormaCraftHudOverlay.BLUEPRINT_PANEL;
            case SETTINGS -> FormaCraftHudOverlay.SETTINGS_PANEL;
            case HISTORY -> FormaCraftHudOverlay.HISTORY_PANEL;
            case NONE -> null;
        };
    }
    
    /**
     * 检查鼠标是否在面板内
     * @param mouseX 鼠标 X 坐标
     * @param mouseY 鼠标 Y 坐标
     * @return 是否在面板内
     */
    public static boolean isMouseInsidePanel(double mouseX, double mouseY) {
        if (!FormacraftUIState.isOpen) return false;
        BasePanel panel = getActivePanel();
        if (panel == null) return false;
        return panel.isMouseOver(mouseX, mouseY);
    }
    
    /**
     * 检查是否应该阻止 Minecraft 控制（面板内时）
     * @param mouseX 鼠标 X 坐标
     * @param mouseY 鼠标 Y 坐标
     * @return 是否应该阻止
     */
    public static boolean shouldBlockMCControls(double mouseX, double mouseY) {
        return FormacraftUIState.isOpen && isMouseInsidePanel(mouseX, mouseY);
    }

    /**
     * 处理鼠标点击
     * @param mouseX 鼠标 X 坐标（屏幕坐标）
     * @param mouseY 鼠标 Y 坐标（屏幕坐标）
     * @param button 鼠标按钮（0=左键, 1=右键, 2=中键）
     * @return 是否已处理（true = 阻止原版处理）
     */
    public static boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 如果 UI 未打开，不处理
        if (!FormacraftUIState.isOpen) {
            return false;
        }
        
        // 1. 如果确认面板开启，优先交给它处理
        if (BuildConfirmPanel.INSTANCE.isVisible()) {
            boolean consumed = BuildConfirmPanel.INSTANCE.mouseClicked(mouseX, mouseY, button);
            if (consumed) return true;
        }
        
        // 2. 检查鼠标是否在面板内
        BasePanel panel = getActivePanel();
        if (panel != null && isMouseInsidePanel(mouseX, mouseY)) {
            // 鼠标在面板内，完全拦截
            panel.mouseClicked(mouseX, mouseY, button);
            return true; // 无论面板是否处理，都阻止点击影响游戏
        }

        // 3. 鼠标在面板外，不拦截（允许正常游戏操作）
        return false;
    }

    /**
     * 处理鼠标滚轮
     * @param mouseX 鼠标 X 坐标
     * @param mouseY 鼠标 Y 坐标
     * @param amount 滚动量
     * @return 是否已处理
     */
    public static boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        // 如果 UI 未打开，不处理
        if (!FormacraftUIState.isOpen) {
            return false;
        }
        
        // 如果确认面板开启，直接消费滚轮事件
        if (BuildConfirmPanel.INSTANCE.isVisible()) {
            return true;
        }
        
        // 检查鼠标是否在面板内
        BasePanel panel = getActivePanel();
        if (panel != null && isMouseInsidePanel(mouseX, mouseY)) {
            // 鼠标在面板内，完全拦截
            panel.mouseScrolled(mouseX, mouseY, amount);
            return true; // 阻止滚轮影响游戏
        }

        // 鼠标在面板外，不拦截（允许正常游戏操作）
        return false;
    }

    /**
     * 处理键盘按键
     * @param keyCode 按键代码
     * @param scanCode 扫描代码
     * @param modifiers 修饰符
     * @return 是否已处理
     */
    public static boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 如果 UI 未打开，不处理
        if (!FormacraftUIState.isOpen) {
            return false;
        }
        
        // 1. 如果确认面板开启，优先交给它处理
        if (BuildConfirmPanel.INSTANCE.isVisible()) {
            boolean consumed = BuildConfirmPanel.INSTANCE.keyPressed(keyCode);
            if (consumed) return true;
        }
        
        // ESC 不拦截，交给游戏（打开菜单）
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            return false;
        }
        
        // 2. 获取鼠标位置，判断是否在面板内
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        double mouseX = client.mouse.getX() * client.getWindow().getScaledWidth() / client.getWindow().getWidth();
        double mouseY = client.mouse.getY() * client.getWindow().getScaledHeight() / client.getWindow().getHeight();
        
        // 3. 如果鼠标在面板内，交给面板处理（输入文字）
        BasePanel panel = getActivePanel();
        if (panel != null && isMouseInsidePanel(mouseX, mouseY)) {
            panel.keyPressed(keyCode, scanCode, modifiers);
            return true; // 拦截按键，防止影响游戏
        }

        // 4. 如果鼠标在面板外，不拦截（允许正常移动）
        return false;
    }

    /**
     * 处理字符输入
     * @param chr 输入的字符
     * @param modifiers 修饰符
     * @return 是否已处理
     */
    public static boolean charTyped(char chr, int modifiers) {
        // 如果 UI 未打开，不处理
        if (!FormacraftUIState.isOpen) {
            return false;
        }
        
        // 如果确认面板开启，不接受字符输入
        if (BuildConfirmPanel.INSTANCE.isVisible()) {
            return true;
        }
        
        // 获取鼠标位置，判断是否在面板内
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        double mouseX = client.mouse.getX() * client.getWindow().getScaledWidth() / client.getWindow().getWidth();
        double mouseY = client.mouse.getY() * client.getWindow().getScaledHeight() / client.getWindow().getHeight();
        
        // 如果鼠标在面板内，交给面板处理（输入文字）
        BasePanel panel = getActivePanel();
        if (panel != null && isMouseInsidePanel(mouseX, mouseY)) {
            panel.charTyped(chr);
            return true; // 拦截字符输入，防止影响游戏
        }

        // 如果鼠标在面板外，不拦截（允许正常输入）
        return false;
    }
}

