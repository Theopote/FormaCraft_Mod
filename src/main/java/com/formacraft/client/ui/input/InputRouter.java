package com.formacraft.client.ui.input;

import com.formacraft.client.ui.FormacraftUIState;
import com.formacraft.client.ui.FormaCraftHudOverlay;
import com.formacraft.client.tool.ToolManager;
import com.formacraft.client.preview.BuildingPreviewState;
import com.formacraft.client.ui.panel.BasePanel;
import com.formacraft.client.ui.panel.BuildConfirmPanel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.glfw.GLFW;
import com.formacraft.common.network.FormaCraftNetworking;

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
    // 最新鼠标位置，由 MouseMixin 更新
    private static double mouseX;
    private static double mouseY;

    public static boolean leftDown = false;
    public static boolean rightDown = false;
    public static boolean middleDown = false;
    
    // 标记：上一次点击是否被UI处理了
    // 用于防止UI处理点击后，Minecraft仍然处理残留状态
    private static boolean lastClickHandledByUI = false;

    /** 更新鼠标位置（来自 MouseMixin） */
    public static void updateMouse(double x, double y) {
        mouseX = x;
        mouseY = y;
    }

    /** 获取当前面板 */
    private static BasePanel getPanel() {
        if (!FormacraftUIState.isOpen) return null;
        // 面板可能还没初始化（MinecraftClient 构造期），这里确保延迟初始化
        if (!FormaCraftHudOverlay.ensurePanelsReady()) return null;
        return switch (FormaCraftHudOverlay.activePanel) {
            case CHAT -> FormaCraftHudOverlay.CHAT_PANEL;
            case BLUEPRINT -> FormaCraftHudOverlay.BLUEPRINT_PANEL;
            case TOOLS -> FormaCraftHudOverlay.TOOL_PANEL;
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

    /** 预览模态锁：只允许确认/取消。 */
    private static boolean isPreviewLocked() {
        return BuildingPreviewState.isInputLocked();
    }

    /** 鼠标点击事件 */
    public static boolean onMouseClick(double x, double y, int button, int action) {
        // 🔒 预览锁定中：只允许 BuildConfirmPanel，其他全部拦截（包含世界/工具/聊天）
        if (isPreviewLocked()) {
            // 更新按钮状态（用于中键视角逻辑等）
            switch (button) {
                case 0 -> leftDown = (action == 1);
                case 1 -> rightDown = (action == 1);
                case 2 -> middleDown = (action == 1);
            }

            if (BuildConfirmPanel.INSTANCE.isVisible() && action == 1) {
                return BuildConfirmPanel.INSTANCE.mouseClicked(x, y, button);
            }
            return true;
        }

        if (!FormacraftUIState.isOpen) {
            lastClickHandledByUI = false;
            return false;
        }

        // 更新按钮状态
        switch (button) {
            case 0 -> leftDown = (action == 1);
            case 1 -> rightDown = (action == 1);
            case 2 -> middleDown = (action == 1);
        }

        // 只处理按下事件（action == 1），释放事件（action == 0）不处理
        if (action != 1) {
            lastClickHandledByUI = false;
            return false;
        }

        // 重要：先尝试让UI处理点击，不管鼠标位置判断如何
        // 这样可以确保点击UI按钮时（如折叠按钮、关闭按钮）都能被正确处理
        
        // BuildConfirmPanel 永远优先
        if (BuildConfirmPanel.INSTANCE.isVisible()) {
            if (BuildConfirmPanel.INSTANCE.mouseClicked(x, y, button)) {
                lastClickHandledByUI = true;
                return true; // UI已处理
            }
        }

        // 尝试让面板处理点击（包括按钮、标签等）
        BasePanel panel = getPanel();
        if (panel != null && panel.mouseClicked(x, y, button)) {
            lastClickHandledByUI = true;
            return true; // UI已处理（点击了按钮或标签等）
        }

        // Tools：当选区工具激活且鼠标在面板外时，左键用于设置选区点（不让游戏破坏方块）
        boolean inside = isMouseInsideUI(x, y);
        if (!inside && button == 0) {
            if (ToolManager.handleWorldClick(x, y, button)) {
                lastClickHandledByUI = true;
                return true;
            }
        }

        // 如果UI没有处理，再检查是否在UI区域内
        // 如果在UI区域内但UI没有处理，也应该阻止游戏处理（防止误触）
        if (inside) {
            lastClickHandledByUI = true;
            return true; // 在UI区域内，阻止游戏处理
        }

        lastClickHandledByUI = false;
        return false; // 不在UI区域内，允许游戏处理
    }
    
    /** 检查上一次点击是否被UI处理了 */
    public static boolean wasLastClickHandledByUI() {
        return lastClickHandledByUI;
    }
    
    /** 清除UI处理标记（在每帧开始时调用） */
    public static void clearLastClickHandledFlag() {
        lastClickHandledByUI = false;
    }

    /** 滚轮事件 */
    public static boolean onMouseScroll(double x, double y, double amount) {
        if (isPreviewLocked()) return true;
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
        // 🔒 预览锁定：只放行 ESC/Enter（可选）
        if (isPreviewLocked()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                BuildConfirmPanel.INSTANCE.cancel();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                BuildConfirmPanel.INSTANCE.confirm();
                return true;
            }
            return true; // 其他全部拦截
        }

        if (!FormacraftUIState.isOpen) return false;

        // ESC 保留给原版
        if (keyCode == 256) return false;

        // Ctrl+Z / Ctrl+Y：Patch Undo / Redo（仅 UI 打开时生效）
        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        if (ctrl && keyCode == GLFW.GLFW_KEY_Z) {
            if (shift) FormaCraftNetworking.sendPatchRedo();
            else FormaCraftNetworking.sendPatchUndo();
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_Y) {
            FormaCraftNetworking.sendPatchRedo();
            return true;
        }

        // 重要交互规则（按用户期望）：
        // - 鼠标在面板范围内：UI 接管键盘
        // - 鼠标在面板范围外：键盘交给游戏（WASD/Shift/Space 等必须可用）

        // BuildConfirmPanel 是“模态弹窗”，即便鼠标暂时不在面板内也优先吃键盘
            if (BuildConfirmPanel.INSTANCE.isVisible()) {
                if (BuildConfirmPanel.INSTANCE.keyPressed(keyCode)) return true;
            }

        boolean inside = isMouseInsideUI();
        if (!inside) return false;

        BasePanel panel = getPanel();
            if (panel != null) {
                panel.keyPressed(keyCode, scanCode, modifiers);
                return true;
            }
        return false;
    }

    /** 字符输入事件 */
    public static boolean onCharTyped(char chr, int modifiers) {
        if (isPreviewLocked()) return true;
        if (!FormacraftUIState.isOpen) return false;

        boolean inside = isMouseInsideUI();
        if (!inside) return false;

        BasePanel panel = getPanel();
            if (panel != null) {
                panel.charTyped(chr);
                return true;
            }
        return false;
    }

    /** 鼠标拖拽事件 */
    public static boolean onMouseDragged(double x, double y, int button, double deltaX, double deltaY) {
        if (isPreviewLocked()) return true;
        if (!FormacraftUIState.isOpen) return false;

        BasePanel panel = getPanel();
        if (panel == null) return false;
        return panel.mouseDragged(x, y, button, deltaX, deltaY);
    }

    /** 鼠标释放事件 */
    public static boolean onMouseReleased(double x, double y, int button) {
        if (isPreviewLocked()) return true;
        if (!FormacraftUIState.isOpen) return false;

        BasePanel panel = getPanel();
        if (panel == null) return false;
        return panel.mouseReleased(x, y, button);
    }

    public static double getMouseX() { return mouseX; }
    public static double getMouseY() { return mouseY; }
}
