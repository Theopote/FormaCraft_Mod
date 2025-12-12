package com.formacraft.client.ui.input;

import com.formacraft.client.ui.FormacraftUIState;
import com.formacraft.client.ui.FormaCraftHudOverlay;
import com.formacraft.client.ui.panel.BasePanel;
import com.formacraft.client.ui.panel.BuildConfirmPanel;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 输入路由中心（HUD 模式）
 *
 * 负责处理：
 * - 面板内：鼠标、键盘全部进入 UI
 * - 面板外：恢复原版游戏交互
 * - 中键按住时允许转动视角
 * - 不锁定鼠标，不使用 Screen
 */
public class InputRouter {

    private static final Logger LOGGER = LoggerFactory.getLogger("FormaCraft-InputRouter");
    private static final MinecraftClient client = MinecraftClient.getInstance();

    // 最新鼠标位置，由 MouseMixin 更新
    private static double mouseX;
    private static double mouseY;

    public static boolean leftDown = false;
    public static boolean rightDown = false;
    public static boolean middleDown = false;

    /** 更新鼠标位置（来自 MouseMixin） */
    public static void updateMouse(double x, double y) {
        mouseX = x;
        mouseY = y;
    }

    /** 获取当前面板 */
    private static BasePanel getPanel() {
        if (!FormacraftUIState.isOpen) return null;
        return switch (FormaCraftHudOverlay.activePanel) {
            case CHAT -> FormaCraftHudOverlay.CHAT_PANEL;
            case BLUEPRINT -> FormaCraftHudOverlay.BLUEPRINT_PANEL;
            case SETTINGS -> FormaCraftHudOverlay.SETTINGS_PANEL;
            case HISTORY -> FormaCraftHudOverlay.HISTORY_PANEL;
            default -> null;
        };
    }

    /** 是否在 UI 区域内 */
    public static boolean isMouseInsideUI(double x, double y) {
        if (!FormacraftUIState.isOpen) return false;

        BasePanel panel = getPanel();
        if (panel == null) return false;

        panel.ensureLayout();

        boolean inside = panel.isInteractiveArea(x, y);

        LOGGER.debug("[InputRouter] mouse={},{} insideUI={}", x, y, inside);

        return inside;
    }

    public static boolean isMouseInsideUI() {
        return isMouseInsideUI(mouseX, mouseY);
    }

    /** 鼠标点击事件 */
    public static boolean onMouseClick(double x, double y, int button, int action) {
        if (!FormacraftUIState.isOpen) return false;

        switch (button) {
            case 0 -> leftDown = (action == 1);
            case 1 -> rightDown = (action == 1);
            case 2 -> middleDown = (action == 1);
        }

        if (action != 1) return false;

        boolean inside = isMouseInsideUI(x, y);

        if (inside) {
            // BuildConfirmPanel 永远优先
            if (BuildConfirmPanel.INSTANCE.isVisible()) {
                if (BuildConfirmPanel.INSTANCE.mouseClicked(x, y, button)) return true;
            }

            BasePanel panel = getPanel();
            if (panel != null) panel.mouseClicked(x, y, button);

            return true;
        }

        return false; // 游戏继续处理
    }

    /** 滚轮事件 */
    public static boolean onMouseScroll(double x, double y, double amount) {
        if (!FormacraftUIState.isOpen) return false;
        boolean inside = isMouseInsideUI(x, y);

        if (inside) {
            BasePanel panel = getPanel();
            if (panel != null) panel.mouseScrolled(x, y, amount);
            return true;
        }

        return false;
    }

    /** 键盘事件 */
    public static boolean onKeyPressed(int keyCode, int scanCode, int modifiers) {
        if (!FormacraftUIState.isOpen) return false;

        // ESC 保留给原版
        if (keyCode == 256) return false;

        boolean inside = isMouseInsideUI();

        if (inside) {
            // BuildConfirmPanel 优先
            if (BuildConfirmPanel.INSTANCE.isVisible()) {
                if (BuildConfirmPanel.INSTANCE.keyPressed(keyCode)) return true;
            }

            BasePanel panel = getPanel();
            if (panel != null) {
                panel.keyPressed(keyCode, scanCode, modifiers);
                return true;
            }
        }

        return false;
    }

    /** 字符输入事件 */
    public static boolean onCharTyped(char chr, int modifiers) {
        if (!FormacraftUIState.isOpen) return false;

        boolean inside = isMouseInsideUI();

        if (inside) {
            BasePanel panel = getPanel();
            if (panel != null) {
                panel.charTyped(chr);
                return true;
            }
        }

        return false;
    }

    public static double getMouseX() { return mouseX; }
    public static double getMouseY() { return mouseY; }
}
